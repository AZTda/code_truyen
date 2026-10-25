"""Dùng chung cho các test của /v1/translate: client, token, chương mẫu và worker giả."""

from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from server import app
from services.auth_token import issue_token
from services.storage_service import storage_service
from services.translation_validation import ChapterTranslation, ENGINE_AI
from services.translation_worker import translation_worker

client = TestClient(app)
AUTH = {"Authorization": f"Bearer {issue_token('user_translate', 'translate')}"}
ORIGINAL = "\n".join("林动握紧长剑，目光冷冽地望向远处的山门。" for _ in range(8))


@pytest.fixture
def fake_worker(monkeypatch):
    """Giả translate_chapter_detailed: ghi lại tham số gọi, trả kết quả xếp sẵn (None = AI lỗi)."""
    state = SimpleNamespace(calls=[], results=[])

    async def fake(**kwargs):
        state.calls.append(kwargs)
        nxt = state.results.pop(0) if state.results else ChapterTranslation("Bản dịch AI mới.", ENGINE_AI)
        return nxt
    monkeypatch.setattr(translation_worker, "translate_chapter_detailed", fake)
    return state


def seed_chapter(novel_id="n1", **extra):
    storage_service.save_chapter(novel_id, 1, {"title": "第1章", "original_content": ORIGINAL, **extra})
