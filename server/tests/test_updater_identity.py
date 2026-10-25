"""Auto-updater so khớp chương theo URL/id thật, không nhân đôi mục lục; mục lục rỗng là lỗi nguồn.

Nền: POST /v1/scrape ghi chapters_index.json với id = str(số chương) còn scraper (piaotia, 69shuba...) trả id
của nguồn. Bản cũ so id với id nên mọi chương nguồn đều "mới": mục lục dài gấp đôi và hai chương ĐẦU bị dịch
trước thay vì chương mới thật.
"""

import json
import os
import time
from datetime import datetime
from types import SimpleNamespace

import pytest

from services import chapters_index_repair
from services.auto_updater import AutoUpdater
from services.storage_service import storage_service
from services.toc_identity import diff_toc, normalize_url, real_id
from services.toc_lookup import TocLookup, TocUnavailableError, FAILED_RETRY_SECONDS, toc_lookup
from services.translation_validation import ChapterTranslation
from services.translation_worker import translation_worker
from tests._fakes import isolated  # noqa: F401 (fixture)

BASE = "https://www.piaotia.com/html/10/10527"


def _remote(n, base=BASE):
    """Chương nguồn như scraper piaotia dựng: id = tên tệp trong URL (id nguồn), không phải số chương."""
    src_id = str(7513500 + n)
    return SimpleNamespace(chapter_number=n, id=src_id, title=f"第{n}章", url=f"{base}/{src_id}.html")


def _stored(n, base=BASE):
    """Mục đã lưu như POST /v1/scrape ghi: id = str(số chương)."""
    return {"chapter_number": n, "id": str(n), "title": f"第{n}章", "title_vi": f"Chương {n}",
            "url": f"{base}/{7513500 + n}.html"}


class FakeScraper:
    def __init__(self, chapters):
        self.chapters = chapters
        self.content_calls = []

    async def fetch_novel_info(self, url):
        return SimpleNamespace(chapters=self.chapters)

    async def fetch_chapter_content(self, url):
        self.content_calls.append(url)
        return "林动握紧长剑。" * 40


@pytest.fixture
def updater_env(isolated, monkeypatch):
    monkeypatch.setattr(toc_lookup, "_entries", type(toc_lookup._entries)(500))
    monkeypatch.setattr(toc_lookup, "_failed_at", type(toc_lookup._failed_at)(500))
    calls = SimpleNamespace(titles=[], translate=[], scraper=None)

    async def fake_titles(titles, novel_id=None):
        calls.titles.append(list(titles))
        return [f"Chương {t}" for t in titles]

    async def fake_translate(**kwargs):
        calls.translate.append(kwargs)
        return ChapterTranslation("Lâm Động siết chặt trường kiếm.")

    monkeypatch.setattr(translation_worker, "translate_chapter_titles_batch", fake_titles)
    monkeypatch.setattr(translation_worker, "translate_chapter_detailed", fake_translate)

    def install(remote, stored):
        calls.scraper = FakeScraper(remote)
        monkeypatch.setattr("services.auto_updater.get_scraper_for_url", lambda url: calls.scraper)
        storage_service.save_novel_info("nv", {"source_url": f"{BASE}/index.html"})
        storage_service.save_chapters_index("nv", stored)
        return calls
    return install


# ---------------------------------------------------------------------------
# Auto-updater
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_id_da_luu_la_so_chuong_con_id_nguon_khac_van_khong_nhan_doi_muc_luc(updater_env):
    calls = updater_env([_remote(n) for n in range(1, 31)], [_stored(n) for n in range(1, 31)])
    result = await AutoUpdater().check_novel_for_updates("nv")

    assert result["status"] == "up_to_date" and result["new_chapters_found"] == 0
    assert len(storage_service.list_chapters("nv")) == 30, "Mục lục không được phình ra"
    assert calls.scraper.content_calls == [] and calls.translate == [] and calls.titles == []


@pytest.mark.asyncio
async def test_chi_chuong_moi_that_duoc_them_va_dich_truoc_la_chuong_moi_khong_phai_chuong_cu(updater_env):
    remote = [_remote(n) for n in range(1, 34)]          # 30 chương đã có + 3 chương mới thật
    calls = updater_env(remote, [_stored(n) for n in range(1, 31)])
    result = await AutoUpdater().check_novel_for_updates("nv")

    assert result["status"] == "updated" and result["new_chapters_found"] == 3
    index = storage_service.list_chapters("nv")
    assert [c["chapter_number"] for c in index[-3:]] == [31, 32, 33] and len(index) == 33
    assert [c["url"] for c in index[-3:]] == [r.url for r in remote[-3:]]
    # Hai chương mới ĐẦU TIÊN được dịch trước, tuyệt đối không phải chương 1-2 cũ
    assert calls.scraper.content_calls == [remote[30].url, remote[31].url]
    assert storage_service.get_chapter("nv", 31)["url"] == remote[30].url
    assert storage_service.get_chapter("nv", 1) is None


@pytest.mark.asyncio
async def test_muc_luc_hon_hop_id_tong_hop_va_id_nguon_nhu_du_lieu_that(updater_env):
    """Dữ liệu thật (10_10527): 7730 mục id = số chương, rồi 6 mục do bản cũ thêm với id nguồn."""
    stored = [_stored(n) for n in range(1, 25)]
    stored += [{**_stored(n), "id": str(7513500 + n)} for n in range(25, 31)]
    updater_env([_remote(n) for n in range(1, 31)], stored)
    result = await AutoUpdater().check_novel_for_updates("nv")
    assert result["status"] == "up_to_date" and len(storage_service.list_chapters("nv")) == 30


@pytest.mark.asyncio
async def test_url_doi_giao_thuc_www_dau_gach_cuoi_van_la_chuong_da_biet(updater_env):
    stored = [{**_stored(n), "url": _stored(n)["url"].replace("https://www.", "http://") + "/"} for n in range(1, 11)]
    updater_env([_remote(n) for n in range(1, 11)], stored)
    result = await AutoUpdater().check_novel_for_updates("nv")
    assert result["status"] == "up_to_date"


@pytest.mark.asyncio
async def test_khong_khop_duoc_chuong_nao_thi_khong_them_ca_loat(updater_env):
    """Nguồn đổi tên miền/dạng URL: không chương nào khớp -> báo lỗi, không nhân đôi mục lục."""
    calls = updater_env([_remote(n, base="https://mirror.example/b/9") for n in range(1, 31)],
                        [_stored(n) for n in range(1, 31)])
    result = await AutoUpdater().check_novel_for_updates("nv")
    assert result["status"] == "error" and "khớp" in result["message"]
    assert len(storage_service.list_chapters("nv")) == 30
    assert calls.scraper.content_calls == [] and calls.translate == []


@pytest.mark.asyncio
async def test_muc_luc_rong_la_loi_nguon_khong_ghi_nhan_va_dat_thoi_gian_cho(updater_env):
    updater_env([], [_stored(n) for n in range(1, 31)])
    result = await AutoUpdater().check_novel_for_updates("nv")
    assert result["status"] == "error"
    assert not toc_lookup.refreshed_recently("nv"), "Mục lục rỗng không được nhớ là mới"
    assert toc_lookup._failed_at.get("nv"), "Phải đặt thời gian chờ thử lại"
    assert len(storage_service.list_chapters("nv")) == 30


@pytest.mark.asyncio
async def test_phat_hien_muc_luc_da_bi_nhan_doi_va_chi_bao_khong_tu_sua(updater_env, caplog):
    stored = [_stored(n) for n in range(1, 11)]
    stored += [{**_stored(n), "chapter_number": 10 + n, "id": str(7513500 + n)} for n in range(1, 11)]
    updater_env([_remote(n) for n in range(1, 11)], stored)
    with caplog.at_level("WARNING", logger="truyencloud.auto_updater"):
        result = await AutoUpdater().check_novel_for_updates("nv")
    assert result["status"] == "up_to_date" and result["duplicate_index_entries"] == 10
    assert "chapters_index_repair" in caplog.text
    assert len(storage_service.list_chapters("nv")) == 20


# ---------------------------------------------------------------------------
# Nhận diện chương (hàm thuần)
# ---------------------------------------------------------------------------

def test_id_so_bang_so_chuong_la_id_tong_hop_khong_dung_de_so_khop():
    assert real_id({"id": "5", "chapter_number": 5}) == ""
    assert real_id({"id": "007", "chapter_number": 7}) == ""
    assert real_id({"id": "13060824", "chapter_number": 7736}) == "13060824"
    assert real_id(SimpleNamespace(id="c5", chapter_number=5)) == "c5"
    assert real_id({"chapter_number": 5}) == ""


def test_id_tong_hop_cua_nguon_khong_dung_id_that_cua_chuong_khac():
    stored = [{"chapter_number": 3, "id": "5", "url": "https://s.example/a"},
              {"chapter_number": 4, "id": "4", "url": "https://s.example/b"}]
    remote = [SimpleNamespace(chapter_number=5, id="5", url="https://s.example/e")]
    assert [c.url for c in diff_toc(stored, remote).new] == ["https://s.example/e"]
    # ...còn id nguồn thật vẫn khớp được kể cả khi URL đã đổi
    remote = [SimpleNamespace(chapter_number=9, id="5", url="https://s.example/moved")]
    diff = diff_toc(stored, remote)
    assert diff.new == [] and diff.matched == 1


def test_chuan_hoa_url_va_chuong_khong_co_url_khong_phai_chuong_moi():
    assert normalize_url("HTTP://WWW.Site.com/a/b.html/") == normalize_url("https://site.com/a/b.html")
    assert normalize_url("http://j.net/o.php?novelid=1&chapterid=2") == normalize_url("https://j.net/o.php?chapterid=2&novelid=1")
    assert normalize_url("https://s.example/a") != normalize_url("https://s.example/b")
    diff = diff_toc([], [SimpleNamespace(chapter_number=1, id="", url=""),
                         SimpleNamespace(chapter_number=2, id="x", url="https://s.example/2"),
                         SimpleNamespace(chapter_number=3, id="y", url="https://s.example/2")])
    assert [c.chapter_number for c in diff.new] == [2], "Không URL thì không cào được; URL trùng chỉ tính một lần"


# ---------------------------------------------------------------------------
# TocLookup: mục lục rỗng là thất bại
# ---------------------------------------------------------------------------

def _ch(n):
    return SimpleNamespace(chapter_number=n, id=str(n), title=f"T{n}", url=f"u{n}")


@pytest.mark.asyncio
async def test_muc_luc_rong_khong_bi_nho_la_moi_va_lan_sau_cao_lai_duoc():
    lookup, calls = TocLookup(), []

    async def fetch(url):
        calls.append(1)
        return [] if len(calls) == 1 else [_ch(1), _ch(2), _ch(3)]

    with pytest.raises(TocUnavailableError):
        await lookup.find("n", "u", 3, fetch)               # nguồn trả trang rỗng/bị chặn
    assert not lookup.refreshed_recently("n")
    with pytest.raises(TocUnavailableError):
        await lookup.find("n", "u", 3, fetch)               # trong thời gian chờ: không cào lại
    assert len(calls) == 1

    lookup._failed_at["n"] = time.time() - FAILED_RETRY_SECONDS - 1
    assert await lookup.find("n", "u", 3, fetch) == ("u3", "T3")
    assert len(calls) == 2 and lookup.refreshed_recently("n")


def test_remember_bo_qua_muc_luc_rong():
    lookup = TocLookup()
    lookup.remember("n", [])
    assert not lookup.refreshed_recently("n")
    lookup.remember("n", [_ch(1)])
    assert lookup.refreshed_recently("n")


# ---------------------------------------------------------------------------
# Công cụ sửa mục lục đã bị nhân đôi
# ---------------------------------------------------------------------------

def _write_doubled_novel(tmp_path, n=6, extra_real=0):
    novel_dir = tmp_path / "novels" / "dup"
    chapters_dir = novel_dir / "chapters"
    chapters_dir.mkdir(parents=True)
    index = [_stored(i) for i in range(1, n + 1)]
    index += [{**_stored(i), "chapter_number": n + i, "id": str(7513500 + i)} for i in range(1, n + 1)]
    for k in range(extra_real):          # chương mới THẬT thêm sau khối nhân đôi
        index.append({"chapter_number": 2 * n + k + 1, "id": f"new{k}", "title": "t", "title_vi": "",
                      "url": f"{BASE}/new{k}.html"})
    (novel_dir / "chapters_index.json").write_text(json.dumps(index, ensure_ascii=False), encoding="utf-8")
    for num in (1, 2, n + 1, n + 2):     # bản cào thừa của chương 1-2 lưu thành #n+1, #n+2
        (chapters_dir / f"chapter_{num}.json").write_text(json.dumps({"chapter_number": num}), encoding="utf-8")
    return novel_dir


def test_cong_cu_sua_chi_bao_cao_khi_khong_apply(tmp_path):
    novel_dir = _write_doubled_novel(tmp_path)
    before = (novel_dir / "chapters_index.json").read_text(encoding="utf-8")
    report = chapters_index_repair.repair_novel(str(novel_dir))
    assert report["status"] == "duplicated" and report["entries"] == 12 and report["duplicates"] == 6
    assert report["entries_after"] == 6 and report["spurious_chapter_files"] == 2
    assert (novel_dir / "chapters_index.json").read_text(encoding="utf-8") == before
    assert not [p for p in os.listdir(novel_dir) if p.startswith(("chapters_index.json.bak", "removed_"))]


def test_cong_cu_sua_apply_sao_luu_bo_trung_va_chuyen_ban_thua_di_noi_khac(tmp_path):
    novel_dir = _write_doubled_novel(tmp_path)
    report = chapters_index_repair.repair_novel(str(novel_dir), apply=True, now=datetime(2026, 10, 1, 9, 30, 0))
    assert report["status"] == "repaired"
    fixed = json.loads((novel_dir / "chapters_index.json").read_text(encoding="utf-8"))
    assert [c["chapter_number"] for c in fixed] == [1, 2, 3, 4, 5, 6]
    assert (novel_dir / "chapters_index.json.bak-20261001-093000").exists()
    removed = novel_dir / "removed_duplicate_chapters-20261001-093000"
    assert sorted(os.listdir(removed)) == ["chapter_7.json", "chapter_8.json"]
    assert sorted(os.listdir(novel_dir / "chapters")) == ["chapter_1.json", "chapter_2.json"]
    # Chạy lại là no-op
    assert chapters_index_repair.repair_novel(str(novel_dir), apply=True)["status"] == "ok"


def test_cong_cu_sua_khong_tu_danh_so_lai_khi_con_chuong_that_sau_khoi_nhan_doi(tmp_path):
    novel_dir = _write_doubled_novel(tmp_path, extra_real=2)
    before = (novel_dir / "chapters_index.json").read_text(encoding="utf-8")
    report = chapters_index_repair.repair_novel(str(novel_dir), apply=True)
    assert report["status"] == "needs_manual_renumber"
    assert (novel_dir / "chapters_index.json").read_text(encoding="utf-8") == before


def test_cong_cu_sua_dong_lenh_va_truyen_sach_khong_bi_dung_toi(tmp_path, capsys):
    _write_doubled_novel(tmp_path)
    clean = tmp_path / "novels" / "clean"
    clean.mkdir()
    (clean / "chapters_index.json").write_text(json.dumps([_stored(i) for i in range(1, 4)]), encoding="utf-8")
    code = chapters_index_repair.main(["--data-dir", str(tmp_path / "novels")])
    out = capsys.readouterr().out
    assert code == 0 and '"novel_id": "dup"' in out and '"status": "duplicated"' in out
    assert '"novel_id": "clean", "status": "ok"' in out and "--apply" in out
    assert sorted(os.listdir(clean)) == ["chapters_index.json"]
