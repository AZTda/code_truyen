"""Admin routes for TruyenCloud backend API - User Account Management."""

import asyncio
import logging
import re
from typing import List, Optional
from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field

from routes.admin_access import (  # noqa: F401  (re-export: nhiều route import từ routes.admin)
    ADMIN_DISABLED_DETAIL,
    ADMIN_KEY_MIN_LENGTH,
    get_configured_admin_key,
    verify_admin_access,
    warn_if_admin_key_missing,
)
from routes.auth import load_users, save_users, is_admin_record
from routes.admin_ops import router as admin_ops_router
from routes.admin_studio import router as admin_studio_router
from services.password import hash_password, new_user_id, DEFAULT_AVATAR, DEFAULT_BANNER
from services.user_data import purge_user_data

router = APIRouter(tags=["Admin"])
router.include_router(admin_studio_router)
router.include_router(admin_ops_router)
logger = logging.getLogger("truyencloud.admin")


class AdminUserResponse(BaseModel):
    id: str
    username: str
    email: str
    display_name: str
    bio: str
    vip_tier: str
    avatar_url: str
    banner_url: str
    auth_provider: str
    is_logged_in: bool = True
    total_reading_hours: float = 0.0
    total_chapters_read: int = 0
    total_words_translated: int = 0
    reading_streak_days: int = 1
    is_admin: bool = False


class CreateUserRequest(BaseModel):
    username: str = Field(..., min_length=3, max_length=30, description="Tên đăng nhập (chữ, số, dấu gạch dưới)")
    password: str = Field(..., min_length=6, description="Mật khẩu ít nhất 6 ký tự")
    display_name: Optional[str] = Field(None, max_length=50, description="Tên hiển thị")
    email: Optional[str] = Field(None, description="Địa chỉ email")
    vip_tier: Optional[str] = Field("✦ VIP Tinh Anh 2026", description="Hạng VIP")
    bio: Optional[str] = Field("Thành viên nội bộ TruyenCloud 🌌", description="Tiểu sử")


class UpdatePasswordRequest(BaseModel):
    new_password: str = Field(..., min_length=6, description="Mật khẩu mới ít nhất 6 ký tự")


@router.get("/v1/admin/users", response_model=List[AdminUserResponse])
async def list_admin_users(_: bool = Depends(verify_admin_access)):
    """Lấy danh sách toàn bộ tài khoản nội bộ (đã che giấu thông tin mật khẩu)."""
    users = load_users()
    result = []
    for uname, data in users.items():
        result.append(
            AdminUserResponse(
                id=data.get("id", f"user_{uname}"),
                username=uname,
                email=data.get("email", f"{uname}@truyencloud.vn"),
                display_name=data.get("display_name", uname),
                bio=data.get("bio", "Thành viên nội bộ TruyenCloud 🌌"),
                vip_tier=data.get("vip_tier", "✦ VIP Tinh Anh 2026"),
                avatar_url=data.get("avatar_url", DEFAULT_AVATAR),
                banner_url=data.get("banner_url", DEFAULT_BANNER),
                auth_provider=data.get("auth_provider", "email"),
                is_logged_in=data.get("is_logged_in", True),
                total_reading_hours=float(data.get("total_reading_hours", 0.0)),
                total_chapters_read=int(data.get("total_chapters_read", 0)),
                total_words_translated=int(data.get("total_words_translated", 0)),
                reading_streak_days=int(data.get("reading_streak_days", 1)),
                is_admin=is_admin_record(data),
            )
        )
    return result


@router.post("/v1/admin/users", response_model=AdminUserResponse, status_code=status.HTTP_201_CREATED)
async def create_admin_user(
    req: CreateUserRequest,
    _: bool = Depends(verify_admin_access)
):
    """Tạo mới tài khoản người dùng nội bộ TruyenCloud."""
    uname = req.username.strip().lower()
    if not re.match(r"^[a-zA-Z0-9_.-]{3,30}$", uname):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Tên đăng nhập chỉ được chứa chữ cái, số, dấu gạch dưới, gạch ngang, từ 3-30 ký tự."
        )

    users = load_users()
    if uname in users:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Tài khoản '{uname}' đã tồn tại trong hệ thống."
        )

    disp_name = (req.display_name or "").strip() or uname
    user_email = (req.email or "").strip() or f"{uname}@truyencloud.vn"
    vip_tier = (req.vip_tier or "").strip() or "✦ VIP Tinh Anh 2026"
    bio = (req.bio or "").strip() or "Thành viên nội bộ TruyenCloud 🌌"

    new_user = {
        # Id ngẫu nhiên: xoá rồi tạo lại cùng tên là một danh tính mới, phiên và dữ liệu cũ không thừa hưởng.
        "id": new_user_id(),
        "username": uname,
        "email": user_email,
        "password": hash_password(req.password),
        "display_name": disp_name,
        "bio": bio,
        "vip_tier": vip_tier,
        "avatar_url": DEFAULT_AVATAR,
        "banner_url": DEFAULT_BANNER,
        "auth_provider": "email",
        "is_logged_in": True,
        "total_reading_hours": 0.0,
        "total_chapters_read": 0,
        "total_words_translated": 0,
        "reading_streak_days": 1,
    }

    users[uname] = new_user
    save_users(users)

    return AdminUserResponse(
        id=new_user["id"],
        username=uname,
        email=user_email,
        display_name=disp_name,
        bio=bio,
        vip_tier=vip_tier,
        avatar_url=DEFAULT_AVATAR,
        banner_url=DEFAULT_BANNER,
        auth_provider="email",
        is_logged_in=True,
        total_reading_hours=0.0,
        total_chapters_read=0,
        total_words_translated=0,
        reading_streak_days=1,
    )


@router.put("/v1/admin/users/{username}/password")
async def update_admin_user_password(
    username: str,
    req: UpdatePasswordRequest,
    _: bool = Depends(verify_admin_access)
):
    """Đặt lại mật khẩu cho tài khoản người dùng."""
    uname = username.strip().lower()
    users = load_users()
    if uname not in users:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Không tìm thấy tài khoản '{uname}'."
        )

    users[uname]["password"] = hash_password(req.new_password)
    save_users(users)

    return {
        "status": "success",
        "message": f"Đã cập nhật mật khẩu mới thành công cho tài khoản '{uname}'."
    }


@router.delete("/v1/admin/users/{username}")
async def delete_admin_user(
    username: str,
    _: bool = Depends(verify_admin_access)
):
    """Xoá một tài khoản người dùng nội bộ cùng dữ liệu đồng bộ của họ (tiến độ, bookmark, từ vựng, log ôn)."""
    uname = username.strip().lower()
    users = load_users()
    if uname not in users:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Không tìm thấy tài khoản '{uname}'."
        )

    if len(users) <= 1:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Không thể xoá tài khoản duy nhất còn lại trong hệ thống."
        )

    removed = users.pop(uname)
    save_users(users)  # token của tài khoản này ngừng có hiệu lực ngay từ đây (current_user hỏi sổ tài khoản)
    purged = await asyncio.to_thread(purge_user_data, str(removed.get("id") or ""))

    return {
        "status": "success",
        "message": f"Đã xoá tài khoản '{uname}' thành công.",
        "purged_rows": purged,
    }
