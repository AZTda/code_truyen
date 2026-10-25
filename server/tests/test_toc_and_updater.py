"""Tải trước vượt cuối truyện không cào lại mục lục; auto-updater dịch trước chương mới ĐẦU TIÊN."""

import asyncio
import os
import time
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from server import app
from models.translate import TermItem
from services.agy_runner import TaskPriority
from services.auto_updater import AutoUpdater
from services.glossary_store import glossary_store
from services.storage_service import storage_service
from services.toc_lookup import TocLookup, TocUnavailableError, toc_lookup
from services.translation_validation import ChapterTranslation
from services.translation_worker import translation_worker
from tests._fakes import isolated  # noqa: F401 (fixture)

client = TestClient(app)


def _ch(n, prefix="c"):
    return SimpleNamespace(chapter_number=n, id=f"{prefix}{n}", title=f"第{n}章", url=f"https://src.example/{prefix}{n}")


class FakeScraper:
    def __init__(self, chapters):
        self.chapters = chapters
        self.toc_calls = 0
        self.content_calls = []

    async def fetch_novel_info(self, url):
        self.toc_calls += 1
        await asyncio.sleep(0)
        return SimpleNamespace(chapters=self.chapters)

    async def fetch_chapter_content(self, url):
        self.content_calls.append(url)
        return "林动握紧长剑。" * 40


# ---------------------------------------------------------------------------
# TocLookup
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_nhieu_luot_dong_thoi_chi_cao_mot_lan():
    lookup, scraper = TocLookup(), FakeScraper([_ch(i) for i in range(1, 4)])

    async def fetch(url):
        return (await scraper.fetch_novel_info(url)).chapters
    results = await asyncio.gather(*(lookup.find("n", "https://src.example", n, fetch) for n in range(4, 9)))
    assert results == [None] * 5
    assert scraper.toc_calls == 1


@pytest.mark.asyncio
async def test_muc_luc_con_moi_thi_khong_cao_lai():
    lookup, calls = TocLookup(), []

    async def fetch(url):
        calls.append(url)
        return [_ch(1), _ch(2)]
    assert await lookup.find("n", "u", 2, fetch) == ("https://src.example/c2", "第2章")
    assert await lookup.find("n", "u", 3, fetch) is None
    assert len(calls) == 1


@pytest.mark.asyncio
async def test_cao_loi_thi_tam_ngung_thu_lai():
    lookup = TocLookup()

    async def broken(url):
        raise RuntimeError("nguồn sập")
    with pytest.raises(RuntimeError):
        await lookup.find("n", "u", 1, broken)
    with pytest.raises(TocUnavailableError):
        await lookup.find("n", "u", 1, broken)


# ---------------------------------------------------------------------------
# Route GET chương vượt cuối truyện
# ---------------------------------------------------------------------------

@pytest.fixture
def novel_with_index(isolated, monkeypatch):
    scraper = FakeScraper([_ch(i) for i in range(1, 4)])
    monkeypatch.setattr("routes.chapters.get_scraper_for_url", lambda url: scraper)
    monkeypatch.setattr(toc_lookup, "_entries", type(toc_lookup._entries)(500))
    monkeypatch.setattr(toc_lookup, "_failed_at", type(toc_lookup._failed_at)(500))
    storage_service.save_novel_info("nt", {"source_url": "https://src.example/book", "total_chapters": 3})
    storage_service.save_chapters_index("nt", [
        {"chapter_number": i, "id": f"c{i}", "title": f"第{i}章", "url": f"https://src.example/c{i}"}
        for i in range(1, 4)
    ])
    return scraper


def test_vuot_cuoi_truyen_khi_muc_luc_vua_lam_moi_tra_404_ngay(novel_with_index):
    for n in range(4, 9):
        assert client.get(f"/v1/novels/nt/chapters/{n}").status_code == 404
    assert novel_with_index.toc_calls == 0


def test_muc_luc_cu_chi_cao_mot_lan_cho_ca_dot_tai_truoc(novel_with_index):
    old = time.time() - 7200
    os.utime(storage_service.get_chapters_index_path("nt"), (old, old))
    for n in range(4, 9):
        assert client.get(f"/v1/novels/nt/chapters/{n}").status_code == 404
    assert novel_with_index.toc_calls == 1


# ---------------------------------------------------------------------------
# Auto-updater
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_dich_truoc_hai_chuong_moi_dau_tien(isolated, monkeypatch):
    monkeypatch.setattr(toc_lookup, "_entries", type(toc_lookup._entries)(500))
    monkeypatch.setattr(toc_lookup, "_failed_at", type(toc_lookup._failed_at)(500))
    remote = [_ch(i) for i in range(1, 7)]
    scraper = FakeScraper(remote)
    monkeypatch.setattr("services.auto_updater.get_scraper_for_url", lambda url: scraper)
    storage_service.save_novel_info("nu", {"source_url": "https://src.example/book"})
    storage_service.save_chapters_index("nu", [
        {"chapter_number": i, "id": f"c{i}", "title": f"第{i}章", "url": f"https://src.example/c{i}"}
        for i in range(1, 4)
    ])
    glossary_store.save("nu", [TermItem(source="林动", target="Lâm Động")])
    calls = []

    async def fake_titles(titles, novel_id=None):
        return [f"Chương {t}" for t in titles]

    async def fake_translate(**kwargs):
        calls.append(kwargs)
        return ChapterTranslation("Lâm Động siết chặt trường kiếm.")
    monkeypatch.setattr(translation_worker, "translate_chapter_titles_batch", fake_titles)
    monkeypatch.setattr(translation_worker, "translate_chapter_detailed", fake_translate)

    result = await AutoUpdater().check_novel_for_updates("nu")
    assert result["status"] == "updated" and result["pretranslated"] is True
    assert scraper.content_calls == ["https://src.example/c4", "https://src.example/c5"]
    assert all(c["priority"] == TaskPriority.BACKGROUND for c in calls)
    assert all(c["allow_fallback"] is False for c in calls)
    assert [t.source for t in calls[0]["glossary"]] == ["林动"]
    assert storage_service.get_chapter("nu", 4)["translated_content"]
    assert storage_service.get_chapter("nu", 5)["translated_content"]
    assert storage_service.get_chapter("nu", 6) is None
    assert toc_lookup.refreshed_recently("nu"), "Mục lục vừa quét được route đọc chương dùng lại"
