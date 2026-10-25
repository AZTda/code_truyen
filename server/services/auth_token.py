"""Token phiên có chữ ký HMAC + phụ thuộc xác thực cho FastAPI.

VÌ SAO CẦN:
Trước module này, `/auth/login` cấp token dạng
    tk_{user_id}_{abs(hash(password)) % 100000}
— không ký, không hạn dùng, và KHÔNG một chỗ nào trong mã nguồn kiểm tra nó.
`/auth/me` luôn trả về user "test1" bất kể gửi token gì, và mọi endpoint ghi
(đồng bộ tiến độ, ghi hồ sơ dịch, xoá cache) đều mở cho bất kỳ ai.

Định dạng token: <payload_b64url>.<hmac_sha256_b64url>
Không dùng thư viện JWT ngoài để giữ backend không thêm phụ thuộc.

Chữ ký hợp lệ chưa đủ: `current_user` / `optional_user` còn hỏi sổ tài khoản (users.json, có đệm theo mtime)
xem tài khoản trong token còn tồn tại và id có khớp bản ghi hiện tại không. Nhờ vậy xoá tài khoản là thu hồi
phiên ngay, và username tạo lại (id ngẫu nhiên mới) không thừa hưởng phiên của người trước.
"""

import base64
import hashlib
import hmac
import json
import logging
import os
import secrets
import time
from typing import Any, Dict, Mapping, Optional, Tuple

from fastapi import Header, HTTPException

logger = logging.getLogger("truyencloud.auth_token")

DEFAULT_TTL_SECONDS = 30 * 24 * 3600  # 30 ngày

_SECRET_FILE = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", ".session_secret"
)


def _load_or_create_secret() -> str:
    """Lấy khoá ký từ biến môi trường, hoặc sinh và lưu một khoá ngẫu nhiên.

    Ưu tiên JWT_SECRET/SESSION_SECRET để nhiều tiến trình uvicorn dùng chung khoá.
    Nếu không đặt, sinh khoá ngẫu nhiên và lưu vào đĩa để token không mất hiệu lực
    sau mỗi lần khởi động lại.
    """
    env_secret = os.getenv("SESSION_SECRET") or os.getenv("JWT_SECRET")
    if env_secret and len(env_secret) >= 16:
        return env_secret

    try:
        if os.path.exists(_SECRET_FILE):
            with open(_SECRET_FILE, "r", encoding="utf-8") as f:
                existing = f.read().strip()
                if len(existing) >= 32:
                    return existing
    except Exception:
        pass

    generated = secrets.token_urlsafe(48)
    try:
        os.makedirs(os.path.dirname(_SECRET_FILE), exist_ok=True)
        with open(_SECRET_FILE, "w", encoding="utf-8") as f:
            f.write(generated)
        os.chmod(_SECRET_FILE, 0o600)
        logger.warning(
            "Chưa đặt SESSION_SECRET — đã sinh khoá ngẫu nhiên và lưu tại %s. "
            "Nên đặt biến môi trường SESSION_SECRET khi chạy nhiều worker.",
            _SECRET_FILE,
        )
    except Exception as e:
        logger.error("Không lưu được khoá phiên: %s", e)
    return generated


_SECRET = _load_or_create_secret()


def _b64url_encode(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _b64url_decode(data: str) -> bytes:
    padding = "=" * (-len(data) % 4)
    return base64.urlsafe_b64decode(data + padding)


def issue_token_with_expiry(
    user_id: str, username: str = "", ttl_seconds: int = DEFAULT_TTL_SECONDS
) -> Tuple[str, int]:
    """Cấp token đã ký và trả kèm thời điểm hết hạn (epoch giây)."""
    now = int(time.time())
    payload = {
        "sub": user_id,
        "usr": username,
        "iat": now,
        "exp": now + ttl_seconds,
    }
    body = _b64url_encode(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    sig = hmac.new(_SECRET.encode("utf-8"), body.encode("ascii"), hashlib.sha256).digest()
    return f"{body}.{_b64url_encode(sig)}", payload["exp"]


def issue_token(user_id: str, username: str = "", ttl_seconds: int = DEFAULT_TTL_SECONDS) -> str:
    """Cấp token đã ký cho một người dùng."""
    return issue_token_with_expiry(user_id, username, ttl_seconds)[0]


def verify_token(token: str, grace_seconds: int = 0) -> Optional[Dict[str, Any]]:
    """Xác minh chữ ký và hạn dùng. Trả payload nếu hợp lệ, None nếu không."""
    if not token or "." not in token:
        return None
    try:
        body, sig_part = token.rsplit(".", 1)
        expected = hmac.new(
            _SECRET.encode("utf-8"), body.encode("ascii"), hashlib.sha256
        ).digest()
        # So sánh theo thời gian hằng định để tránh tấn công đo thời gian
        if not hmac.compare_digest(_b64url_decode(sig_part), expected):
            return None
        payload = json.loads(_b64url_decode(body))
        if not isinstance(payload, dict):
            return None
        if int(payload.get("exp", 0)) + max(0, grace_seconds) < int(time.time()):
            return None
        if not payload.get("sub"):
            return None
        return payload
    except Exception:
        return None


def _extract_token(authorization: Optional[str], x_auth_token: Optional[str]) -> Optional[str]:
    if authorization:
        parts = authorization.strip().split(None, 1)
        if len(parts) == 2 and parts[0].lower() == "bearer":
            return parts[1].strip()
        return authorization.strip()
    if x_auth_token:
        return x_auth_token.strip()
    return None


def session_from_headers(headers: Mapping[str, str]) -> Optional[Dict[str, Any]]:
    """Payload phiên hợp lệ từ header thô — cho middleware, nơi không dùng được Depends."""
    token = _extract_token(headers.get("authorization"), headers.get("x-auth-token"))
    return verify_token(token) if token else None


def account_exists(payload: Dict[str, Any]) -> bool:
    """Tài khoản trong token còn tồn tại và token thuộc đúng bản ghi hiện tại (id khớp).

    Token ký xong có hiệu lực 30 ngày; nếu không hỏi sổ tài khoản thì người đã bị xoá vẫn dùng được
    phiên cũ, và username tạo lại sẽ thừa hưởng phiên (và dữ liệu) của người trước. Lỗi đọc sổ -> từ chối.
    """
    # Import muộn: routes.auth import module này nên import ở đầu file sẽ tạo vòng.
    from routes.auth import find_user_for_token, load_users_cached
    try:
        return find_user_for_token(load_users_cached(), payload) is not None
    except Exception:
        logger.exception("Không kiểm tra được tài khoản trong token")
        return False


async def current_user(
    authorization: Optional[str] = Header(None),
    x_auth_token: Optional[str] = Header(None, alias="X-Auth-Token"),
) -> Dict[str, Any]:
    """Phụ thuộc BẮT BUỘC xác thực. Dùng cho mọi endpoint ghi dữ liệu."""
    token = _extract_token(authorization, x_auth_token)
    payload = verify_token(token) if token else None
    if not payload:
        raise HTTPException(
            status_code=401,
            detail="Yêu cầu đăng nhập. Token không hợp lệ hoặc đã hết hạn.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    if not account_exists(payload):
        raise HTTPException(
            status_code=401,
            detail="Tài khoản không còn tồn tại hoặc phiên đã bị thu hồi. Hãy đăng nhập lại.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return payload


REFRESH_GRACE_SECONDS = 180 * 24 * 3600  # Cho phép tự gia hạn ngầm trong 180 ngày nếu chữ ký HMAC hợp lệ & tài khoản còn hoạt động


async def refreshable_user(
    authorization: Optional[str] = Header(None),
    x_auth_token: Optional[str] = Header(None, alias="X-Auth-Token"),
) -> Dict[str, Any]:
    """Xác thực dùng riêng cho `/auth/refresh`: chấp nhận token vừa quá hạn 30 ngày miễn chữ ký HMAC hợp lệ và tài khoản còn tồn tại."""
    token = _extract_token(authorization, x_auth_token)
    payload = verify_token(token, grace_seconds=REFRESH_GRACE_SECONDS) if token else None
    if not payload or not account_exists(payload):
        raise HTTPException(
            status_code=401,
            detail="Phiên đăng nhập không hợp lệ hoặc tài khoản không còn tồn tại.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return payload


async def optional_user(
    authorization: Optional[str] = Header(None),
    x_auth_token: Optional[str] = Header(None, alias="X-Auth-Token"),
) -> Optional[Dict[str, Any]]:
    """Phụ thuộc KHÔNG bắt buộc — dùng cho endpoint đọc công khai nhưng muốn
    biết người gọi là ai (ví dụ tiến độ đọc theo từng người). Token của tài khoản đã
    bị xoá coi như không có token (khách)."""
    token = _extract_token(authorization, x_auth_token)
    payload = verify_token(token) if token else None
    return payload if payload and account_exists(payload) else None


def user_id_of(payload: Optional[Dict[str, Any]]) -> str:
    """Lấy user_id từ payload, trả 'local' cho khách chưa đăng nhập."""
    if not payload:
        return "local"
    return str(payload.get("sub") or "local")
