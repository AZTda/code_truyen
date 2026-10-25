import pytest
from fastapi.testclient import TestClient
from server import app
from database import db

client = TestClient(app)

def test_health_endpoint():
    response = client.get("/v1/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "healthy"
    assert "version" in data
    # Health công khai không được lộ chi tiết nền tảng
    assert "python_version" not in data and "platform" not in data


def test_deep_health_endpoint():
    # Deep health chỉ dành cho quản trị (luồng có quyền: tests/test_security_hardening.py)
    response = client.get("/v1/health/deep")
    assert response.status_code == 401

def test_search_novels_empty():
    response = client.get("/v1/novels/search?q=")
    # FastAPI requires q
    assert response.status_code in (200, 422)

def test_get_nonexistent_novel():
    response = client.get("/v1/novels/non-existent-id")
    assert response.status_code == 404

def test_chapter_cache_flow():
    # Insert mock novel
    novel_id = "test-novel-1"
    db.table("novels").upsert({
        "id": novel_id,
        "source_url": "https://test.com/novel/1",
        "source_id": "test",
        "title": "Test Novel",
        "total_chapters": 10
    }).execute()

    # Insert mock cached chapter
    db.table("chapters").upsert({
        "novel_id": novel_id,
        "chapter_number": 1,
        "title": "Chương 1: Khởi đầu",
        "original_content": "第一章 初始",
        "translated_content": "Chương 1: Khởi đầu một hành trình",
        "translation_engine": "ANTIGRAVITY"
    }).execute()

    # Request chapter
    response = client.get(f"/v1/novels/{novel_id}/chapters/1")
    assert response.status_code == 200
    data = response.json()
    assert data["novel_id"] == novel_id
    assert data["chapter_number"] == 1
    assert data["cached"] is True
    assert data["original_content"] == "第一章 初始"
    assert data["translated_content"] == "Chương 1: Khởi đầu một hành trình"

def test_settings_endpoints():
    response = client.get("/v1/settings")
    assert response.status_code == 200
    data = response.json()
    assert "has_api_key" in data
    assert "engine" in data

    # Endpoint GHI nay nay da duoc bao ve — khong token thi phai bi tu choi
    update_res = client.post("/v1/settings/api-key", json={"api_key": ""})
    assert update_res.status_code == 401, "Endpoint ghi phai yeu cau xac thuc"

    # Co token hop le thi di qua binh thuong
    from services.auth_token import issue_token
    token = issue_token("user_test1", "test1")
    ok_res = client.post(
        "/v1/settings/api-key",
        json={"api_key": ""},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert ok_res.status_code == 200
    res_data = ok_res.json()
    assert res_data["status"] == "success"
    assert res_data["engine"] == "ANTIGRAVITY_CLI"

