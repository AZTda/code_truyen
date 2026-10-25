"""Test bảo mật: SSRF, xác thực, giới hạn tần suất, giới hạn kích thước.

Mỗi test ở đây tương ứng với một lỗ hổng đã được tái hiện bằng proof of concept
trước khi vá — chúng tồn tại để lỗ hổng không quay lại.
"""

import http.server
import json
import socketserver
import threading
import time

import pytest
from fastapi.testclient import TestClient

from server import app
from services.auth_token import issue_token, verify_token
from services.url_guard import assert_safe_url, is_safe_url, UnsafeUrlError, SCRAPE_HOST_ALLOWLIST
from services import rate_limit

client = TestClient(app)


@pytest.fixture(autouse=True)
def _reset_rate_limit():
    """Mỗi test bắt đầu với bộ đếm tần suất sạch."""
    rate_limit.reset_all()
    yield
    rate_limit.reset_all()


@pytest.fixture(scope="module")
def internal_service():
    """Dịch vụ nội bộ giả lập — đóng vai 'thứ mà kẻ tấn công muốn đọc'."""
    payload = json.dumps({"SECRET": "API_KEY=sk-live-xxxx", "db_password": "hunter2"}).encode()

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *args):
            pass

    socketserver.TCPServer.allow_reuse_address = True
    srv = socketserver.TCPServer(("127.0.0.1", 0), Handler)
    port = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    time.sleep(0.2)
    yield port
    srv.shutdown()


# ---------------------------------------------------------------------------
# SSRF
# ---------------------------------------------------------------------------

def test_image_proxy_khong_doc_duoc_dich_vu_noi_bo(internal_service):
    """Lỗ hổng gốc: ?url=http://127.0.0.1:PORT/... trả về nguyên nội dung nội bộ."""
    res = client.get(
        "/v1/images/proxy",
        params={"url": f"http://127.0.0.1:{internal_service}/internal/secrets"},
    )
    assert "SECRET" not in res.text, "RÒ RỈ: proxy trả về nội dung dịch vụ nội bộ"
    assert "hunter2" not in res.text
    assert not res.headers.get("content-type", "").startswith("application/json")


@pytest.mark.parametrize("bad_url", [
    "http://127.0.0.1:8000/v1/settings",
    "http://localhost:8000/v1/settings",
    "http://169.254.169.254/latest/meta-data/",   # metadata cloud
    "http://192.168.1.1/",
    "http://10.0.0.1/admin",
    "http://[::1]:8000/",
    "file:///etc/passwd",
    "gopher://127.0.0.1:8000/",
])
def test_image_proxy_chan_moi_dia_chi_noi_bo(bad_url):
    res = client.get("/v1/images/proxy", params={"url": bad_url})
    # Luôn trả ảnh dự phòng, không bao giờ trả nội dung đích
    assert res.headers.get("content-type", "") in ("image/jpeg", "") or res.status_code == 404


def test_image_proxy_chan_ten_mien_ngoai_danh_sach():
    res = client.get("/v1/images/proxy", params={"url": "https://example.com/anh.jpg"})
    assert not res.headers.get("content-type", "").startswith("text/")


def test_scrape_chan_url_noi_bo(internal_service):
    token = issue_token("user_test1", "test1")
    res = client.post(
        "/v1/scrape",
        json={"source_url": f"http://127.0.0.1:{internal_service}/admin"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert res.status_code == 400, "Phải từ chối URL nội bộ"
    assert "không được chấp nhận" in res.json()["detail"].lower() or "khong" in res.json()["detail"].lower()


def test_url_guard_chan_dung_cac_truong_hop():
    for bad in ["http://127.0.0.1/", "http://10.1.2.3/", "http://169.254.169.254/",
                "ftp://cdn.cdnshu.com/a.jpg", "", "http://"]:
        assert not is_safe_url(bad), f"Phải chặn: {bad}"

    # Tên miền hợp lệ nhưng không có trong allowlist cũng bị chặn
    assert not is_safe_url("https://evil.example.com/a.jpg")

    # Bỏ allowlist vẫn phải chặn IP nội bộ
    with pytest.raises(UnsafeUrlError):
        assert_safe_url("http://127.0.0.1/x", require_allowlist=False)


def test_url_guard_chan_url_qua_dai():
    assert not is_safe_url("https://cdn.cdnshu.com/" + "a" * 3000)


# ---------------------------------------------------------------------------
# Xác thực
# ---------------------------------------------------------------------------

def test_token_co_chu_ky_va_han_dung():
    token = issue_token("user_42", "diem")
    payload = verify_token(token)
    assert payload is not None
    assert payload["sub"] == "user_42"
    assert payload["usr"] == "diem"
    assert payload["exp"] > payload["iat"]


def test_token_bi_sua_thi_khong_hop_le():
    token = issue_token("user_42")
    body, sig = token.rsplit(".", 1)
    assert verify_token(f"{body}.{'A' * len(sig)}") is None, "Chữ ký sai phải bị từ chối"

    # Đổi payload nhưng giữ chữ ký cũ
    import base64, json as _json
    forged = base64.urlsafe_b64encode(
        _json.dumps({"sub": "admin", "exp": 9999999999}).encode()
    ).rstrip(b"=").decode()
    assert verify_token(f"{forged}.{sig}") is None


def test_token_het_han_bi_tu_choi():
    assert verify_token(issue_token("user_42", ttl_seconds=-10)) is None


@pytest.mark.parametrize("token", ["", "rac", "tk_user_test1_12345", "Bearer x.y"])
def test_token_bia_dat_bi_tu_choi(token):
    assert verify_token(token) is None


def test_auth_me_yeu_cau_token():
    """Bản cũ luôn trả về user test1 dù không có token."""
    assert client.get("/v1/auth/me").status_code == 401
    assert client.get(
        "/v1/auth/me", headers={"Authorization": "Bearer token_bia_dat"}
    ).status_code == 401


def test_auth_me_tra_dung_nguoi_trong_token():
    token = issue_token("user_test1", "test1")
    res = client.get("/v1/auth/me", headers={"Authorization": f"Bearer {token}"})
    assert res.status_code == 200
    assert res.json()["id"] == "user_test1"


@pytest.mark.parametrize("method,path,body", [
    ("POST", "/v1/settings/profile", {"custom_prompt": "x"}),
    ("POST", "/v1/settings/api-key", {"api_key": "x"}),
    ("POST", "/v1/settings/models/refresh", None),
    ("POST", "/v1/scrape", {"source_url": "https://www.69shuba.com/book/1.htm"}),
    ("DELETE", "/v1/english/cache", None),
    ("POST", "/v1/novels/abc/check-updates", None),
])
def test_moi_endpoint_ghi_deu_yeu_cau_xac_thuc(method, path, body):
    res = client.request(method, path, json=body) if body else client.request(method, path)
    assert res.status_code == 401, f"{method} {path} phải yêu cầu đăng nhập"


def test_tien_do_doc_tach_theo_nguoi_dung():
    """user_id phải lấy từ TOKEN, không phải từ body do client gửi."""
    t1 = issue_token("user_A", "a")
    t2 = issue_token("user_B", "b")

    client.post("/v1/progress/sync",
                json={"novel_id": "n_shared", "current_chapter": 11},
                headers={"Authorization": f"Bearer {t1}"})
    client.post("/v1/progress/sync",
                json={"novel_id": "n_shared", "current_chapter": 77},
                headers={"Authorization": f"Bearer {t2}"})

    a = client.get("/v1/progress/n_shared", headers={"Authorization": f"Bearer {t1}"}).json()
    b = client.get("/v1/progress/n_shared", headers={"Authorization": f"Bearer {t2}"}).json()
    assert a["current_chapter"] == 11
    assert b["current_chapter"] == 77, "Hai người dùng không được dùng chung tiến độ"


def test_dong_bo_nhieu_lan_khong_sinh_dong_trung():
    t = issue_token("user_C", "c")
    h = {"Authorization": f"Bearer {t}"}
    for ch in range(1, 6):
        client.post("/v1/progress/sync", json={"novel_id": "n_dup", "current_chapter": ch}, headers=h)
    res = client.get("/v1/progress/n_dup", headers=h).json()
    assert res["current_chapter"] == 5, "Phải giữ giá trị mới nhất, không sinh dòng mới mỗi lần"


# ---------------------------------------------------------------------------
# Giới hạn tần suất
# ---------------------------------------------------------------------------

def test_gioi_han_tan_suat_chan_goi_ai_don_dap():
    """Mỗi lời gọi AI là một tiến trình Antigravity 25-90 giây."""
    blocked = False
    for _ in range(40):
        res = client.post("/v1/english/explain-word", json={"word": "test"})
        if res.status_code == 429:
            blocked = True
            assert "Retry-After" in res.headers
            break
    assert blocked, "Phải chặn khi gọi AI quá dày"


def test_gioi_han_khong_chan_endpoint_nhe():
    rate_limit.reset_all()
    for _ in range(30):
        assert client.get("/v1/health").status_code == 200


def test_phan_nhom_gioi_han_theo_duong_dan():
    assert rate_limit.group_for_path("/v1/english/explain-word") == "ai"
    assert rate_limit.group_for_path("/v1/translate") == "ai"
    assert rate_limit.group_for_path("/v1/images/proxy") == "image"
    assert rate_limit.group_for_path("/v1/auth/login") == "auth"
    assert rate_limit.group_for_path("/v1/novels/abc") == "default"


# ---------------------------------------------------------------------------
# Giới hạn kích thước
# ---------------------------------------------------------------------------

def test_tu_choi_tep_import_qua_lon():
    token = issue_token("user_test1", "test1")
    big = b"x" * (26 * 1024 * 1024)
    res = client.post(
        "/v1/novels/import",
        files={"file": ("huge.txt", big, "text/plain")},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert res.status_code == 413, "Tệp vượt trần phải bị từ chối bằng 413"
