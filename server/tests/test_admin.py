import json

import pytest
from starlette.testclient import TestClient

import routes.auth as auth_routes
from server import app
from services import rate_limit
from services.password import hash_password

client = TestClient(app)
ADMIN_KEY = "k" * 16 + "test-admin-key-for-pytest-only-0123456789"


@pytest.fixture(autouse=True)
def _isolated_admin_env(tmp_path, monkeypatch):
    """Khoá quản trị đủ dài + users.json tạm: không bao giờ ghi vào data/users.json thật."""
    users_file = tmp_path / "users.json"
    users_file.write_text(json.dumps({
        "seed": {"id": "user_seed", "username": "seed", "password": hash_password("SeedPass123")},
    }), encoding="utf-8")
    monkeypatch.setattr(auth_routes, "DATA_DIR", tmp_path)
    monkeypatch.setattr(auth_routes, "USERS_FILE", users_file)
    monkeypatch.setenv("ADMIN_KEY", ADMIN_KEY)
    monkeypatch.delenv("ADMIN_SECRET", raising=False)
    rate_limit.reset_all()
    yield
    rate_limit.reset_all()


def test_admin_page_renders_html():
    response = client.get("/admin")
    assert response.status_code == 200
    assert "text/html" in response.headers["content-type"]
    assert "TruyenCloud Admin" in response.text


def test_admin_requires_authentication():
    # Without header
    res = client.get("/v1/admin/users")
    assert res.status_code == 401

    # With invalid header
    res = client.get("/v1/admin/users", headers={"X-Admin-Key": "wrong_key_123"})
    assert res.status_code == 401


def test_admin_list_users_success():
    res = client.get("/v1/admin/users", headers={"X-Admin-Key": ADMIN_KEY})
    assert res.status_code == 200
    users = res.json()
    assert isinstance(users, list)
    assert len(users) >= 1
    # Check that password hash is not exposed
    for u in users:
        assert "password" not in u
        assert "username" in u
        assert "display_name" in u


def test_admin_crud_user_lifecycle():
    test_uname = "pytest_temp_user"
    test_pass = "TestPass12345"

    headers = {"X-Admin-Key": ADMIN_KEY}

    # 1. Create User
    create_res = client.post(
        "/v1/admin/users",
        headers=headers,
        json={
            "username": test_uname,
            "password": test_pass,
            "display_name": "Pytest Temp User",
            "email": "pytest_temp@truyencloud.vn",
            "vip_tier": "✦ VIP Tinh Anh 2026",
            "bio": "User created for automated testing"
        }
    )
    assert create_res.status_code == 201
    created = create_res.json()
    assert created["username"] == test_uname
    assert created["display_name"] == "Pytest Temp User"
    assert created["email"] == "pytest_temp@truyencloud.vn"

    # 2. Verify duplicate creation fails
    dup_res = client.post(
        "/v1/admin/users",
        headers=headers,
        json={
            "username": test_uname,
            "password": "another_password",
        }
    )
    assert dup_res.status_code == 409

    # 3. Test logging in with newly created user
    login_res = client.post(
        "/v1/auth/login",
        json={
            "email": test_uname,
            "password": test_pass
        }
    )
    assert login_res.status_code == 200
    assert login_res.json()["status"] == "success"

    # 4. Change password
    new_pass = "UpdatedSecretPass999"
    update_res = client.put(
        f"/v1/admin/users/{test_uname}/password",
        headers=headers,
        json={"new_password": new_pass}
    )
    assert update_res.status_code == 200

    # 5. Old password should now fail
    old_login = client.post(
        "/v1/auth/login",
        json={"email": test_uname, "password": test_pass}
    )
    assert old_login.status_code == 401

    # 6. New password should succeed
    new_login = client.post(
        "/v1/auth/login",
        json={"email": test_uname, "password": new_pass}
    )
    assert new_login.status_code == 200

    # 7. Delete User
    del_res = client.delete(f"/v1/admin/users/{test_uname}", headers=headers)
    assert del_res.status_code == 200

    # 8. User should no longer exist
    login_after_del = client.post(
        "/v1/auth/login",
        json={"email": test_uname, "password": new_pass}
    )
    assert login_after_del.status_code == 401


def test_admin_novels_list_and_auth():
    assert client.get("/v1/admin/novels").status_code == 401
    res = client.get("/v1/admin/novels", headers={"X-Admin-Key": ADMIN_KEY})
    assert res.status_code == 200
    body = res.json()
    assert "total" in body and isinstance(body.get("items"), list)


def test_admin_novel_tools_glossary_epub_and_cancel(tmp_path, monkeypatch):
    import zipfile
    import io
    from services.storage_service import storage_service
    from services.glossary_store import glossary_store
    from services.novel_chapter_stats import get_chapter_counts, invalidate_chapter_counts

    monkeypatch.setattr(storage_service, "base_dir", str(tmp_path / "novels"))
    monkeypatch.setattr(glossary_store, "base_dir", str(tmp_path / "glossaries"))
    (tmp_path / "glossaries").mkdir(parents=True, exist_ok=True)

    nid = "test_novel_epub_1"
    storage_service.save_novel_info(nid, {
        "id": nid,
        "title": "霍格沃茨的路人教授",
        "title_vi": "Giáo Sư Qua Đường Của Hogwarts",
        "author": "Tác Giả Test",
        "description": "Một bộ truyện thử nghiệm.",
        "total_chapters": 2,
    })
    storage_service.save_chapter(nid, 1, {
        "chapter_number": 1,
        "title": "第一章",
        "title_vi": "Chương 1: Khởi đầu",
        "original_content": "霍格沃茨的第一天。" * 30,
        "translated_content": "Ngày đầu tiên tại học viện phép thuật Hogwarts.\nĐoạn văn thứ hai rất dài để đủ chuẩn chương dịch." * 10,
        "translation_engine": "agy",
    })
    invalidate_chapter_counts(nid)
    counts = get_chapter_counts(nid)
    assert counts["cached_chapters"] == 1 and counts["translated_chapters"] == 1

    headers = {"X-Admin-Key": ADMIN_KEY}

    # 1. Glossary PUT & GET
    put_res = client.put(
        f"/v1/admin/novels/{nid}/glossary",
        headers=headers,
        json={"terms": [{"source": "霍格沃茨", "target": "Hogwarts"}, {"source": "麻省理工", "target": "MIT"}]},
    )
    assert put_res.status_code == 200
    assert put_res.json()["total"] == 2

    get_res = client.get(f"/v1/admin/novels/{nid}/glossary", headers=headers)
    assert get_res.status_code == 200
    assert len(get_res.json()["terms"]) == 2

    # 2. EPUB export
    epub_res = client.get(f"/v1/admin/novels/{nid}/export.epub", headers=headers)
    assert epub_res.status_code == 200
    assert epub_res.headers["content-type"] == "application/epub+zip"
    with zipfile.ZipFile(io.BytesIO(epub_res.content), "r") as zf:
        names = zf.namelist()
        assert "mimetype" in names
        assert "OEBPS/content.opf" in names
        assert "OEBPS/chapter_1.xhtml" in names

    # 3. Cancel job endpoint (idle when no job is running)
    cancel_res = client.post(f"/v1/admin/novels/{nid}/cancel-job", headers=headers)
    assert cancel_res.status_code == 200
    assert cancel_res.json()["status"] == "idle"

    # 4. Harvester status & stop endpoints
    harv_res = client.get("/v1/admin/novels/harvester/status", headers=headers)
    assert harv_res.status_code == 200
    harv_data = harv_res.json()
    assert "packs" in harv_data and len(harv_data["packs"]) >= 5
    assert harv_data["running"] is False

    stop_res = client.post("/v1/admin/novels/harvester/stop", headers=headers)
    assert stop_res.status_code == 200
    assert stop_res.json()["status"] in ("idle", "stopping")
