"""Luồng giọng đọc không được dùng chung xô giới hạn tần suất với proxy ảnh.

Một lần /v1/tts/stream bị 429 là một lần giọng AI "hỏng" trong mắt app: bộ ngắt mạch chuyển cả phiên sang giọng máy
~3 phút. Trước đây /v1/tts/stream nằm chung xô "image" (120 lượt, nạp 2/giây mỗi IP) với /v1/images/proxy, nên mở một
danh sách dài ảnh bìa rồi bấm nghe ngay có thể bị 429 ở đoạn đầu.
"""

from fastapi.testclient import TestClient

from server import app
from services import rate_limit

client = TestClient(app, raise_server_exceptions=False)

SEPARATOR = "***"  # đoạn không có chữ: máy chủ trả MP3 im lặng, không ra mạng


def test_stream_va_proxy_anh_la_hai_xo_khac_nhau():
    assert rate_limit.group_for_path("/v1/tts/stream") != rate_limit.group_for_path("/v1/images/proxy")
    assert rate_limit.group_for_path("/v1/images/proxy") == "image"
    assert rate_limit.group_for_path("/v1/tts/stream") == "tts_stream"
    assert rate_limit.group_for_path("/v1/tts/prefetch") == "tts_prefetch"


def test_tai_het_xo_anh_bia_van_nghe_duoc_giong_ai():
    rate_limit.reset_all()
    throttled = 0
    for _ in range(150):  # vượt hẳn 120 lượt của xô ảnh
        if client.get("/v1/images/proxy").status_code == 429:
            throttled += 1
    assert throttled > 0, "xô ảnh phải thực sự cạn thì phép thử này mới có nghĩa"

    resp = client.get("/v1/tts/stream", params={"text": SEPARATOR, "voice": "mai_phuong", "lang": "vi"})
    assert resp.status_code == 200
    assert resp.headers["content-type"] == "audio/mpeg"


def test_xo_stream_van_chan_ke_lam_dung():
    rate_limit.reset_all()
    codes = [
        client.get("/v1/tts/stream", params={"text": SEPARATOR, "voice": "mai_phuong", "lang": "vi"}).status_code
        for _ in range(200)
    ]
    assert codes[0] == 200
    assert 429 in codes
