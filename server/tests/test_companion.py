"""Unit tests for AI Companion Chat."""

import pytest
from unittest.mock import patch
from fastapi.testclient import TestClient

from server import app
from services.companion_chat_service import companion_chat_service
from services.auth_token import issue_token
from tests._fakes import isolated  # noqa: F401 (fixture: DB và kho đĩa tạm)

client = TestClient(app, headers={"Authorization": f"Bearer {issue_token('test_companion', 'test_companion')}"})

@pytest.mark.asyncio
async def test_companion_service_fallback(monkeypatch):
    """Verify companion chat service returns a FLAGGED fallback when CLI unavailable.

    Trước đây dự phòng trả câu "tình thế phức tạp..." kèm 3 câu gợi ý cố định như
    thể là câu trả lời thật; giờ phải gắn is_fallback và không bịa gợi ý.
    """
    monkeypatch.setattr(companion_chat_service, "_get_agy_path", lambda: None)
    res = await companion_chat_service.chat_with_companion(
        novel_title="Vạn Cổ Đệ Nhất Thần",
        chapter_title="Chương 1",
        chapter_number=1,
        user_message="Nhân vật chính tên gì?",
        character_name="Lý Thiên Mệnh"
    )
    assert "reply" in res
    assert res["character_name"] == "Lý Thiên Mệnh"
    assert res["is_fallback"] is True
    assert res["suggested_questions"] == []

def test_companion_chat_endpoint():
    """Test /v1/companion/chat endpoint."""
    with patch.object(
        companion_chat_service,
        "chat_with_companion",
        return_value={
            "reply": "Ta chính là Lý Thiên Mệnh, nắm giữ Thập Giới Luân Hồi!",
            "character_name": "Lý Thiên Mệnh",
            "suggested_questions": ["Công pháp của ngươi là gì?", "Thú cưng đầu tiên là ai?"],
            "is_fallback": False,
        }
    ):
        res = client.post(
            "/v1/companion/chat",
            json={
                "novel_title": "Vạn Cổ Đệ Nhất Thần",
                "chapter_title": "Chương 1",
                "chapter_number": 1,
                "user_message": "Ngươi là ai?",
                "character_name": "Lý Thiên Mệnh"
            }
        )
    assert res.status_code == 200
    data = res.json()
    assert "reply" in data
    assert data["character_name"] == "Lý Thiên Mệnh"
    assert len(data["suggested_questions"]) == 2


def test_companion_chat_stream_endpoint(monkeypatch):
    """Test /v1/companion/chat/stream SSE endpoint."""
    monkeypatch.setattr(companion_chat_service, "_get_agy_path", lambda: None)
    res = client.post(
        "/v1/companion/chat/stream",
        json={
            "novel_title": "Vạn Cổ Đệ Nhất Thần",
            "chapter_title": "Chương 1",
            "chapter_number": 1,
            "user_message": "Chào bạn",
            "character_name": "Lý Thiên Mệnh"
        }
    )
    assert res.status_code == 200
    assert "text/event-stream" in res.headers.get("content-type", "")
    content = res.text
    assert "data:" in content
    assert '"type": "start"' in content or '"type": "chunk"' in content
    assert '"type": "done"' in content


@pytest.mark.asyncio
async def test_translation_worker_cache_hit(isolated):
    """Verify that translation_worker checks and uses translation_cache (DB tạm, không chạm truyencloud.db thật)."""
    from database import db
    from services.translation_worker import translation_worker

    sample_content = "Đây là nội dung thử nghiệm bộ nhớ đệm dịch thuật 2026."
    cache_key = translation_worker._compute_translation_cache_key(
        content=sample_content,
        target_language="vi",
        english_level="c1",
        custom_prompt=None,
        glossary=None
    )

    # Ghi sẵn một bản dịch giả vào cache
    db.table("translation_cache").upsert({
        "id": cache_key,
        "result": "Bản dịch đã được lưu sẵn trong bộ nhớ đệm!",
        "target_language": "vi"
    }).execute()

    # Gọi translate_chapter — phải trả về kết quả từ cache ngay mà không cần AGY
    result = await translation_worker.translate_chapter(content=sample_content)
    assert result == "Bản dịch đã được lưu sẵn trong bộ nhớ đệm!"
