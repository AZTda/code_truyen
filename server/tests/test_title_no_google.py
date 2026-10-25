"""Không còn request Google nào cho tựa sách, tựa chương, tác giả hay từ khoá tìm kiếm (có hoặc không có từ điển).

Google chỉ còn ở services/chapter_fallback_translator.py (dịch dự phòng nguyên chương / tóm tắt dài).
"""

import os
import re

import httpx
import pytest
from fastapi.testclient import TestClient

from models.novel import ChapterSummary, Novel, NovelDetail
from services import chapter_fallback_translator as cft
from services.fast_translator import fast_translator
from services.query_translator import query_translator
from services.search_service import SearchService
from services.storage_service import storage_service
from services.translation_worker import translation_worker

SERVER_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_GOOGLE_RE = re.compile(r"translate\.googleapis|translate_a/single|[\"']gtx[\"']|GT_URL")


def test_chi_con_module_du_phong_chuong_nhac_toi_google():
    hits = []
    for folder in ("services", "routes"):
        for name in sorted(os.listdir(os.path.join(SERVER_DIR, folder))):
            if name.endswith(".py"):
                with open(os.path.join(SERVER_DIR, folder, name), encoding="utf-8") as f:
                    if _GOOGLE_RE.search(f.read()):
                        hits.append(f"{folder}/{name}")
    assert hits == ["services/chapter_fallback_translator.py"]


@pytest.fixture
def google_spy(monkeypatch):
    """Ghi lại MỌI request httpx và mọi lượt dịch dự phòng Google; request nào cũng bị từ chối."""
    calls = []

    async def spy_send(self, request, *a, **k):
        calls.append(str(request.url))
        raise httpx.ConnectError("bị chặn trong test", request=request)

    async def spy_google(client, text, sl="auto", tl="vi"):
        calls.append(f"google:{text[:20]}")
        return None

    monkeypatch.setattr(httpx.AsyncClient, "send", spy_send)
    monkeypatch.setattr(cft, "google_translate_raw", spy_google)
    return calls


def _no_google(calls):
    return [c for c in calls if "google" in c]


@pytest.mark.asyncio
@pytest.mark.parametrize("with_dict", [True, False])
async def test_cac_ham_dich_ngan_khong_goi_google(request, google_spy, with_dict):
    if with_dict:
        request.getfixturevalue("fixture_dict")
    assert await fast_translator.translate_text("我在大学当校长", sl="zh-CN", tl="vi")
    assert len(await fast_translator.translate_batch_chapter_titles(["第1章 国师的建议", "第2章 林动出关"])) == 2
    assert await fast_translator.translate_query_to_cjk("kiếm lai")
    assert await fast_translator.translate_query_to_cjk("chuyện tình boston", use_hanviet=False)
    assert await translation_worker.translate_novel_title("我在大学当校长")
    assert await translation_worker.translate_author_name("风青阳")
    assert len(await translation_worker.translate_chapter_titles_batch(["第3章 圣兽战魂"])) == 1
    for q in ("kiem lai", "chuyện tình boston", "tôi làm hiệu trưởng ở đại học"):
        assert await query_translator.translate_query(q)
        query_translator._neg_cache.pop(q.lower(), None)
    combined = []
    SearchService()._merge_novels(
        [Novel(id="w1", title="我在大学当校长", url="https://x/1", source_id="69shuba")], combined, set(), {})
    assert combined and combined[0].title_vi
    assert _no_google(google_spy) == []


def test_route_chi_tiet_truyen_khong_goi_google(fixture_dict, google_spy):
    from server import app
    storage_service.save_novel_info("ng1", {
        "id": "ng1", "title": "我在大学当校长", "title_vi": "", "author": "风青阳", "description": "Mô tả",
        "source_url": "https://www.69shuba.com/book/1.htm", "source_id": "69shuba", "cover_url": "/static/x.jpg"})
    storage_service.save_chapters_index("ng1", [
        {"chapter_number": i, "id": str(i), "title": f"第{i}章 国师的建议", "title_vi": "", "url": ""} for i in (1, 2)])
    res = TestClient(app).get("/v1/novels/ng1")
    assert res.status_code == 200
    body = res.json()
    assert body["title_vi"] == "Ngã Tại Đại Học Đương Hiệu Trưởng"
    assert body["chapters"][0]["title_vi"] == "Chương 1: Quốc Sư đích đề nghị"
    assert _no_google(google_spy) == []


@pytest.mark.asyncio
async def test_perform_scrape_khong_goi_google(fixture_dict, google_spy, monkeypatch):
    from routes import scrape as scrape_route

    class FakeScraper:
        async def fetch_novel_info(self, url):
            chapters = [ChapterSummary(chapter_number=i, id=str(i), title=f"第{i}章 林动出关", url=f"{url}#{i}")
                        for i in (1, 2, 3)]
            return NovelDetail(id="sc1", title="斗破苍穹", author="风青阳", source_url=url, source_id="69shuba",
                               cover_url="/static/c.jpg", description="", total_chapters=3, chapters=chapters)

    monkeypatch.setattr(scrape_route, "assert_safe_url", lambda url, allow: None)
    monkeypatch.setattr(scrape_route, "get_scraper_for_url", lambda url: FakeScraper())
    detail = await scrape_route.perform_scrape("https://www.69shuba.com/book/9.htm")
    assert detail.title_vi == "Đấu Phá Thương Khung" and detail.author == "Phong Thanh Dương"
    assert detail.chapters[0].title_vi == "Chương 1: Lâm Động xuất quan"
    assert _no_google(google_spy) == []


@pytest.mark.asyncio
async def test_tom_tat_dai_van_di_duong_du_phong(google_spy):
    """Văn bản dài (tóm tắt) không thuộc phạm vi: vẫn qua module dự phòng chương, không qua nhánh tựa đề."""
    out = await translation_worker.translate_novel_description("这是一段很长的简介。" * 20)
    assert out  # Google bị chặn -> rơi về phiên âm, không lỗi
    assert google_spy and all(c.startswith("google:") for c in google_spy)
