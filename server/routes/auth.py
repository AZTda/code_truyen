"""Authentication routes for TruyenCloud backend API."""

import json
import logging
import os
import tempfile
import threading
from pathlib import Path
from typing import Optional, Dict, Any
from fastapi import APIRouter, Depends, HTTPException
from config import settings
from models.auth_dtos import (  # noqa: F401  (re-export: giữ nguyên `from routes.auth import ...Request/Response`)
    AuthResponse,
    ChangePasswordRequest,
    LoginRequest,
    RefreshResponse,
    RegisterRequest,
    SocialLoginRequest,
    UserProfileResponse,
)
from services.auth_token import issue_token, issue_token_with_expiry, current_user, refreshable_user
from services.password import hash_password, verify_password, DEFAULT_AVATAR, DEFAULT_BANNER
from services.user_registry import UsersCache

router = APIRouter(prefix="/auth", tags=["auth"])

DATA_DIR = Path(__file__).parent.parent / "data"
USERS_FILE = DATA_DIR / "users.json"

logger = logging.getLogger("truyencloud.auth")
_USERS_LOCK = threading.RLock()
MIN_PASSWORD_LENGTH = 8

DEFAULT_TEST1_USER = {
    "id": "user_test1",
    "username": "test1",
    "email": "test1@truyencloud.vn",
    "password": hash_password(settings.DEFAULT_TEST_PASSWORD),
    "display_name": "Diễm Trần (test1)",
    "bio": "Kẻ du hành qua vạn trang sách 🌌",
    "vip_tier": "✦ VIP Tinh Anh 2026",
    "avatar_url": DEFAULT_AVATAR,
    "banner_url": DEFAULT_BANNER,
    "auth_provider": "email",
    "is_logged_in": True,
    "total_reading_hours": 48.5,
    "total_chapters_read": 158,
    "total_words_translated": 240000,
    "reading_streak_days": 7,
}


def load_users() -> Dict[str, Dict[str, Any]]:
    with _USERS_LOCK:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        if not USERS_FILE.exists():
            # Never bootstrap a known-password account in a non-debug deployment.
            if not settings.DEBUG:
                logger.error("Thiếu users.json; tài khoản thử test1 chỉ được tạo khi DEBUG=true. "
                             "Hãy tạo tài khoản bằng: python manage_users.py add <user> <mật_khẩu>")
                return {}
            users = {"test1": DEFAULT_TEST1_USER}
            save_users(users)
            return users
        try:
            with open(USERS_FILE, "r", encoding="utf-8") as f:
                users = json.load(f)
            return users if isinstance(users, dict) else {}
        except Exception:
            # Do not silently replace a corrupted account database with test1.
            logger.exception("Không đọc được users.json; tạm thời khóa đăng nhập.")
            return {}


def save_users(users: Dict[str, Dict[str, Any]]):
    with _USERS_LOCK:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        fd, temp_path = tempfile.mkstemp(prefix=".users-", suffix=".tmp", dir=DATA_DIR)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(users, f, ensure_ascii=False, indent=2)
                f.flush()
                os.fsync(f.fileno())
            os.chmod(temp_path, 0o600)
            os.replace(temp_path, USERS_FILE)
        finally:
            if os.path.exists(temp_path):
                os.unlink(temp_path)
    _users_cache.invalidate()


# Bản đọc-chung cho phép kiểm tra "tài khoản còn tồn tại" trên mọi request mà không parse lại tệp.
_users_cache = UsersCache(lambda: USERS_FILE, load_users)


def load_users_cached() -> Dict[str, Dict[str, Any]]:
    """users.json đã đệm (làm mới khi tệp đổi). KHÔNG sửa kết quả — muốn sửa thì dùng load_users()."""
    return _users_cache.get()


def reset_users_cache() -> None:
    _users_cache.invalidate()


def is_admin_record(record: Optional[Dict[str, Any]]) -> bool:
    # Chỉ nhận đúng boolean true: chuỗi "false" trong JSON sửa tay vẫn là truthy.
    return bool(record) and record.get("is_admin") is True


def find_user_for_token(users: Dict[str, Dict[str, Any]], payload: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Bản ghi của người trong token. Khớp theo username thì id cũng phải khớp,
    để username bị xoá rồi tạo lại không thừa hưởng phiên (và quyền) của người cũ."""
    user_id = payload.get("sub")
    username = payload.get("usr", "")
    record = users.get(username) if username else None
    if record is not None and str(record.get("id", "user_internal")) == str(user_id):
        return record
    return next((u for u in users.values() if u.get("id") == user_id), None)


def _session_profile(record: Dict[str, Any], identifier: str) -> UserProfileResponse:
    """Hồ sơ trả về khi đăng nhập / gia hạn token."""
    return UserProfileResponse(
        id=record.get("id", "user_internal"),
        email=record.get("email", f"{identifier}@truyencloud.vn"),
        display_name=record.get("display_name", identifier),
        bio=record.get("bio", "Thành viên nội bộ TruyenCloud 🌌"),
        vip_tier=record.get("vip_tier", "✦ VIP Tinh Anh 2026"),
        avatar_url=record.get("avatar_url", DEFAULT_AVATAR),
        banner_url=record.get("banner_url", DEFAULT_BANNER),
        auth_provider="email",
        is_logged_in=True,
        is_admin=is_admin_record(record),
    )


def find_user_by_identifier(users: Dict[str, Dict[str, Any]], identifier: str) -> Optional[Dict[str, Any]]:
    ident = identifier.strip().lower()
    for key, user in users.items():
        if key.lower() == ident:
            return user
        if user.get("username", "").lower() == ident:
            return user
        if user.get("email", "").lower() == ident:
            return user
    return None


@router.post("/login", response_model=AuthResponse)
async def login(req: LoginRequest):
    """Authenticate internal user with username/email and password."""
    email_or_user = req.email.strip()
    if not email_or_user or not req.password:
        raise HTTPException(status_code=400, detail="Vui lòng nhập đầy đủ tên đăng nhập/email và mật khẩu.")

    users = load_users()
    matched_user = find_user_by_identifier(users, email_or_user)

    if not matched_user:
        raise HTTPException(
            status_code=401,
            detail="Tên đăng nhập hoặc mật khẩu không chính xác.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    # Strict password verification with PBKDF2
    stored_pwd = matched_user.get("password", "")
    if not verify_password(stored_pwd, req.password):
        raise HTTPException(
            status_code=401,
            detail="Tên đăng nhập hoặc mật khẩu không chính xác.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    return AuthResponse(
        status="success",
        message=f"Đăng nhập thành công! Chào mừng {matched_user.get('display_name', 'bạn')}.",
        # Token da ky HMAC, co han dung. Ban cu la f"tk_{id}_{hash(password)%100000}"
        # — khong ky, khong han, va khong mot cho nao kiem tra.
        token=issue_token(
            user_id=matched_user.get("id", "user_internal"),
            username=matched_user.get("username", ""),
        ),
        user=_session_profile(matched_user, email_or_user),
    )


@router.post("/refresh", response_model=RefreshResponse)
async def refresh_token(auth: dict = Depends(refreshable_user)):
    """Đổi token đã ký HMAC lấy token mới 30 ngày (hỗ trợ Silent Token Refresh kể cả khi vừa quá hạn 30 ngày)."""
    record = find_user_for_token(load_users(), auth)
    if not record:
        raise HTTPException(
            status_code=401,
            detail="Tài khoản không còn tồn tại.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    token, expires_at = issue_token_with_expiry(
        user_id=record.get("id", "user_internal"),
        username=record.get("username", ""),
    )
    identifier = record.get("username") or str(auth.get("usr") or "")
    return RefreshResponse(token=token, expires_at=expires_at, user=_session_profile(record, identifier))


@router.post("/change-password")
async def change_password(req: ChangePasswordRequest, auth: dict = Depends(current_user)):
    """Người dùng tự đổi mật khẩu; các token đang dùng vẫn giữ hiệu lực."""
    if len(req.new_password or "") < MIN_PASSWORD_LENGTH:
        raise HTTPException(status_code=400, detail=f"Mật khẩu mới phải có ít nhất {MIN_PASSWORD_LENGTH} ký tự.")
    # Giữ khoá suốt đọc-sửa-ghi để hai lần đổi đồng thời không ghi đè mất nhau.
    with _USERS_LOCK:
        users = load_users()
        record = find_user_for_token(users, auth)
        if not record:
            raise HTTPException(
                status_code=401,
                detail="Tài khoản không còn tồn tại.",
                headers={"WWW-Authenticate": "Bearer"},
            )
        if not verify_password(record.get("password", ""), req.old_password or ""):
            raise HTTPException(status_code=400, detail="Mật khẩu hiện tại không chính xác.")
        record["password"] = hash_password(req.new_password)
        save_users(users)
    return {"status": "ok"}


@router.post("/register", response_model=AuthResponse)
async def register(req: RegisterRequest):
    """Public registration is disabled for internal private system."""
    raise HTTPException(
        status_code=403,
        detail="Hệ thống nội bộ riêng tư không hỗ trợ đăng ký công khai. Vui lòng liên hệ Quản trị viên để được cấp tài khoản."
    )


@router.post("/social", response_model=AuthResponse)
async def social_login(req: SocialLoginRequest):
    """Social login is disabled for internal private system."""
    raise HTTPException(
        status_code=403,
        detail="Đăng nhập Google / Apple đã bị vô hiệu hoá trên hệ thống nội bộ. Vui lòng đăng nhập bằng tài khoản được cấp."
    )


@router.get("/me", response_model=UserProfileResponse)
async def get_current_user(auth: dict = Depends(current_user)):
    """Lấy hồ sơ của CHÍNH người đang đăng nhập (đọc từ token đã xác minh).

    Bản cũ bỏ qua hoàn toàn token và luôn trả về user "test1", nên gửi token bịa
    hay không gửi gì cũng cho cùng một kết quả.
    """
    user_id = auth.get("sub")
    matched = find_user_for_token(load_users(), auth)

    if not matched:
        raise HTTPException(status_code=404, detail="Tài khoản không còn tồn tại.")

    return UserProfileResponse(
        id=matched.get("id", user_id),
        email=matched.get("email", ""),
        display_name=matched.get("display_name", ""),
        bio=matched.get("bio", ""),
        vip_tier=matched.get("vip_tier", ""),
        avatar_url=matched.get("avatar_url", DEFAULT_AVATAR),
        banner_url=matched.get("banner_url", DEFAULT_BANNER),
        auth_provider=matched.get("auth_provider", "email"),
        is_logged_in=True,
        is_admin=is_admin_record(matched),
    )
