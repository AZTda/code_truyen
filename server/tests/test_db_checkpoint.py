"""SQLite WAL + bind-mount MỘT tệp: dữ liệu phải được gộp vào tệp chính định kỳ và khi tắt máy chủ.

docker-compose.yml chỉ mount ./truyencloud.db; -wal/-shm nằm trong lớp ghi của container và mất khi container bị tạo lại.
`copy_main_file_only` mô phỏng đúng điều đó: chỉ tệp chính sống sót.
"""

import asyncio
import shutil
import sqlite3
import threading

import pytest
from fastapi.testclient import TestClient

import database
from database import SqliteBackedTable, checkpoint, close_all_connections, get_db_connection
from services.auth_token import issue_token
from services.cache_janitor import CacheJanitor


def copy_main_file_only(db_file, tmp_path):
    copy = tmp_path / "main_only.db"
    shutil.copyfile(db_file, copy)
    return copy


def count_rows(db_copy, table):
    con = sqlite3.connect(db_copy)
    try:
        return con.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
    except sqlite3.OperationalError:
        return -1  # bảng chưa có trong tệp chính
    finally:
        con.close()


@pytest.fixture
def db_file(tmp_path):
    path = str(tmp_path / "wal_probe.db")
    table = SqliteBackedTable("progress_probe", db_path=path)
    for i in range(5):
        table.upsert({"id": f"row{i}", "user_id": "u", "value": i})
    yield path
    close_all_connections(path)


def test_new_writes_live_only_in_the_wal_until_a_checkpoint(db_file, tmp_path):
    assert count_rows(copy_main_file_only(db_file, tmp_path), "progress_probe") != 5  # tiền đề: -wal đang giữ dữ liệu

    result = checkpoint(db_file)
    assert result is not None and result[0] == 0  # busy = 0: checkpoint trọn vẹn
    assert count_rows(copy_main_file_only(db_file, tmp_path), "progress_probe") == 5
    assert (tmp_path / "wal_probe.db-wal").stat().st_size == 0  # TRUNCATE: -wal về 0 byte


def test_checkpoint_never_creates_a_missing_database(tmp_path):
    missing = tmp_path / "nothing_here.db"
    assert checkpoint(str(missing)) is None
    assert not missing.exists()


def test_checkpoint_rejects_unknown_modes(db_file):
    with pytest.raises(ValueError):
        checkpoint(db_file, mode="TRUNCATE; DROP TABLE x")


def test_requests_become_durable_in_the_main_file_after_checkpoint(tmp_path):
    client = TestClient(__import__("server").app)
    headers = {"Authorization": f"Bearer {issue_token('user_wal', 'wal')}"}
    for novel in ("a", "b", "c"):
        assert client.post("/v1/progress/sync", headers=headers, json={"novel_id": novel, "current_chapter": 1}).status_code == 200

    assert checkpoint() is not None  # DB_PATH mặc định đã được test cách ly vào tmp_path
    assert count_rows(copy_main_file_only(database.DB_PATH, tmp_path), "reading_progress_backend") == 3


def test_close_all_connections_reaches_other_live_threads_and_reopens_cleanly(db_file):
    opened, release = [], threading.Event()

    def worker():
        opened.append(get_db_connection(db_file))
        release.wait(10)  # thread còn sống trong lúc main gọi close_all_connections

    thread = threading.Thread(target=worker)
    thread.start()
    while not opened:
        thread.join(0.01)
    main_conn = get_db_connection(db_file)
    assert close_all_connections(db_file) >= 2
    with pytest.raises(sqlite3.ProgrammingError):
        opened[0].execute("SELECT 1")  # kết nối của thread khác đã thật sự bị đóng
    release.set()
    thread.join()
    assert get_db_connection(db_file) is not main_conn
    assert get_db_connection(db_file).execute("SELECT COUNT(*) FROM progress_probe").fetchone()[0] == 5


def test_a_finished_thread_releases_its_connection_like_a_plain_thread_local():
    import gc

    holder_refs = []

    def worker():
        get_db_connection(database.DB_PATH).execute("SELECT 1")
        holder_refs.append(__import__("weakref").ref(database._local_storage.holder))

    thread = threading.Thread(target=worker)
    thread.start()
    thread.join()
    gc.collect()
    assert holder_refs[0]() is None  # không rò kết nối theo số thread đã từng tồn tại


def test_cache_janitor_checkpoints_on_its_own_short_interval(monkeypatch):
    calls = []
    monkeypatch.setattr(database, "checkpoint", lambda *a, **k: calls.append(1) or (0, 0, 0))

    async def scenario():
        janitor = CacheJanitor(interval_seconds=3600, checkpoint_interval_seconds=0.02)
        janitor.start()
        await asyncio.sleep(0.25)
        janitor.stop()
        await asyncio.sleep(0.05)
        return janitor

    janitor = asyncio.run(scenario())
    assert len(calls) >= 3
    assert janitor.is_running is False and janitor._task is None and janitor._checkpoint_task is None


def test_default_janitor_checkpoints_about_every_ten_minutes():
    assert CacheJanitor().checkpoint_interval_seconds == 600


def test_lifespan_shutdown_checkpoints_early_and_again_before_closing_connections(monkeypatch):
    import server
    from services.agy_pool import agy_pool

    order = []

    async def nothing(*args, **kwargs):
        return None

    monkeypatch.setattr(server, "checkpoint", lambda *a, **k: order.append("checkpoint"))
    monkeypatch.setattr(server, "close_all_connections", lambda *a, **k: order.append("close"))
    for service in (server.auto_updater, server.cache_janitor):  # không khởi động tác vụ nền thật
        monkeypatch.setattr(service, "start", lambda *a, **k: None)
        monkeypatch.setattr(service, "stop", lambda *a, **k: None)
    monkeypatch.setattr(server.storage_service, "sync_all_to_database", lambda *a, **k: None)
    monkeypatch.setattr(agy_pool, "warmup", nothing)
    monkeypatch.setattr(agy_pool, "stop", nothing)

    async def run():
        async with server.lifespan(server.app):
            order.append("running")

    asyncio.run(run())
    # Checkpoint sớm (phòng bị SIGKILL giữa chừng), rồi checkpoint + đóng kết nối ở cuối.
    assert order == ["running", "checkpoint", "checkpoint", "close"]


def test_sqlite_table_merges_with_persisted_row_on_index_cache_miss(tmp_path):
    path = str(tmp_path / "merge_probe.db")
    try:
        table = SqliteBackedTable("chapters", db_path=path)
        table.upsert({
            "novel_id": "n1",
            "chapter_number": 1,
            "title": "Chương 1",
            "original_content": "Nội dung gốc dài",
            "translated_content": "Nội dung dịch dài",
        })
        # Giả lập vượt ngưỡng LRU _index hoặc khởi động lại tiến trình
        table._index.clear()
        table.batch_upsert([{
            "novel_id": "n1",
            "chapter_number": 1,
            "title": "Chương 1 (mới)",
            "title_vi": "Chương 1 VI",
        }])
        table._index.clear()
        row = table.select().eq("pkey", "n1_1").single().execute().data
        assert row is not None
        assert row["title_vi"] == "Chương 1 VI"
        assert row["original_content"] == "Nội dung gốc dài"
        assert row["translated_content"] == "Nội dung dịch dài"
    finally:
        close_all_connections(path)


def test_parse_epub_supports_href_before_id_in_manifest():
    import io
    import zipfile
    from services.import_service import import_service

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr(
            "META-INF/container.xml",
            '<?xml version="1.0"?><container><rootfiles>'
            '<rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>'
            '</rootfiles></container>',
        )
        z.writestr(
            "OEBPS/content.opf",
            '<?xml version="1.0"?><package><metadata>'
            '<dc:title>Truyện EPUB</dc:title><dc:creator>Tác giả A</dc:creator>'
            '</metadata><manifest>'
            '<item media-type="application/xhtml+xml" href="ch1.xhtml" id="item_ch1"/>'
            '</manifest><spine><itemref linear="yes" idref="item_ch1"/></spine></package>',
        )
        z.writestr(
            "OEBPS/ch1.xhtml",
            "<html><body><h1>Chương 1: Khởi đầu</h1><p>" + ("Nội dung chương một rất dài. " * 5) + "</p></body></html>",
        )

    title, author, chapters = import_service.parse_epub(buf.getvalue())
    assert title == "Truyện EPUB"
    assert author == "Tác giả A"
    assert len(chapters) == 1
    assert chapters[0][0] == "Chương 1: Khởi đầu"
