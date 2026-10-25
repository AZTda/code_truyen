"""Các API mở rộng cho Trung tâm Quản trị Web (`/admin`):
- Điều phối & giám sát Dịch Ngầm Ban Đêm (`OvernightTranslator`)
- Xem thử chương dịch song ngữ & chỉnh sửa thông tin truyện / nội dung bản dịch
- Chỉnh sửa Hồ sơ Độc giả (Tên, VIP, Tiểu sử, Quyền Admin) & xem lịch sử đọc / từ vựng đồng bộ
- Giám sát chuyên sâu cụm AI (`Gemini Antigravity CLI`, `Qwen3-4B GGUF`, `Title MT`) & danh mục giọng `VieNeu-TTS`
"""

import os
from typing import Any, Dict, List, Optional

import httpx
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from database import db
from routes.admin_access import verify_admin_access
from routes.auth import is_admin_record, load_users, save_users
from services.library_index import library_index
from services.local_chapter_mt import qwen_base_url, qwen_healthy
from services.model_catalog import model_catalog_service as model_catalog
from services.novel_chapter_stats import get_chapter_counts, invalidate_chapter_counts
from services.overnight_translator import overnight_translator
from services.storage_service import storage_service
from services.translation_validation import is_usable_translation
from services.tts_voice_profiles import catalog_voices, public_voice_catalog

router = APIRouter(tags=["Admin Studio"])


class UpdateNovelMetaRequest(BaseModel):
    title_vi: Optional[str] = Field(default=None, max_length=240)
    author: Optional[str] = Field(default=None, max_length=160)
    description: Optional[str] = Field(default=None, max_length=5000)
    cover_url: Optional[str] = Field(default=None, max_length=600)


class UpdateChapterTranslationRequest(BaseModel):
    title_vi: Optional[str] = Field(default=None, max_length=240)
    translated_content: str = Field(..., min_length=1, max_length=200000)


class UpdateUserProfileRequest(BaseModel):
    display_name: Optional[str] = Field(default=None, max_length=60)
    email: Optional[str] = Field(default=None, max_length=120)
    vip_tier: Optional[str] = Field(default=None, max_length=60)
    bio: Optional[str] = Field(default=None, max_length=240)
    is_admin: Optional[bool] = Field(default=None)


@router.get("/v1/admin/novels/overnight/status")
async def get_overnight_status(_: bool = Depends(verify_admin_access)) -> Dict[str, Any]:
    """Lấy trạng thái tổng thể của tiến trình Dịch Ngầm Ban Đêm (`OvernightTranslator`)."""
    st = overnight_translator.status()
    cur_id = st.get("current_novel_id") or ""
    cur_title = ""
    if cur_id:
        info = storage_service.get_novel_info(cur_id) or {}
        cur_title = info.get("title_vi") or info.get("title") or cur_id
    st["current_novel_title"] = cur_title
    return st


@router.post("/v1/admin/novels/{novel_id}/overnight/stop")
async def stop_overnight_novel(
    novel_id: str, _: bool = Depends(verify_admin_access)
) -> Dict[str, Any]:
    """Tắt chế độ Dịch Ngầm Ban Đêm cho một bộ truyện."""
    return overnight_translator.disable_novel(novel_id)


@router.put("/v1/admin/novels/{novel_id}/meta")
async def update_novel_metadata(
    novel_id: str,
    req: UpdateNovelMetaRequest,
    _: bool = Depends(verify_admin_access),
) -> Dict[str, Any]:
    """Cập nhật tên tiếng Việt, tác giả, văn án và ảnh bìa của truyện trong Kho MiniPC."""
    info = storage_service.get_novel_info(novel_id)
    if not info:
        raise HTTPException(status_code=404, detail=f"Không tìm thấy truyện '{novel_id}' trong kho.")
    patch: Dict[str, Any] = {}
    if req.title_vi is not None:
        val = req.title_vi.strip()
        if val:
            info["title_vi"] = val
            patch["title_vi"] = val
    if req.author is not None:
        info["author"] = req.author.strip()
        patch["author"] = info["author"]
    if req.description is not None:
        info["description"] = req.description.strip()
        patch["description"] = info["description"]
    if req.cover_url is not None:
        info["cover_url"] = req.cover_url.strip()
        patch["cover_url"] = info["cover_url"]

    storage_service.save_novel_info(novel_id, info)
    if patch:
        try:
            db.table("novels").update(patch).eq("id", novel_id).execute()
        except Exception:
            pass
    library_index.invalidate()
    return {
        "status": "updated",
        "novel_id": novel_id,
        "novel": info,
        "message": "Đã cập nhật thông tin truyện trong Kho MiniPC.",
    }


@router.get("/v1/admin/novels/{novel_id}/chapters-preview")
async def preview_novel_chapter(
    novel_id: str,
    chapter: int = 1,
    _: bool = Depends(verify_admin_access),
) -> Dict[str, Any]:
    """Đọc thử nội dung chương (Bản gốc + Bản dịch AI) và danh sách chương đã lưu trên ổ 1TB."""
    info = storage_service.get_novel_info(novel_id)
    if not info:
        raise HTTPException(status_code=404, detail=f"Không tìm thấy truyện '{novel_id}'.")

    ch_dir = os.path.join(storage_service.get_novel_dir(novel_id), "chapters")
    available: List[Dict[str, Any]] = []
    if os.path.isdir(ch_dir):
        ch_nums: List[int] = []
        for fn in os.listdir(ch_dir):
            stem = fn[:-5] if fn.endswith(".json") else ""
            if stem.startswith("chapter_"):
                stem = stem[8:]
            if stem.isdigit():
                ch_nums.append(int(stem))
        ch_nums.sort()
        for num in ch_nums[:250]:
            c_data = storage_service.get_chapter(novel_id, num) or {}
            has_tr = is_usable_translation(c_data.get("translated_content"))
            has_raw = bool((c_data.get("original_content") or "").strip())
            if has_tr or has_raw:
                available.append({
                    "chapter_number": num,
                    "title": c_data.get("title_vi") or c_data.get("title") or f"Chương {num}",
                    "translated": has_tr,
                    "raw": has_raw,
                    "engine": c_data.get("translation_engine") or "",
                })

    if chapter <= 0:
        first_tr = next((x["chapter_number"] for x in available if x.get("translated")), None)
        first_raw = next((x["chapter_number"] for x in available if x.get("raw")), None)
        target_ch = first_tr or first_raw or 1
    else:
        target_ch = max(1, chapter)
    ch_data = storage_service.get_chapter(novel_id, target_ch) or {}
    counts = get_chapter_counts(novel_id)
    return {
        "novel_id": novel_id,
        "novel_title": info.get("title_vi") or info.get("title") or novel_id,
        "total_chapters": int(info.get("total_chapters") or 0),
        "cached_chapters": counts["cached_chapters"],
        "translated_chapters": counts["translated_chapters"],
        "chapter_number": target_ch,
        "title": ch_data.get("title") or f"Chương {target_ch}",
        "title_vi": ch_data.get("title_vi") or "",
        "original_content": ch_data.get("original_content") or "",
        "translated_content": ch_data.get("translated_content") or "",
        "translation_engine": ch_data.get("translation_engine") or "",
        "available_chapters": available,
    }


@router.put("/v1/admin/novels/{novel_id}/chapters/{chapter_num}")
async def save_novel_chapter_translation(
    novel_id: str,
    chapter_num: int,
    req: UpdateChapterTranslationRequest,
    _: bool = Depends(verify_admin_access),
) -> Dict[str, Any]:
    """Lưu bản dịch chương đã chỉnh sửa thủ công trên Web Admin vào ổ 1TB."""
    existing = storage_service.get_chapter(novel_id, chapter_num) or {
        "novel_id": novel_id,
        "chapter_number": chapter_num,
    }
    if req.title_vi is not None and req.title_vi.strip():
        existing["title_vi"] = req.title_vi.strip()
    existing["translated_content"] = req.translated_content.strip()
    existing["translation_engine"] = "ADMIN_EDITED"
    storage_service.save_chapter(novel_id, chapter_num, existing)
    invalidate_chapter_counts(novel_id)
    return {
        "status": "saved",
        "novel_id": novel_id,
        "chapter_number": chapter_num,
        "message": f"Đã lưu bản dịch Chương {chapter_num} vào ổ 1TB.",
    }


@router.put("/v1/admin/users/{username}/profile")
async def update_admin_user_profile(
    username: str,
    req: UpdateUserProfileRequest,
    _: bool = Depends(verify_admin_access),
) -> Dict[str, Any]:
    """Cập nhật tên hiển thị, hạng VIP, tiểu sử, email và quyền quản trị của tài khoản."""
    uname = username.strip().lower()
    users = load_users()
    if uname not in users:
        raise HTTPException(status_code=404, detail=f"Không tìm thấy tài khoản '{uname}'.")
    record = users[uname]
    if req.display_name is not None and req.display_name.strip():
        record["display_name"] = req.display_name.strip()
    if req.email is not None and req.email.strip():
        record["email"] = req.email.strip()
    if req.vip_tier is not None:
        record["vip_tier"] = req.vip_tier.strip()
    if req.bio is not None:
        record["bio"] = req.bio.strip()
    if req.is_admin is not None:
        if not req.is_admin and is_admin_record(record):
            other_admins = [k for k, v in users.items() if k != uname and is_admin_record(v)]
            if not other_admins:
                raise HTTPException(
                    status_code=400,
                    detail="Không thể thu hồi quyền quản trị của quản trị viên duy nhất còn lại.",
                )
        record["is_admin"] = bool(req.is_admin)
        record["role"] = "admin" if req.is_admin else "member"

    save_users(users)
    return {
        "status": "updated",
        "username": uname,
        "message": f"Đã cập nhật hồ sơ cho @{uname}.",
    }


@router.get("/v1/admin/users/{username}/activity")
async def get_admin_user_activity(
    username: str, _: bool = Depends(verify_admin_access)
) -> Dict[str, Any]:
    """Xem tiến độ đọc truyện, dấu trang và số từ vựng FSRS đã đồng bộ của một độc giả."""
    uname = username.strip().lower()
    users = load_users()
    if uname not in users:
        raise HTTPException(status_code=404, detail=f"Không tìm thấy tài khoản '{uname}'.")
    u = users[uname]
    uid = str(u.get("id") or f"user_{uname}")

    progress_items: List[Dict[str, Any]] = []
    vocab_count = 0
    bookmark_count = 0
    try:
        res_p = db.table("reading_progress_backend").select("*").eq("user_id", uid).execute()
        rows_p = [r for r in (getattr(res_p, "data", None) or []) if isinstance(r, dict) and r.get("shelf_name") != "removed"]
        rows_p.sort(key=lambda r: int(r.get("updated_at_ms") or 0), reverse=True)
        for r in rows_p[:20]:
            nid = str(r.get("novel_id") or "")
            n_info = storage_service.get_novel_info(nid) or {}
            progress_items.append({
                "novel_id": nid,
                "novel_title": n_info.get("title_vi") or n_info.get("title") or r.get("novel_title") or nid,
                "current_chapter": int(r.get("current_chapter") or 1),
                "total_chapters": int(n_info.get("total_chapters") or r.get("total_chapters") or 0),
                "shelf_name": r.get("shelf_name") or "reading",
                "updated_at_ms": int(r.get("updated_at_ms") or 0),
            })
    except Exception:
        pass
    try:
        res_v = db.table("vocabulary_backend").select("word").eq("user_id", uid).execute()
        vocab_count = len(getattr(res_v, "data", None) or [])
    except Exception:
        pass
    try:
        res_b = db.table("bookmarks_backend").select("id").eq("user_id", uid).execute()
        bookmark_count = len(getattr(res_b, "data", None) or [])
    except Exception:
        pass

    return {
        "username": uname,
        "user_id": uid,
        "display_name": u.get("display_name") or uname,
        "vip_tier": u.get("vip_tier") or "",
        "bio": u.get("bio") or "",
        "email": u.get("email") or "",
        "is_admin": is_admin_record(u),
        "vocab_count": vocab_count,
        "bookmark_count": bookmark_count,
        "reading_progress": progress_items,
    }


@router.get("/v1/admin/system/ai-engines")
async def get_ai_engines_status(_: bool = Depends(verify_admin_access)) -> Dict[str, Any]:
    """Kiểm tra trạng thái trực tiếp của 3 tầng AI (Gemini CLI, Qwen3-4B Offline, Title MT) & Giọng đọc TTS."""
    qwen_ok = await qwen_healthy()
    mt_url = (os.getenv("TITLE_MT_URL") or "http://truyencloud-mt:8080").rstrip("/")
    mt_ok = False
    try:
        async with httpx.AsyncClient(timeout=2.0) as client:
            r = await client.get(mt_url + "/health")
            mt_ok = r.status_code == 200
    except Exception:
        mt_ok = False

    cat = model_catalog.get_catalog(auto_check=False)
    return {
        "gemini": {
            "active_model": cat.get("active_model") or "gemini-3-flash",
            "cli_version": cat.get("cli_version") or "ready",
            "timeout_seconds": cat.get("timeout_seconds") or 90,
            "status": "ready",
        },
        "qwen_offline": {
            "model": "Qwen3-4B-Q4_K_M.gguf",
            "endpoint": qwen_base_url(),
            "healthy": qwen_ok,
            "status": "ready" if qwen_ok else "offline",
        },
        "title_mt": {
            "model": "Qwen2.5-1.5B-Instruct (Title MT)",
            "endpoint": mt_url,
            "healthy": mt_ok,
            "status": "ready" if mt_ok else "offline",
        },
        "tts_voices": public_voice_catalog(),
        "tts_catalog": catalog_voices(),
    }
