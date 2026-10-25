"""Tích hợp đầu-cuối /v1/translate: route -> kho -> worker -> kiểm tra -> cache -> dự phòng Google.

Chỉ giả hai thứ ngoài hệ thống: lời gọi Antigravity CLI và Google Translate.
"""

import pytest

from services import chapter_fallback_translator as cft
from services import chapter_translation_service
from services.storage_service import storage_service
from services.translation_validation import ENGINE_AI, ENGINE_FALLBACK
from services.translation_worker import translation_worker
from tests._fakes import isolated  # noqa: F401 (fixture)
from tests._translate_support import AUTH, client

# Đủ dài (310 chữ Hán) để các phép đo độ dài/số đoạn có hiệu lực: nguồn dưới 200 chữ Hán thì bỏ qua.
PARAGRAPHS = 10
ORIGINAL_LONG = "\n".join(
    f"第{i}段。林动握紧长剑，目光冷冽地望向远处的山门，心中暗暗发誓一定要变强。" for i in range(PARAGRAPHS)
)
GOOD_VI = "\n".join(
    f"Đoạn {i}. Lâm Động siết chặt thanh trường kiếm, ánh mắt lạnh lẽo nhìn về sơn môn phía xa, "
    f"trong lòng thầm thề nhất định phải trở nên mạnh mẽ." for i in range(PARAGRAPHS)
)
BODY = {"novel_id": "n1", "chapter_number": 1}


@pytest.fixture
def stack(isolated, monkeypatch):
    """CLI giả trả lần lượt các kết quả xếp sẵn; Google giả dịch từng dòng."""
    state = {"ai": [], "ai_calls": 0, "google_calls": 0}

    async def fake_cli(prompt_content, glossary, custom_prompt, target_language, english_level, priority=None):
        state["ai_calls"] += 1
        outcome = state["ai"].pop(0) if state["ai"] else GOOD_VI
        if isinstance(outcome, Exception):
            raise outcome
        return outcome

    async def fake_google(client_, text, sl="auto", tl="vi"):
        state["google_calls"] += 1
        return "\n".join(f"[{tl}] {len(line)}" for line in text.split("\n"))

    monkeypatch.setattr(translation_worker, "is_agy_ready", lambda *a, **k: True)
    monkeypatch.setattr(translation_worker, "_translate_via_agy_cli", fake_cli)
    monkeypatch.setattr(cft, "google_translate_raw", fake_google)
    storage_service.save_chapter("n1", 1, {"title": "第1章", "original_content": ORIGINAL_LONG})
    return state


def _cache_rows(isolated):
    return [r for r in isolated.db.table("translation_cache").rows]


def test_ai_thanh_cong_luu_kho_va_cache_roi_phuc_vu_tu_kho(stack, isolated):
    data = client.post("/v1/translate", headers=AUTH, json=BODY).json()
    assert data["translated_content"] == GOOD_VI and data["cached"] is False
    assert data["engine"] == data["translation_engine"] == ENGINE_AI and data["is_fallback"] is False
    assert storage_service.get_chapter("n1", 1)["translation_engine"] == ENGINE_AI
    assert len(_cache_rows(isolated)) == 1 and _cache_rows(isolated)[0]["id"].startswith("trans2_")

    again = client.post("/v1/translate", headers=AUTH, json=BODY).json()
    assert again["cached"] is True and again["translated_content"] == GOOD_VI
    assert stack["ai_calls"] == 1


def test_force_chay_ai_lai_va_ghi_de_ca_cache_lan_kho(stack, isolated):
    client.post("/v1/translate", headers=AUTH, json=BODY)
    better = GOOD_VI.replace("Đoạn", "Mục")
    stack["ai"] = [better]
    data = client.post("/v1/translate", headers=AUTH, json={**BODY, "force": True}).json()
    assert data["translated_content"] == better and stack["ai_calls"] == 2
    assert storage_service.get_chapter("n1", 1)["translated_content"] == better
    assert _cache_rows(isolated)[0]["result"] == better, "force ghi đè translation_cache"
    # Dịch lại không force: lấy từ kho, không chạy AI
    assert client.post("/v1/translate", headers=AUTH, json=BODY).json()["translated_content"] == better
    assert stack["ai_calls"] == 2


def test_tu_choi_roi_ai_thu_lai_mot_lan_thanh_cong(stack, isolated):
    stack["ai"] = ["The prompt could not be submitted because it may violate Google policy"]
    data = client.post("/v1/translate", headers=AUTH, json=BODY).json()
    assert data["is_fallback"] is False and data["translated_content"] == GOOD_VI
    assert stack["ai_calls"] == 2 and stack["google_calls"] == 0


def test_ai_cut_hai_lan_thi_dung_ban_google_tam_khong_vao_cache(stack, isolated):
    cut = "\n".join(GOOD_VI.splitlines()[:2])
    stack["ai"] = [cut, cut]
    data = client.post("/v1/translate", headers=AUTH, json=BODY).json()
    assert data["is_fallback"] is True and data["translation_engine"] == ENGINE_FALLBACK
    assert data["translated_content"].startswith("[vi]") and stack["google_calls"] >= 1
    assert _cache_rows(isolated) == [], "Bản Google không bao giờ vào translation_cache"
    stored = storage_service.get_chapter("n1", 1)
    assert stored["translation_engine"] == ENGINE_FALLBACK and stored["original_content"]


def test_ban_tam_duoc_nang_cap_khi_ai_hoi_phuc(stack, isolated):
    stack["ai"] = [RuntimeError("cli chết"), RuntimeError("cli chết")]
    first = client.post("/v1/translate", headers=AUTH, json=BODY).json()
    assert first["is_fallback"] is True

    # Trong thời gian chờ: trả ngay bản tạm, không gọi lại AI
    calls = stack["ai_calls"]
    assert client.post("/v1/translate", headers=AUTH, json=BODY).json()["is_fallback"] is True
    assert stack["ai_calls"] == calls

    # Hết thời gian chờ và AI đã hồi phục: bản AI thay bản tạm
    chapter_translation_service._ai_retry_after.clear()
    upgraded = client.post("/v1/translate", headers=AUTH, json=BODY).json()
    assert upgraded["is_fallback"] is False and upgraded["translated_content"] == GOOD_VI
    assert storage_service.get_chapter("n1", 1)["translation_engine"] == ENGINE_AI
    assert len(_cache_rows(isolated)) == 1


def test_ban_tam_van_loi_thi_tra_lai_ban_tam_ngay(stack, isolated):
    stack["ai"] = [RuntimeError("x"), RuntimeError("x")]
    client.post("/v1/translate", headers=AUTH, json=BODY)
    chapter_translation_service._ai_retry_after.clear()
    stack["ai"] = [RuntimeError("vẫn chết")]
    before = stack["google_calls"]
    data = client.post("/v1/translate", headers=AUTH, json=BODY).json()
    assert data["is_fallback"] is True and data["cached"] is True
    assert stack["google_calls"] == before, "Retry AI thất bại không dịch Google lần nữa"
    assert stack["ai_calls"] == 3, "Retry bản tạm chỉ một lượt AI"


def test_song_ngu_ai_lo_ra_cap_en_vi_khi_dung_ban_google(stack):
    stack["ai"] = [RuntimeError("x"), RuntimeError("x")]
    data = client.post("/v1/translate", headers=AUTH,
                       json={**BODY, "target_language": "bilingual_b2"}).json()
    assert data["is_fallback"] is True
    assert data["translated_content"].count("[EN]") == data["translated_content"].count("[VI]") == PARAGRAPHS
    stored = storage_service.get_chapter("n1", 1)
    assert stored["translated_content_bilingual_b2_engine"] == ENGINE_FALLBACK
    assert not stored.get("translated_content"), "Không lẫn sang bản tiếng Việt"
