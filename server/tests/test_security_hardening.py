"""Test cho đợt vá bảo mật vòng 3: khoá quản trị, phân quyền, giới hạn tần suất,
làm mới token / đổi mật khẩu, health, CORS.

Mọi thao tác ghi đều trỏ vào tmp_path — không chạm data/ hay truyencloud.db thật.
"""

import json

import pytest
from fastapi.testclient import TestClient
from starlette.requests import Request

import routes.auth as auth_routes
import routes.health as health_routes
import routes.jobs as jobs_routes
import routes.settings as settings_routes
import routes.tts as tts_routes
from config import Settings, settings
from server import app
from services import rate_limit
from services.auth_token import issue_token, verify_token
from services.job_registry import JobRegistry
from services.password import hash_password

client = TestClient(app)
ADMIN_KEY = "hardening-admin-key-for-pytest-only-0123456789"


@pytest.fixture(autouse=True)
def env(tmp_path, monkeypatch):
    users = {
        "boss": {"id": "user_boss", "username": "boss", "password": hash_password("BossPass123"), "is_admin": True},
        "reader": {"id": "user_reader", "username": "reader", "password": hash_password("ReaderPass123")},
        "fake": {"id": "user_fake", "username": "fake", "password": hash_password("FakePass123"), "is_admin": "true"},
    }
    users_file = tmp_path / "users.json"
    users_file.write_text(json.dumps(users), encoding="utf-8")
    monkeypatch.setattr(auth_routes, "DATA_DIR", tmp_path)
    monkeypatch.setattr(auth_routes, "USERS_FILE", users_file)
    monkeypatch.setenv("ADMIN_KEY", ADMIN_KEY)
    monkeypatch.delenv("ADMIN_SECRET", raising=False)
    rate_limit.reset_all()
    yield tmp_path
    rate_limit.reset_all()


def bearer(user_id, username):
    return {"Authorization": f"Bearer {issue_token(user_id, username)}"}


BOSS = lambda: bearer("user_boss", "boss")  # noqa: E731
READER = lambda: bearer("user_reader", "reader")  # noqa: E731


# --- Khoá quản trị -----------------------------------------------------------

@pytest.mark.parametrize("configured", [None, "short-key", "changeme_on_first_login" * 2])
def test_admin_tat_khi_thieu_hoac_khoa_yeu(monkeypatch, configured):
    monkeypatch.delenv("ADMIN_KEY", raising=False)
    if configured:
        monkeypatch.setenv("ADMIN_KEY", configured)
        monkeypatch.setattr(settings, "DEFAULT_TEST_PASSWORD", "changeme_on_first_login" * 2)
    res = client.get("/v1/admin/users", headers={"X-Admin-Key": configured or "Diempro001"})
    assert res.status_code == 503
    assert "ADMIN_KEY" in res.json()["detail"]


def test_admin_khong_con_nhan_query_param_va_mat_khau_cu():
    assert client.get("/v1/admin/users", params={"admin_key": ADMIN_KEY}).status_code == 401
    assert client.get("/v1/admin/users", headers={"X-Admin-Key": "Diempro001"}).status_code == 401


def test_admin_nhan_header_bearer_va_tai_khoan_is_admin():
    assert client.get("/v1/admin/users", headers={"X-Admin-Key": ADMIN_KEY}).status_code == 200
    assert client.get("/v1/admin/users", headers={"Authorization": f"Bearer {ADMIN_KEY}"}).status_code == 200
    res = client.get("/v1/admin/users", headers=BOSS())
    assert res.status_code == 200
    assert {u["username"]: u["is_admin"] for u in res.json()}["boss"] is True


def test_nguoi_dung_thuong_va_is_admin_dang_chuoi_bi_tu_choi():
    assert client.get("/v1/admin/users", headers=READER()).status_code == 403
    assert client.get("/v1/admin/users", headers=bearer("user_fake", "fake")).status_code == 403


def test_username_tao_lai_khong_thua_huong_quyen_admin():
    """Token cũ có usr=boss nhưng id khác không được coi là boss."""
    assert client.get("/v1/admin/users", headers=bearer("user_old_boss", "boss")).status_code == 403


# --- Phân quyền endpoint dùng chung ------------------------------------------

def test_ghi_cau_hinh_may_chu_chi_danh_cho_quan_tri(env, monkeypatch):
    monkeypatch.setattr(settings_routes, "DATA_DIR", env)
    monkeypatch.setattr(settings_routes, "PROFILE_FILE", env / "translation_profile.json")
    catalog = {"cli_version": "x", "active_model": "m", "models": []}
    monkeypatch.setattr(settings_routes.model_catalog_service, "select_model", lambda **kw: catalog)
    monkeypatch.setattr(settings_routes.model_catalog_service, "refresh_catalog", lambda: catalog)

    for path, body in [("/v1/settings/models/select", {"model_id": "m"}),
                       ("/v1/settings/profile", {"custom_prompt": "độc"}),
                       ("/v1/settings/models/refresh", None)]:
        assert client.post(path, json=body, headers=READER()).status_code == 403, path
        assert client.post(path, json=body, headers=BOSS()).status_code == 200, path
    assert client.get("/v1/settings/profile").json()["custom_prompt"] == "độc"


def test_health_toi_gian_va_deep_can_quan_tri(monkeypatch):
    body = client.get("/v1/health").json()
    assert set(body) == {"status", "version"}

    assert client.get("/v1/health/deep").status_code == 401
    assert client.get("/v1/health/deep", headers=READER()).status_code == 403

    class FakeTable:
        rows = {}

        def upsert(self, row):
            self.rows[row["id"]] = row
            self._last = row
            return self

        def select(self, *_):
            return self

        def eq(self, _col, value):
            self._last = self.rows.get(value)
            return self

        def single(self):
            return self

        def execute(self):
            return type("R", (), {"data": self._last})()

    table = FakeTable()
    monkeypatch.setattr(health_routes, "db", type("DB", (), {"table": lambda self, name: table})())
    for _ in range(3):
        res = client.get("/v1/health/deep", headers={"X-Admin-Key": ADMIN_KEY})
        assert res.status_code == 200
        assert res.json()["checks"]["sqlite"]["status"] == "ok"
    assert len(FakeTable.rows) == 1, "Deep health phải ghi đè một dòng cố định"


def test_tts_prefetch_mo_cho_khach(monkeypatch):
    calls = []

    def fake_enqueue(plan, priority=1):
        calls.append(plan.text)
        return True

    monkeypatch.setattr(tts_routes, "enqueue_ahead", fake_enqueue)
    res = client.post("/v1/tts/prefetch", json={"texts": ["a", "b"]})
    assert res.status_code == 200
    assert calls == ["a", "b"]


def test_job_chi_chu_so_huu_doc_duoc(env, monkeypatch):
    registry = JobRegistry(db_path=str(env / "jobs.db"))
    monkeypatch.setattr(jobs_routes, "job_registry", registry)
    job = registry.create("batch_translation", "user_reader")
    assert client.get(f"/v1/jobs/{job.id}").status_code == 401
    assert client.get(f"/v1/jobs/{job.id}", headers=READER()).status_code == 200
    assert client.get(f"/v1/jobs/{job.id}", headers=BOSS()).status_code == 404


# --- Làm mới token / đổi mật khẩu --------------------------------------------

def test_refresh_cap_token_moi_kem_han_va_ho_so():
    res = client.post("/v1/auth/refresh", headers=BOSS())
    assert res.status_code == 200
    data = res.json()
    payload = verify_token(data["token"])
    assert payload["sub"] == "user_boss"
    assert data["expires_at"] == payload["exp"]
    assert data["user"]["id"] == "user_boss"
    assert data["user"]["is_admin"] is True

    assert client.post("/v1/auth/refresh").status_code == 401
    expired = issue_token("user_boss", "boss", ttl_seconds=-5)
    too_old = issue_token("user_boss", "boss", ttl_seconds=-181 * 86400)
    assert client.post("/v1/auth/refresh", headers={"Authorization": f"Bearer {too_old}"}).status_code == 401
    assert client.get("/v1/auth/me", headers={"Authorization": f"Bearer {expired}"}).status_code == 401
    assert client.post("/v1/auth/refresh", headers=bearer("user_gone", "gone")).status_code == 401


def test_me_va_login_tra_is_admin():
    assert client.get("/v1/auth/me", headers=BOSS()).json()["is_admin"] is True
    assert client.get("/v1/auth/me", headers=READER()).json()["is_admin"] is False
    login = client.post("/v1/auth/login", json={"email": "boss", "password": "BossPass123"}).json()
    assert login["user"]["is_admin"] is True


def test_doi_mat_khau():
    headers = READER()
    url = "/v1/auth/change-password"
    wrong = client.post(url, json={"old_password": "sai", "new_password": "NewPass12345"}, headers=headers)
    assert wrong.status_code == 400
    short = client.post(url, json={"old_password": "ReaderPass123", "new_password": "1234567"}, headers=headers)
    assert short.status_code == 400
    assert client.post(url, json={"old_password": "x", "new_password": "NewPass12345"}).status_code == 401

    ok = client.post(url, json={"old_password": "ReaderPass123", "new_password": "NewPass12345"}, headers=headers)
    assert ok.status_code == 200 and ok.json() == {"status": "ok"}
    assert client.post("/v1/auth/login", json={"email": "reader", "password": "ReaderPass123"}).status_code == 401
    assert client.post("/v1/auth/login", json={"email": "reader", "password": "NewPass12345"}).status_code == 200
    # Token cũ vẫn dùng được: đổi mật khẩu không thu hồi phiên nào khác
    assert client.get("/v1/auth/me", headers=headers).status_code == 200


# --- Giới hạn tần suất -------------------------------------------------------

@pytest.mark.parametrize("path,group", [
    ("/v1/admin/users", "admin"),
    ("/admin", "admin"),
    ("/v1/auth/login", "auth"),
    ("/v1/novels/abc/chapters/3/recap", "ai"),
    ("/v1/novels/abc/chapters/3/recrawl", "ai"),
    ("/v1/novels/abc/chapters/3", "default"),
    ("/v1/tts/prefetch", "tts_prefetch"),
    ("/v1/tts/stream", "tts_stream"),  # xô riêng: ảnh bìa không được làm đoạn đang đọc bị 429 (xem test_tts_stream_rate_bucket)
    ("/v1/tts/prerender", "tts_prerender"),
    ("/v1/tts/voices", "image"),
    ("/v1/tts/status", "image"),
])
def test_phan_nhom_moi(path, group):
    assert rate_limit.group_for_path(path) == group


def _request(peer, headers):
    raw = [(k.lower().encode(), v.encode()) for k, v in headers.items()]
    return Request({"type": "http", "method": "GET", "path": "/", "headers": raw, "client": (peer, 5000)})


def test_client_key_chi_tin_header_tu_proxy_tin_cay(monkeypatch):
    monkeypatch.setattr(settings, "TRUSTED_PROXY_IPS", "127.0.0.1,::1,172.16.0.0/12")
    cf = {"CF-Connecting-IP": "203.0.113.9", "X-Forwarded-For": "6.6.6.6, 198.51.100.7"}
    assert rate_limit.client_key(_request("127.0.0.1", cf)) == "203.0.113.9"
    assert rate_limit.client_key(_request("172.18.0.1", cf)) == "203.0.113.9"
    xff = {"X-Forwarded-For": "6.6.6.6, 198.51.100.7"}
    assert rate_limit.client_key(_request("127.0.0.1", xff)) == "198.51.100.7", "Không lấy phần tử client tự khai"
    assert rate_limit.client_key(_request("192.168.1.50", cf)) == "192.168.1.50", "Peer lạ không được tự khai IP"
    monkeypatch.setattr(settings, "TRUSTED_PROXY_IPS", "")
    assert rate_limit.client_key(_request("127.0.0.1", cf)) == "127.0.0.1"


def test_login_tach_bucket_theo_ten_dang_nhap_va_body_van_toi_handler():
    for _ in range(8):
        client.post("/v1/auth/login", json={"email": "victim", "password": "sai"})
    assert client.post("/v1/auth/login", json={"email": "victim", "password": "sai"}).status_code == 429
    # Người khác cùng IP vẫn đăng nhập được; 200 chứng tỏ middleware đọc body không làm mất body
    res = client.post("/v1/auth/login", json={"email": "reader", "password": "ReaderPass123"})
    assert res.status_code == 200


def test_login_co_tran_chung_moi_ip():
    blocked = False
    for i in range(40):
        if client.post("/v1/auth/login", json={"email": f"spray{i}", "password": "x"}).status_code == 429:
            blocked = True
            break
    assert blocked, "Dò nhiều tên đăng nhập từ một IP vẫn phải bị chặn"


def test_ai_tinh_theo_user_khi_co_token():
    a, b = READER(), BOSS()
    for _ in range(12):
        client.post("/v1/lore/__khong_ton_tai__", headers=a)
    assert client.post("/v1/lore/__khong_ton_tai__", headers=a).status_code == 429
    assert client.post("/v1/lore/__khong_ton_tai__", headers=b).status_code != 429
    assert client.post("/v1/lore/__khong_ton_tai__").status_code != 429


# --- CORS --------------------------------------------------------------------

@pytest.mark.parametrize("debug,raw,expected", [
    (False, "*", []),
    (False, "", []),
    (False, "https://a.vn,*", ["https://a.vn"]),
    (True, "", ["*"]),
    (True, "*", ["*"]),
])
def test_cors_production_khong_mo_wildcard(debug, raw, expected):
    cfg = Settings()
    cfg.DEBUG = debug
    cfg.CORS_ORIGINS = raw
    assert cfg.effective_cors_origins == expected


def test_recrawl_query_on_chapter_get_uses_ai_bucket():
    from services.rate_limit import group_for_path

    chapter = "/v1/novels/abc/chapters/5"
    assert group_for_path(chapter, "recrawl=true") == "ai"
    assert group_for_path(chapter, "auto_translate=true&recrawl=1") == "ai"
    assert group_for_path(chapter, "recrawl=false") == "default"
    assert group_for_path(chapter, "") == "default"
    # Chỉ áp cho GET chương, không áp cho đường dẫn khác
    assert group_for_path("/v1/novels/abc", "recrawl=true") == "default"
