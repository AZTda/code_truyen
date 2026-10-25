"""POST /v1/app/version: download_url dị dạng phải bị từ chối 400, không bao giờ làm máy chủ trả 500."""

import pytest
from fastapi.testclient import TestClient

import routes.app_update as app_update
from config import settings
from server import app

ADMIN_KEY = "malformed-url-admin-key-for-pytest-0123456789"
client = TestClient(app, raise_server_exceptions=False)

MALFORMED = [
    "/static/x\x00.apk",                          # NUL: os.path.realpath ném ValueError
    "http://127.0.0.1:8000/static/x.apk\x00",
    "http://[::1/static/x.apk",                   # ngoặc IPv6 hở: urlsplit ném ValueError
    "http://127.0.0.1／@evil.com/",                # dấu gạch chéo toàn chiều rộng
    "http://ex℀ample.com/",                       # NFKC chuẩn hoá host thành ký tự cấm
    "/static/x\x0b.apk",                            # điều khiển nằm GIỮA chuỗi (đầu/cuối thì route đã strip())
    "\x0b//evil.com/static/x.apk",
    "/static/x\x7f.apk",
    "http://127.0.0.1:8000/‮/static/x.apk",  # RTL override
    "/static/‥/x",
    "/static/x y.apk",
    "/static/x\\y.apk",
    "http://127.0.0.1:99999/static/x.apk",        # cổng ngoài miền
]


@pytest.fixture(autouse=True)
def _env(monkeypatch):
    monkeypatch.setenv("ADMIN_KEY", ADMIN_KEY)
    monkeypatch.setattr(settings, "BASE_URL", "http://127.0.0.1:8000")


def publish(url):
    return client.post("/v1/app/version", headers={"X-Admin-Key": ADMIN_KEY},
                       json={"version_code": 2, "version_name": "2", "changelog": "c", "download_url": url})


@pytest.mark.parametrize("url", MALFORMED)
def test_malformed_download_url_is_a_400_and_changes_nothing(url):
    assert client.get("/v1/app/check-update").json()["version_code"] == 1
    assert publish(url).status_code == 400
    assert client.get("/v1/app/check-update").json()["version_code"] == 1


@pytest.mark.parametrize("url", ["/static/truyencloud-latest.apk", "http://127.0.0.1:8000/static/truyencloud-latest.apk"])
def test_well_formed_download_urls_are_still_accepted(url):
    res = publish(url)
    assert res.status_code == 200 and res.json()["version"]["download_url"] == url


def test_validators_return_false_instead_of_raising():
    for url in MALFORMED:
        assert app_update._is_own_download_url(url) is False, repr(url)
    assert app_update._local_static_file("/static/x\x00.apk") is None
    assert app_update._local_static_file("http://[::1/static/x.apk") is None


def test_a_malformed_base_url_setting_rejects_absolute_urls_instead_of_crashing(monkeypatch):
    monkeypatch.setattr(settings, "BASE_URL", "http://[::1")
    assert app_update._is_own_download_url("http://127.0.0.1:8000/static/x.apk") is False
    assert publish("http://127.0.0.1:8000/static/x.apk").status_code == 400
