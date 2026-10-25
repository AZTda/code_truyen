"""Kiểm soát quyền quản trị: khoá ADMIN_KEY hoặc phiên của tài khoản is_admin.

Tách khỏi routes/admin.py (quản lý tài khoản) để mỗi tệp một trách nhiệm; routes/admin.py re-export các
tên này nên `from routes.admin import verify_admin_access` ở các route khác vẫn chạy.
"""

import hmac
import logging
import os
from typing import Any, Dict, Optional

from fastapi import Depends, Header, HTTPException, status

from config import settings
from routes.auth import load_users_cached, find_user_for_token, is_admin_record
from services.auth_token import optional_user

logger = logging.getLogger("truyencloud.admin")

ADMIN_KEY_MIN_LENGTH = 32
ADMIN_DISABLED_DETAIL = f"Quản trị bị tắt: chưa cấu hình ADMIN_KEY (>={ADMIN_KEY_MIN_LENGTH} ký tự)"


def get_configured_admin_key() -> Optional[str]:
    """Khoá quản trị từ ADMIN_KEY/ADMIN_SECRET; None (tắt) nếu thiếu hoặc quá ngắn.

    Không bao giờ dùng lại mật khẩu tài khoản thử làm khoá quản trị.
    """
    key = (os.getenv("ADMIN_KEY") or os.getenv("ADMIN_SECRET") or "").strip()
    if len(key) < ADMIN_KEY_MIN_LENGTH or key == settings.DEFAULT_TEST_PASSWORD:
        return None
    return key


def warn_if_admin_key_missing() -> None:
    """Gọi một lần lúc khởi động để người vận hành biết vì sao /admin trả 503."""
    if get_configured_admin_key() is None:
        logger.warning(
            "Quản trị bằng khoá đang TẮT: chưa đặt ADMIN_KEY (>= %d ký tự). Tạo khoá bằng "
            "`python -c \"import secrets;print(secrets.token_urlsafe(48))\"` rồi thêm ADMIN_KEY=<khoá> vào .env. "
            "Tài khoản có \"is_admin\": true (manage_users.py set-admin <user>) vẫn có quyền quản trị.",
            ADMIN_KEY_MIN_LENGTH,
        )


def _presented_admin_key(
    x_admin_key: Optional[str], authorization: Optional[str], session: Optional[Dict[str, Any]]
) -> Optional[str]:
    if x_admin_key and x_admin_key.strip():
        return x_admin_key.strip()
    # Bearer đã là token phiên hợp lệ thì không phải khoá quản trị
    if authorization and session is None:
        parts = authorization.strip().split(None, 1)
        if len(parts) == 2 and parts[0].lower() == "bearer" and parts[1].strip():
            return parts[1].strip()
    return None


async def verify_admin_access(
    session: Optional[Dict[str, Any]] = Depends(optional_user),
    x_admin_key: Optional[str] = Header(None, alias="X-Admin-Key"),
    authorization: Optional[str] = Header(None),
) -> bool:
    """Cho qua khi: token phiên của tài khoản is_admin, hoặc khoá quản trị (X-Admin-Key / Bearer)."""
    if session is not None and is_admin_record(find_user_for_token(load_users_cached(), session)):
        return True

    presented = _presented_admin_key(x_admin_key, authorization, session)
    if presented is None:
        if session is not None:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Tài khoản của bạn không có quyền quản trị.",
            )
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Mã quản trị (Admin Key) không hợp lệ hoặc bị thiếu.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    expected_key = get_configured_admin_key()
    if expected_key is None:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=ADMIN_DISABLED_DETAIL)
    if hmac.compare_digest(presented.encode("utf-8"), expected_key.encode("utf-8")):
        return True

    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Mã quản trị (Admin Key) không hợp lệ hoặc bị thiếu.",
        headers={"WWW-Authenticate": "Bearer"},
    )
