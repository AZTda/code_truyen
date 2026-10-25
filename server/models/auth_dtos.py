"""Schema yêu cầu/phản hồi của các route xác thực (tách khỏi routes/auth.py cho gọn)."""

from typing import Optional

from pydantic import BaseModel


class LoginRequest(BaseModel):
    email: str
    password: str


class RegisterRequest(BaseModel):
    display_name: str
    email: str
    password: str


class SocialLoginRequest(BaseModel):
    provider: str  # "google" or "apple"
    id_token: Optional[str] = None
    email: Optional[str] = None
    name: Optional[str] = None


class UserProfileResponse(BaseModel):
    id: str
    email: str
    display_name: str
    bio: str
    vip_tier: str
    avatar_url: str
    banner_url: str
    auth_provider: str
    is_logged_in: bool = True
    is_admin: bool = False


class AuthResponse(BaseModel):
    status: str
    message: str
    token: str
    user: UserProfileResponse


class RefreshResponse(BaseModel):
    token: str
    expires_at: int
    user: UserProfileResponse


class ChangePasswordRequest(BaseModel):
    old_password: str
    new_password: str
