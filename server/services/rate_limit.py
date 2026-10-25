"""Giới hạn tần suất truy cập theo địa chỉ IP (lời gọi AI đã đăng nhập thì theo user id),
cài đặt bằng thuật toán token bucket.

VÌ SAO CẦN:
Máy chủ chạy trên MiniPC và mỗi yêu cầu dịch/tra từ đều spawn một tiến trình
Antigravity CLI tốn 25-90 giây. Không có giới hạn thì một vòng lặp đơn giản
cũng đủ làm treo máy, và `/v1/images/proxy` có thể bị lạm dụng làm proxy băng thông.

Lưu trong bộ nhớ tiến trình — đủ cho triển khai một worker. Nhiều worker thì
nên chuyển sang Redis.
"""

import ipaddress
import json
import time
import logging
from collections import OrderedDict
from typing import Dict, Optional, Tuple

from fastapi import Request
from fastapi.responses import JSONResponse
from config import settings
from services.auth_token import session_from_headers
from services.rate_limit_groups import group_for_path, wants_recrawl  # noqa: F401  (re-export cho test/nơi khác)

logger = logging.getLogger("truyencloud.rate_limit")

# Số khách tối đa theo dõi cùng lúc, tránh phình bộ nhớ khi bị quét
_MAX_TRACKED_CLIENTS = 5000


class TokenBucket:
    """Cho phép `capacity` yêu cầu tức thời, hồi lại `refill_per_sec` mỗi giây."""

    def __init__(self, capacity: int, refill_per_sec: float, name: str = ""):
        self.capacity = float(capacity)
        self.refill_per_sec = float(refill_per_sec)
        self.name = name
        self._buckets: "OrderedDict[str, Tuple[float, float]]" = OrderedDict()

    def _evict_if_needed(self) -> None:
        while len(self._buckets) > _MAX_TRACKED_CLIENTS:
            self._buckets.popitem(last=False)

    def check(self, key: str) -> Tuple[bool, float]:
        """Trả (được_phép, số_giây_chờ_nếu_bị_chặn)."""
        now = time.monotonic()
        tokens, last = self._buckets.get(key, (self.capacity, now))

        tokens = min(self.capacity, tokens + (now - last) * self.refill_per_sec)
        if tokens >= 1.0:
            self._buckets[key] = (tokens - 1.0, now)
            self._buckets.move_to_end(key)
            self._evict_if_needed()
            return True, 0.0

        self._buckets[key] = (tokens, now)
        self._buckets.move_to_end(key)
        retry_after = (1.0 - tokens) / self.refill_per_sec if self.refill_per_sec > 0 else 60.0
        return False, retry_after

    def peek(self, key: str) -> Tuple[bool, float]:
        """Như check() nhưng KHÔNG tiêu token: dùng cho xô chỉ trừ khi yêu cầu thất bại."""
        now = time.monotonic()
        tokens, last = self._buckets.get(key, (self.capacity, now))
        tokens = min(self.capacity, tokens + (now - last) * self.refill_per_sec)
        if tokens >= 1.0:
            return True, 0.0
        return False, (1.0 - tokens) / self.refill_per_sec if self.refill_per_sec > 0 else 60.0


def _parse_ip(value: str):
    try:
        ip = ipaddress.ip_address((value or "").strip())
    except ValueError:
        return None
    if ip.version == 6 and ip.ipv4_mapped:
        return ip.ipv4_mapped
    return ip


def _is_trusted_proxy(host: str) -> bool:
    ip = _parse_ip(host)
    return ip is not None and any(ip in net for net in settings.trusted_proxy_networks)


def _bucket_ip(ip) -> str:
    """Khoá theo IP. IPv6 gom theo /64: một thuê bao thường có nguyên một /64 nên xoay địa chỉ trong
    đó (2**64 giá trị) sẽ xoá sạch giới hạn nếu mỗi địa chỉ có xô riêng."""
    if ip.version == 6:
        return str(ipaddress.ip_network(f"{ip}/64", strict=False))
    return str(ip)


def client_key(request: Request) -> str:
    """Khoá nhận dạng khách gọi. Header chuyển tiếp chỉ được tin khi kết nối trực
    tiếp đến từ proxy đã cấu hình — nếu không, ai cũng tự xoay khoá được."""
    peer = request.client.host if request.client else "unknown"
    if not _is_trusted_proxy(peer):
        peer_ip = _parse_ip(peer)
        return _bucket_ip(peer_ip) if peer_ip is not None else peer

    # Cloudflare ghi đè CF-Connecting-IP bằng IP thật, khách không giả được
    cf_ip = _parse_ip(request.headers.get("cf-connecting-ip", ""))
    if cf_ip is not None:
        return _bucket_ip(cf_ip)

    # Phần tử trái cùng của XFF do khách tự khai; lấy chặng phải cùng không phải proxy tin cậy
    hops = [hop.strip() for hop in request.headers.get("x-forwarded-for", "").split(",") if hop.strip()]
    for hop in reversed(hops):
        if not _is_trusted_proxy(hop):
            ip = _parse_ip(hop)
            return _bucket_ip(ip) if ip is not None else peer
    return peer


# Các mức giới hạn theo nhóm endpoint
_LIMITS: Dict[str, TokenBucket] = {
    # Gọi AI: rất tốn tài nguyên (mỗi lần là một tiến trình agy)
    "ai": TokenBucket(capacity=12, refill_per_sec=12 / 60.0, name="ai"),
    # Cào truyện: chạm vào web bên ngoài
    "scrape": TokenBucket(capacity=10, refill_per_sec=10 / 60.0, name="scrape"),
    # Proxy ảnh: nhẹ nhưng dễ bị lạm dụng làm proxy băng thông
    "image": TokenBucket(capacity=120, refill_per_sec=2.0, name="image"),
    # Đăng nhập + quản trị: chống dò mật khẩu / khoá quản trị
    "auth": TokenBucket(capacity=8, refill_per_sec=8 / 300.0, name="auth"),
    # Trang + API quản trị: mỗi lần mở trang tốn 2-4 yêu cầu nên xô đăng nhập (8/5 phút) làm quản trị viên bị 429.
    # Dò khoá quản trị (>= 32 ký tự ngẫu nhiên) với 60 lần/5 phút vẫn vô vọng; mật khẩu tài khoản vẫn đi qua "auth".
    "admin": TokenBucket(capacity=60, refill_per_sec=60 / 300.0, name="admin"),
    # Lượt quản trị bị từ chối (401/403: sai khoá, phiên giả): vẫn chặt như đăng nhập để chống dò
    "admin_fail": TokenBucket(capacity=8, refill_per_sec=8 / 300.0, name="admin_fail"),
    # Trần chung mỗi IP cho đăng nhập, vì bucket "auth" của login tách theo IP + tên đăng nhập
    "login_ip": TokenBucket(capacity=30, refill_per_sec=30 / 300.0, name="login_ip"),
    # Tiền nạp TTS: mở cho khách, mỗi lần xếp tới 15 đoạn tổng hợp giọng nói
    "tts_prefetch": TokenBucket(capacity=20, refill_per_sec=20 / 60.0, name="tts_prefetch"),
    # Dựng giọng trước + hỏi tiến độ: mỗi lần gọi có thể xếp tới 5 chương, nhưng app chỉ gọi lại mỗi vài giây
    "tts_prerender": TokenBucket(capacity=60, refill_per_sec=1.0, name="tts_prerender"),
    # Luồng giọng đọc: cùng mức với xô ảnh (không nới giới hạn chống lạm dụng) nhưng tách riêng để ảnh bìa không làm
    # đoạn đang đọc bị 429. Đọc bình thường chỉ ~1 yêu cầu mỗi đoạn (vài giây âm thanh) nên 2/giây là dư.
    "tts_stream": TokenBucket(capacity=120, refill_per_sec=2.0, name="tts_stream"),
    # Chấm phát âm: không gọi AI nhưng tốn CPU; tính theo từng người dùng, tách khỏi hạn mức AI chung
    "pronunciation": TokenBucket(capacity=15, refill_per_sec=30 / 60.0, name="pronunciation"),
    # Mặc định cho phần còn lại
    "default": TokenBucket(capacity=180, refill_per_sec=3.0, name="default"),
}

_LOGIN_PATH = "/v1/auth/login"
_MAX_LOGIN_BODY_BYTES = 4096
# Nhóm mà người đã đăng nhập được tính theo user id thay vì IP (nhiều người sau một NAT)
_USER_KEYED_GROUPS = ("ai", "pronunciation")


def _is_limited_path(path: str) -> bool:
    return path.startswith("/v1/") or path == "/admin" or path.startswith("/admin/")


async def _login_identifier(request: Request) -> Optional[str]:
    """Tên đăng nhập trong body login, hoặc None nếu không đọc an toàn được.

    Starlette >= 0.28 lưu đệm body đã đọc trong BaseHTTPMiddleware nên handler phía sau
    vẫn nhận đủ body. Chỉ đọc body nhỏ có Content-Length để không bị dùng làm đường DoS.
    """
    try:
        length = int(request.headers.get("content-length", ""))
    except ValueError:
        return None
    if length <= 0 or length > _MAX_LOGIN_BODY_BYTES:
        return None
    try:
        data = json.loads(await request.body())
    except Exception:
        return None
    identifier = data.get("email") if isinstance(data, dict) else None
    if not isinstance(identifier, str) or not identifier.strip():
        return None
    return identifier.strip().lower()[:128]


def _too_many(key: str, group: str, retry_after: float) -> JSONResponse:
    logger.warning("Chặn do vượt tần suất: %s nhóm=%s", key, group)
    return JSONResponse(
        status_code=429,
        content={
            "detail": "Bạn thao tác quá nhanh. Vui lòng thử lại sau ít giây.",
            "retry_after_seconds": round(retry_after, 1),
        },
        headers={"Retry-After": str(max(1, int(retry_after)))},
    )


async def rate_limit_middleware(request: Request, call_next):
    """Middleware áp giới hạn tần suất cho mọi yêu cầu /v1/ và trang /admin."""
    path = request.url.path
    if not _is_limited_path(path):
        return await call_next(request)

    group = group_for_path(path, request.url.query, request.method)
    ip = client_key(request)
    key = f"{group}:{ip}"

    if group in _USER_KEYED_GROUPS:
        session = session_from_headers(request.headers)
        if session:
            key = f"{group}:user:{session['sub']}"
    elif group == "auth" and request.method == "POST" and path == _LOGIN_PATH:
        identifier = await _login_identifier(request)
        if identifier:
            ip_key = f"login_ip:{ip}"
            allowed, retry_after = _LIMITS["login_ip"].check(ip_key)
            if not allowed:
                return _too_many(ip_key, "login_ip", retry_after)
            key = f"{group}:{ip}:{identifier}"

    fail_key = f"admin_fail:{ip}"
    if group == "admin":
        allowed, retry_after = _LIMITS["admin_fail"].peek(fail_key)
        if not allowed:
            return _too_many(fail_key, "admin_fail", retry_after)

    allowed, retry_after = _LIMITS[group].check(key)
    if not allowed:
        return _too_many(key, group, retry_after)

    response = await call_next(request)
    if group == "admin" and response.status_code in (401, 403):
        _LIMITS["admin_fail"].check(fail_key)
    return response


def reset_all() -> None:
    """Xoá toàn bộ trạng thái — dùng trong kiểm thử."""
    for bucket in _LIMITS.values():
        bucket._buckets.clear()
