"""Shared password hashing helpers and default profile asset constants.

Single source of truth for routes/auth.py, routes/admin.py, and manage_users.py.
Stored hash format (must never change without a migration):
    pbkdf2:sha256:100000$<salt_hex>$<key_hex>
"""

import hashlib
import secrets
import uuid

from config import settings

DEFAULT_AVATAR = f"{settings.BASE_URL}/static/user_avatar.jpg"
DEFAULT_BANNER = f"{settings.BASE_URL}/static/user_banner.jpg"


def new_user_id() -> str:
    """Id tài khoản mới, không đoán được. Id `user_<tên>` cũ khiến tài khoản bị xoá rồi tạo lại cùng tên
    thừa hưởng phiên và dữ liệu của người trước; id ngẫu nhiên biến nó thành một danh tính mới."""
    return f"user_{uuid.uuid4().hex}"


def hash_password(password: str) -> str:
    """Hash password using PBKDF2-HMAC-SHA256 with a unique random salt."""
    salt = secrets.token_hex(16)
    key = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt.encode("utf-8"), 100000)
    return f"pbkdf2:sha256:100000${salt}${key.hex()}"


def verify_password(stored_password: str, provided_password: str) -> bool:
    """Verify a password against the PBKDF2 format used by stored accounts."""
    if not stored_password or not provided_password:
        return False
    if stored_password.startswith("pbkdf2:sha256:"):
        try:
            parts = stored_password.split("$")
            salt = parts[1]
            key_hex = parts[2]
            computed = hashlib.pbkdf2_hmac("sha256", provided_password.encode("utf-8"), salt.encode("utf-8"), 100000).hex()
            return secrets.compare_digest(key_hex, computed)
        except Exception:
            return False
    return False
