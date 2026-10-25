import json

import pytest
from fastapi.testclient import TestClient

import routes.app_update as app_update
from config import settings
from server import app
from services.auth_token import issue_token

client = TestClient(app)
ADMIN_KEY = "app-update-admin-key-for-pytest-0123456789abcdef"


@pytest.fixture(autouse=True)
def _isolated_manifest(tmp_path, monkeypatch):
    """Manifest tạm: test không được ghi vào data/app_version.json thật."""
    version_file = tmp_path / "app_version.json"
    version_file.write_text(json.dumps({
        "version_code": 1,
        "version_name": "1.0.0",
        "download_url": "/static/truyencloud-latest.apk",
        "changelog": "- Bản đầu",
    }), encoding="utf-8")
    monkeypatch.setattr(app_update, "DATA_DIR", str(tmp_path))
    monkeypatch.setattr(app_update, "VERSION_FILE", str(version_file))
    monkeypatch.setattr(settings, "BASE_URL", "https://truyen.achatvn.com")
    monkeypatch.setenv("ADMIN_KEY", ADMIN_KEY)


def test_check_update_default():
    response = client.get("/v1/app/check-update")
    assert response.status_code == 200
    data = response.json()
    assert "version_code" in data
    assert "version_name" in data
    assert "download_url" in data
    assert "changelog" in data
    assert data["sha256"] == ""  # manifest cũ chưa có sha256
    assert data["platform"] == "android"


def test_download_latest_endpoint():
    response = client.get("/v1/app/download-latest", follow_redirects=False)
    # If static apk exists it returns 200 (FileResponse), otherwise 307 (Redirect)
    assert response.status_code in (200, 307)

def test_serve_landing_page():
    response = client.get("/")
    assert response.status_code == 200
    page = response.text
    assert "TruyenCloud" in page and "Tải ứng dụng cho Android" in page
    # Phiên bản lấy từ máy chủ lúc mở trang; tải dự phòng khi không lấy được thông tin phiên bản
    assert "/v1/app/check-update" in page and "/v1/app/download-latest" in page
    # Trang công khai không quảng bá đường vào trang quản trị
    assert 'href="/admin' not in page

def test_serve_download_page():
    response = client.get("/download")
    assert response.status_code == 200
    assert "TruyenCloud" in response.text

def test_update_version_unauthorized():
    payload = {
        "version_code": 2,
        "version_name": "1.0.1",
        "changelog": "Fix bugs"
    }
    response = client.post("/v1/app/version", json=payload)
    assert response.status_code in (401, 403)


def test_update_version_nguoi_dung_thuong_bi_chan():
    """Lỗ hổng gốc: bất kỳ ai đăng nhập cũng phát hành được bản cập nhật bắt buộc giả."""
    token = issue_token(user_id="user_nobody", username="nobody")
    response = client.post(
        "/v1/app/version",
        json={"version_code": 999, "version_name": "x", "changelog": "fake", "is_mandatory": True},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 403
    assert client.get("/v1/app/check-update").json()["version_code"] == 1


def test_update_version_authorized():
    sha = "a" * 64
    payload = {
        "version_code": 99,
        "version_name": "9.9.9",
        "changelog": "Test update OTA",
        "download_url": "https://truyen.achatvn.com/static/truyencloud-latest.apk",
        "sha256": sha.upper(),
    }
    response = client.post("/v1/app/version", json=payload, headers={"X-Admin-Key": ADMIN_KEY})
    assert response.status_code == 200
    res_data = response.json()
    assert res_data["version"]["version_code"] == 99
    assert res_data["version"]["version_name"] == "9.9.9"

    # Verify check-update returns updated version
    check_resp = client.get("/v1/app/check-update")
    assert check_resp.status_code == 200
    assert check_resp.json()["version_code"] == 99
    assert check_resp.json()["sha256"] == sha


@pytest.mark.parametrize("bad_url", [
    "https://evil.example.com/static/truyencloud-latest.apk",
    "//evil.example.com/static/x.apk",
    "https://truyen.achatvn.com@evil.example.com/x.apk",
    "http://truyen.achatvn.com/static/x.apk",  # hạ cấp https -> http
    "/static/../data/users.json",
    "/v1/other.apk",
    "javascript:alert(1)",
])
def test_update_version_tu_choi_download_url_ngoai(bad_url):
    response = client.post(
        "/v1/app/version",
        json={"version_code": 5, "version_name": "5", "changelog": "x", "download_url": bad_url},
        headers={"X-Admin-Key": ADMIN_KEY},
    )
    assert response.status_code == 400
    assert client.get("/v1/app/check-update").json()["version_code"] == 1


def test_update_version_tu_choi_sha256_sai_dinh_dang():
    response = client.post(
        "/v1/app/version",
        json={"version_code": 5, "version_name": "5", "changelog": "x", "sha256": "xyz"},
        headers={"X-Admin-Key": ADMIN_KEY},
    )
    assert response.status_code == 400


def test_check_update_ios_khong_bao_gio_co_ban_cap_nhat():
    data = client.get("/v1/app/check-update", params={"platform": "ios"}).json()
    assert data["version_code"] == 0
    assert data["download_url"] == ""
    assert data["is_mandatory"] is False
    assert data["sha256"] == ""
    assert data["platform"] == "ios"
    assert client.get("/v1/app/check-update", params={"platform": "symbian"}).status_code == 400
