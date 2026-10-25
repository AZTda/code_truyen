"""persist_shared là kiểm-rồi-ghi: bản dự phòng Google đến chậm không được đè bản AI tốt đã lưu.

Độc giả (làn đọc, cho phép dự phòng) và việc nền (tải trước, không dự phòng) không gộp chung một lượt chạy
nên hai lượt AI cho cùng một chương có thể chạy song song. Việc nền xong trước và lưu bản AI; lượt của độc
giả hết giờ muộn hơn, rơi xuống Google rồi ghi đè bản AI và còn đặt thời gian chờ 10 phút phục vụ bản Google.
"""

import asyncio

import httpx
import pytest

from server import app
from services.chapter_translation_service import VI_FIELD, fallback_cooldown_active, persist_shared, SourceText
from services.storage_service import storage_service
from services.translation_validation import ChapterTranslation, ENGINE_AI, ENGINE_FALLBACK
from services.translation_worker import translation_worker
from tests._fakes import isolated  # noqa: F401 (fixture)
from tests._translate_support import AUTH, ORIGINAL

GOOD_AI = "Bản dịch AI tốt do việc nền vừa lưu."
GOOGLE = "Bản Google dự phòng đến chậm."


@pytest.mark.asyncio
async def test_doc_gia_roi_xuong_google_sau_khi_viec_nen_luu_ban_ai_thi_khong_de_len(isolated, monkeypatch):
    storage_service.save_chapter("n1", 1, {"title": "第1章", "original_content": ORIGINAL})

    async def fake(**kw):
        if kw["allow_fallback"]:                    # độc giả: AI hết giờ chậm rồi rơi xuống Google
            await asyncio.sleep(0.4)
            return ChapterTranslation(GOOGLE, ENGINE_FALLBACK, is_fallback=True)
        await asyncio.sleep(0.1)                    # tải trước ở làn nền: AI xong nhanh
        return ChapterTranslation(GOOD_AI, ENGINE_AI)
    monkeypatch.setattr(translation_worker, "translate_chapter_detailed", fake)

    body = {"novel_id": "n1", "chapter_number": 1}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://testserver") as c:
        reader = asyncio.create_task(c.post("/v1/translate", headers=AUTH, json=body))
        await asyncio.sleep(0.05)
        prefetch = await c.post("/v1/translate", headers=AUTH, json={**body, "prefetch": True})
        assert prefetch.status_code == 200
        assert storage_service.get_chapter("n1", 1)["translated_content"] == GOOD_AI
        res = await reader
        again = await c.post("/v1/translate", headers=AUTH, json=body)

    data = res.json()
    assert data["translated_content"] == GOOD_AI and data["is_fallback"] is False
    assert data["translation_engine"] == ENGINE_AI
    stored = storage_service.get_chapter("n1", 1)
    assert stored["translated_content"] == GOOD_AI and stored["translation_engine"] == ENGINE_AI
    assert not fallback_cooldown_active("n1", 1, VI_FIELD), "Không có bản tạm nào trong kho nên không có thời gian chờ"
    assert again.json()["translated_content"] == GOOD_AI and again.json()["cached"] is True


def _source():
    return SourceText(ORIGINAL, "第1章", "Chương 1")


def test_persist_shared_ban_du_phong_khong_de_len_ban_ai(isolated):
    storage_service.save_chapter("n1", 1, {VI_FIELD: GOOD_AI, "translation_engine": ENGINE_AI})
    out = persist_shared("n1", 1, "vi", "c1", ChapterTranslation(GOOGLE, ENGINE_FALLBACK, is_fallback=True), _source())
    assert out.text == GOOD_AI and out.engine == ENGINE_AI and out.is_fallback is False and out.from_cache is True
    stored = storage_service.get_chapter("n1", 1)
    assert stored[VI_FIELD] == GOOD_AI and stored["translation_engine"] == ENGINE_AI
    assert not isolated.scheduled


def test_persist_shared_ban_cu_khong_ghi_engine_van_tinh_la_ban_ai(isolated):
    storage_service.save_chapter("n1", 1, {VI_FIELD: GOOD_AI})
    out = persist_shared("n1", 1, "vi", "c1", ChapterTranslation(GOOGLE, ENGINE_FALLBACK, is_fallback=True), _source())
    assert out.text == GOOD_AI
    assert storage_service.get_chapter("n1", 1)[VI_FIELD] == GOOD_AI


@pytest.mark.parametrize("stored", [
    {},                                                                   # chưa có gì
    {VI_FIELD: "Bản Google cũ.", "translation_engine": ENGINE_FALLBACK},    # bản tạm
    {VI_FIELD: "I'm sorry, but I can't help with that.", "translation_engine": ENGINE_AI},  # bản hỏng
])
def test_persist_shared_van_ghi_ban_du_phong_khi_chua_co_ban_ai_dung_duoc(isolated, stored):
    if stored:
        storage_service.save_chapter("n1", 1, stored)
    fallback = ChapterTranslation(GOOGLE, ENGINE_FALLBACK, is_fallback=True)
    out = persist_shared("n1", 1, "vi", "c1", fallback, _source())
    assert out is fallback
    saved = storage_service.get_chapter("n1", 1)
    assert saved[VI_FIELD] == GOOGLE and saved["translation_engine"] == ENGINE_FALLBACK


def test_persist_shared_ban_ai_luon_duoc_ghi_ke_ca_khi_da_co_ban_ai_khac(isolated):
    storage_service.save_chapter("n1", 1, {VI_FIELD: "Bản AI cũ.", "translation_engine": ENGINE_AI})
    fresh = ChapterTranslation(GOOD_AI, ENGINE_AI)
    assert persist_shared("n1", 1, "vi", "c1", fresh, _source()) is fresh
    assert storage_service.get_chapter("n1", 1)[VI_FIELD] == GOOD_AI
    assert isolated.scheduled, "Bản AI tiếng Việt mới vẫn lên lịch trích tên riêng"


def test_persist_shared_kiem_tra_dung_truong_theo_ngon_ngu_dich(isolated):
    """Bản AI tiếng Việt đã có không chặn bản dự phòng TIẾNG ANH (hai trường lưu khác nhau)."""
    storage_service.save_chapter("n1", 1, {VI_FIELD: GOOD_AI, "translation_engine": ENGINE_AI})
    fallback_en = ChapterTranslation("English fallback.", ENGINE_FALLBACK, is_fallback=True)
    assert persist_shared("n1", 1, "en", "b2", fallback_en, _source()) is fallback_en
    saved = storage_service.get_chapter("n1", 1)
    assert saved["translated_content_en_b2"] == "English fallback."
    assert saved[VI_FIELD] == GOOD_AI

    good_en = ChapterTranslation("Good English.", ENGINE_AI)
    persist_shared("n1", 1, "en", "b2", good_en, _source())
    out = persist_shared("n1", 1, "en", "b2", fallback_en, _source())
    assert out.text == "Good English."
