"""Dữ liệu và tiện ích dùng chung cho tests/test_discover_*.py (file này không chứa test).

Danh mục cố định gồm 8 truyện công khai và 1 truyện nạp riêng tư. Tác giả / nguồn / ngày được chọn để mỗi
kịch bản cho một thứ tự khác nhau và đoán trước được:

    a1 Thần Đông  69shuba  ngày 3     b1 Mặc Vũ  jjwxc    ngày 15    c1 Lạc Tử  piaotia   ngày 20
    a2 Thần Đông  69shuba  ngày 2     b2 Mặc Vũ  jjwxc    ngày 4     c2 Lạc Tử  piaotia   ngày 6
    d1 Hà Phong   uukanshu ngày 12    d2 Tô Hạ   uukanshu ngày 8      import_abcd1234 (riêng tư, tác giả Lạc Tử)
"""

import pytest
from fastapi.testclient import TestClient

from database import SqliteBackedTable, db
from server import app
from services.auth_token import issue_token
from services.library_index import library_index
from services.novel_catalog import PROGRESS_TABLE, reader_counter

client = TestClient(app)

PRIVATE_ID = "import_abcd1234"
LEGACY_ITEM_KEYS = {"id", "title", "title_vi", "author", "cover_url", "total_chapters",
                    "source_id", "source_url", "description", "status",
                    "category", "overnight_enabled", "translated_chapters"}

CATALOG = [
    ("a1", "Thần Đông", "69shuba", 3), ("a2", "Thần Đông", "69shuba", 2),
    ("b1", "Mặc Vũ", "jjwxc", 15), ("b2", "Mặc Vũ", "jjwxc", 4),
    ("c1", "Lạc Tử", "piaotia", 20), ("c2", "Lạc Tử", "piaotia", 6),
    ("d1", "Hà Phong", "uukanshu", 12), ("d2", "Tô Hạ", "uukanshu", 8),
]


def make_entry(nid, day, author=None, source_id="69shuba", **extra):
    novel = {"id": nid, "title": f"T {nid}", "title_vi": f"Truyện {nid}",
             "author": f"Tác giả {nid}" if author is None else author,
             "source_id": source_id, "source_url": f"https://x/{nid}", "total_chapters": 3,
             "updated_at": f"2026-09-{day:02d}T00:00:00+00:00"}
    novel.update(extra)
    return {"novel": novel, "id": nid, "title_vi": novel["title_vi"]}


class CountingTable(SqliteBackedTable):
    """Đếm số lần `select` mỗi bảng: mỗi lượt dựng ReaderSnapshot đúng một lần select bảng tiến độ."""
    selects = {}

    def select(self, *args, **kwargs):
        CountingTable.selects[self.name] = CountingTable.selects.get(self.name, 0) + 1
        return super().select(*args, **kwargs)


@pytest.fixture
def catalog(tmp_path, monkeypatch):
    """(table, entries): danh mục cố định trong bộ nhớ + bảng SQLite tạm; bộ đệm người đọc được xoá trước và sau."""
    tables = {}
    CountingTable.selects = {}

    def table(name):
        if name not in tables:
            tables[name] = CountingTable(name, db_path=str(tmp_path / "discover.db"))
        return tables[name]

    entries = [make_entry(nid, day, author, source) for nid, author, source, day in CATALOG]
    entries.append(make_entry(PRIVATE_ID, 25, "Lạc Tử", "local_import"))
    monkeypatch.setattr(db, "table", table)
    monkeypatch.setattr(library_index, "get_entries", lambda: entries)
    reader_counter.invalidate()
    yield table, entries
    reader_counter.invalidate()


def bearer(uid):
    return {"Authorization": f"Bearer {issue_token(uid, uid)}"}


def shelve(table, uid, *novel_ids, shelf="reading"):
    """Đặt các truyện lên kệ `uid` (shelf='removed' là bia mộ app ghi khi gỡ truyện khỏi tủ)."""
    for nid in novel_ids:
        table(PROGRESS_TABLE).upsert({"id": f"progress_{uid}_{nid}", "user_id": uid, "novel_id": nid,
                                      "current_chapter": 1, "shelf_name": shelf})


def discover(sort="for_you", uid=None, limit=None, headers=None, fresh=True):
    """GET /v1/novels/discover. `fresh` xoá bộ đệm tiến độ trước để thấy ngay các dòng vừa ghi."""
    if fresh:
        reader_counter.invalidate()
    params = {"sort": sort} if sort else {}
    if limit is not None:
        params["limit"] = limit
    sent = dict(headers or {})
    if uid:
        sent.update(bearer(uid))
    return client.get("/v1/novels/discover", params=params, headers=sent)


def ids(response):
    return [novel["id"] for novel in response.json()["novels"]]


def reasons(response):
    return {novel["id"]: novel["reason"] for novel in response.json()["novels"]}
