"""Chạm MỌI kho dữ liệu của server qua API/dịch vụ thật. Chỉ chạy bằng subprocess từ tests/test_state_isolation.py.

Tên bắt đầu bằng dấu gạch dưới nên `pytest tests/` không tự thu thập; nếu cách ly hở thì dữ liệu THẬT của server
sẽ đổi và test bảo vệ báo lỗi.
"""

from fastapi.testclient import TestClient

from database import db
from server import app
from services.auth_token import issue_token
from services.glossary_store import glossary_store
from services.storage_service import storage_service

ADMIN_KEY = "state-probe-admin-key-only-for-pytest-0123456789"


def test_touch_every_persistent_store(monkeypatch):
    monkeypatch.setenv("ADMIN_KEY", ADMIN_KEY)
    admin = {"X-Admin-Key": ADMIN_KEY}
    client = TestClient(app, raise_server_exceptions=False)
    user = {"Authorization": f"Bearer {issue_token('user_state_probe', 'probe')}"}

    # truyencloud.db: tiến độ, bookmark, từ vựng, bảng truyện/chương
    assert client.post("/v1/progress/sync", headers=user, json={"novel_id": "probe", "current_chapter": 3}).status_code == 200
    assert client.post("/v1/bookmarks/sync", headers=user, json={"bookmarks": [
        {"id": "b", "novel_id": "probe", "chapter_number": 1, "paragraph_index": 0, "created_at": 1}]}).status_code == 200
    assert client.post("/v1/vocabulary/sync", headers=user, json={
        "words": [{"id": "w", "word": "w"}], "review_logs": [{"wordId": "w", "reviewedAt": 9}]}).status_code == 200
    db.table("novels").upsert({"id": "probe-novel", "title": "Probe", "source_id": "probe"}).execute()
    db.table("chapters").upsert({"novel_id": "probe-novel", "chapter_number": 1, "original_content": "x"}).execute()

    # data/novels + data/glossaries
    storage_service.save_novel_info("probe-novel", {"title": "Probe", "source_id": "probe"})
    storage_service.save_chapter("probe-novel", 1, {"chapter_number": 1, "original_content": "x"})
    glossary_store.save("probe-novel", [("林", "Lâm")])

    # data/models_cache.json, translation_profile.json, app_version.json, users.json
    assert client.get("/v1/settings").status_code == 200
    assert client.get("/v1/settings/models").status_code == 200
    assert client.post("/v1/settings/profile", headers=admin, json={"genre": "probe"}).status_code == 200
    assert client.post("/v1/app/version", headers=admin, json={
        "version_code": 2, "version_name": "2", "changelog": "probe"}).status_code == 200
    assert client.get("/v1/app/check-update").status_code == 200
    assert client.post("/v1/admin/users", headers=admin, json={
        "username": "state_probe_user", "password": "ProbePass123"}).status_code == 201
