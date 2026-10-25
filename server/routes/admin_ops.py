"""Các API Vận hành & Công cụ Nâng cao cho Trung tâm Quản trị Web (`/admin`):
1. Phát hành OTA & Tải lên trực tiếp tệp `truyencloud-latest.apk`
2. Giám sát & Dọn dẹp Bộ đệm Ổ 1TB (`CacheJanitor`, `TitleDict`, `SQLite WAL Checkpoint`)
3. Phòng Thử nghiệm Dịch AI (`Gemini CLI`, `Qwen3-4B Offline`, `Title MT`)
4. Điều phối `AutoUpdater` (Quét chương mới tự động) & Thao tác Hàng loạt Kho truyện (`Bulk Actions`)
5. Sao lưu & Xuất toàn bộ Cấu hình / Hồ sơ Độc giả / Bảng Thuật ngữ (`Backup JSON`)
"""

import asyncio
import hashlib
import os
import tempfile
import time
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

import database
from database import db
from routes.admin_access import verify_admin_access
from routes.app_update import STATIC_APK_PATH, _load_version_data, _save_version_data
from routes.auth import load_users
from routes.settings import load_profile
from services.auto_updater import auto_updater
from services.cache_janitor import cache_janitor, sweep_all, usage_report
from services.glossary_store import glossary_store
from services.library_index import library_index
from services.local_chapter_mt import translate_chapter_offline
from services.model_catalog import model_catalog_service
from services.overnight_translator import overnight_translator
from services.storage_service import storage_service
from services.title_cache_store import title_cache
from services.title_dict import title_dict
from services.title_mt_client import title_mt
from services.title_refiner import title_refiner
from services.translation_worker import translation_worker

router = APIRouter(tags=["Admin Operations"])

MAX_APK_UPLOAD_BYTES = 250 * 1024 * 1024  # 250 MB


class PlaygroundTranslateRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=4000)
    engine: str = Field(default="qwen", max_length=32)


class BulkNovelActionRequest(BaseModel):
    action: str = Field(..., max_length=40)
    novel_ids: List[str] = Field(default_factory=list, max_length=500)


@router.post("/v1/admin/app/upload-apk")
async def upload_android_apk(
    file: UploadFile = File(...),
    _: bool = Depends(verify_admin_access),
) -> Dict[str, Any]:
    """Tải lên tệp APK mới vào `/static/truyencloud-latest.apk`, tự động tính SHA-256 và dung lượng."""
    fname = (file.filename or "").strip().lower()
    if not fname.endswith(".apk"):
        raise HTTPException(status_code=400, detail="Chỉ chấp nhận tệp định dạng .apk.")

    os.makedirs(os.path.dirname(STATIC_APK_PATH), exist_ok=True)
    digest = hashlib.sha256()
    total_bytes = 0
    fd, temp_path = tempfile.mkstemp(
        prefix=".truyencloud-apk-", suffix=".tmp", dir=os.path.dirname(STATIC_APK_PATH)
    )
    try:
        with os.fdopen(fd, "wb") as out:
            while True:
                chunk = await file.read(1024 * 1024)
                if not chunk:
                    break
                total_bytes += len(chunk)
                if total_bytes > MAX_APK_UPLOAD_BYTES:
                    raise HTTPException(status_code=413, detail="Tệp APK vượt quá giới hạn 250 MB.")
                digest.update(chunk)
                out.write(chunk)
            out.flush()
            os.fsync(out.fileno())
        os.replace(temp_path, STATIC_APK_PATH)
    finally:
        if os.path.exists(temp_path):
            try:
                os.unlink(temp_path)
            except OSError:
                pass

    sha256_hex = digest.hexdigest()
    info = _load_version_data()
    info["file_size_bytes"] = total_bytes
    info["sha256"] = sha256_hex
    info["download_url"] = "/static/truyencloud-latest.apk"
    info["release_date"] = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    _save_version_data(info)
    return {
        "status": "uploaded",
        "file_size_bytes": total_bytes,
        "sha256": sha256_hex,
        "version": info,
        "message": f"Đã tải lên truyencloud-latest.apk ({round(total_bytes / 1048576, 1)} MB) & cập nhật SHA-256.",
    }


@router.get("/v1/admin/system/caches")
async def get_system_caches_overview(_: bool = Depends(verify_admin_access)) -> Dict[str, Any]:
    """Báo cáo chi tiết dung lượng các thư mục bộ đệm trên ổ 1TB và trạng thái Từ điển Tên truyện."""
    title_dict.get()
    return {
        "directories": usage_report(),
        "janitor_running": cache_janitor.is_running,
        "last_sweep": cache_janitor.last_run,
        "last_checkpoint": cache_janitor.last_checkpoint,
        "titles": {
            "dictionary": title_dict.status(),
            "queue": title_refiner.status(),
            "cache": title_cache.counts(),
        },
    }


@router.post("/v1/admin/system/sweep-cache")
async def trigger_cache_sweep(_: bool = Depends(verify_admin_access)) -> Dict[str, Any]:
    """Dọn dẹp bộ đệm quá hạn (TTL + LRU) và gộp SQLite WAL ngay lập tức."""
    sweep_res = await asyncio.to_thread(sweep_all, False)
    cp_res = await asyncio.to_thread(database.checkpoint)
    now_iso = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    cache_janitor.last_run = now_iso
    cache_janitor.last_result = sweep_res
    cache_janitor.last_checkpoint = cp_res

    freed_total = sum(int(v.get("freed_bytes") or 0) for v in sweep_res.values() if isinstance(v, dict))
    removed_total = sum(
        int(v.get("removed_ttl") or 0) + int(v.get("removed_lru") or 0)
        for v in sweep_res.values()
        if isinstance(v, dict)
    )
    return {
        "status": "swept",
        "removed_files": removed_total,
        "freed_mb": round(freed_total / (1024 * 1024), 2),
        "sweep_result": sweep_res,
        "directories": usage_report(),
        "message": f"Đã dọn {removed_total} mục quá hạn (giải phóng {round(freed_total / 1048576, 2)} MB) & gộp SQLite WAL.",
    }


@router.post("/v1/admin/ai/playground-translate")
async def playground_translate(
    req: PlaygroundTranslateRequest,
    _: bool = Depends(verify_admin_access),
) -> Dict[str, Any]:
    """Dịch thử đoạn văn tiếng Trung trên Web Admin bằng tầng AI tuỳ chọn."""
    text = req.text.strip()
    engine = req.engine.strip().lower()
    t0 = time.perf_counter()
    profile = load_profile()

    if engine == "title_mt":
        out = await title_mt.translate_title(text)
        used = "Qwen2.5-1.5B (Title MT)"
        if not out:
            d = title_dict.get()
            out = d.convert_title(text) if d else ""
            used = "Từ điển Hán-Việt nội bộ"
    elif engine == "gemini":
        out = await translation_worker.translate(
            content=text,
            target_language="vi",
            custom_prompt=profile.get("custom_prompt"),
            genre=profile.get("genre"),
            sino_vietnamese_ratio=profile.get("sino_vietnamese_ratio"),
        )
        used = f"Gemini Antigravity ({model_catalog_service.get_active_model()})"
    else:
        out = await translate_chapter_offline(text)
        used = "Qwen3-4B-Q4_K_M.gguf (Local Offline)"

    elapsed_ms = int((time.perf_counter() - t0) * 1000)
    if not out or not out.strip():
        raise HTTPException(
            status_code=502,
            detail=f"Động cơ '{engine}' chưa trả về bản dịch hợp lệ. Vui lòng thử tầng AI khác.",
        )
    return {
        "status": "ok",
        "engine_used": used,
        "elapsed_ms": elapsed_ms,
        "translated_text": out.strip(),
    }


@router.get("/v1/admin/novels/auto-updater")
async def get_auto_updater_status(_: bool = Depends(verify_admin_access)) -> Dict[str, Any]:
    """Trạng thái tiến trình AutoUpdater (quét chương mới định kỳ mỗi 30 phút)."""
    return auto_updater.get_status()


@router.post("/v1/admin/novels/auto-updater/trigger")
async def trigger_auto_updater_now(_: bool = Depends(verify_admin_access)) -> Dict[str, Any]:
    """Kích hoạt quét chương mới ngay lập tức cho các bộ truyện trong kho."""
    asyncio.create_task(auto_updater.check_all_novels())
    return {
        "status": "triggered",
        "message": "Đã kích hoạt quét chương mới ngầm cho các bộ truyện trong kho MiniPC.",
        "updater": auto_updater.get_status(),
    }


@router.post("/v1/admin/novels/bulk-action")
async def run_bulk_novel_action(
    req: BulkNovelActionRequest,
    _: bool = Depends(verify_admin_access),
) -> Dict[str, Any]:
    """Bật hoặc tắt Dịch Ngầm Ban Đêm hàng loạt cho danh sách truyện đang lọc."""
    act = req.action.strip().lower()
    ids = [str(nid).strip() for nid in req.novel_ids if str(nid).strip()]
    if act not in ("enable_overnight", "disable_overnight"):
        raise HTTPException(status_code=400, detail="Hành động hàng loạt không hợp lệ.")

    enabled_flag = act == "enable_overnight"
    updated = 0
    for nid in ids[:200]:
        info = storage_service.get_novel_info(nid)
        if not info:
            continue
        info["overnight_enabled"] = enabled_flag
        storage_service.save_novel_info(nid, info)
        updated += 1

    if enabled_flag and updated > 0:
        overnight_translator.start()
        overnight_translator.wake_up()
    library_index.invalidate()
    return {
        "status": "ok",
        "updated_count": updated,
        "overnight_enabled": enabled_flag,
        "message": (
            f"Đã bật Dịch Ngầm Ban Đêm cho {updated} bộ truyện."
            if enabled_flag
            else f"Đã tắt Dịch Ngầm Ban Đêm cho {updated} bộ truyện."
        ),
    }


@router.get("/v1/admin/backup/export")
async def export_admin_backup(_: bool = Depends(verify_admin_access)) -> JSONResponse:
    """Xuất bản sao lưu JSON toàn diện (Tài khoản, Cấu hình AI, Phiên bản App, Bảng Thuật ngữ & Kho truyện)."""
    users_raw = load_users()
    users_sanitized = {}
    for uname, rec in users_raw.items():
        if isinstance(rec, dict):
            users_sanitized[uname] = {k: v for k, v in rec.items() if k not in ("password", "password_hash")}

    novels = storage_service.list_all_novels()
    glossaries: Dict[str, Any] = {}
    for nv in novels:
        nid = str(nv.get("id") or "")
        if nid:
            terms = glossary_store.load(nid)
            if terms:
                glossaries[nid] = [{"source": t.source, "target": t.target} for t in terms]

    payload = {
        "exported_at": datetime.now(timezone.utc).isoformat(),
        "server": "TruyenCloud MiniPC",
        "accounts_count": len(users_sanitized),
        "accounts": users_sanitized,
        "ai_profile": load_profile(),
        "ai_catalog": model_catalog_service.get_catalog(auto_check=False),
        "app_version": _load_version_data(),
        "library_summary": {
            "total_novels": len(novels),
            "overnight_enabled_ids": [n.get("id") for n in novels if n.get("overnight_enabled")],
        },
        "novel_glossaries": glossaries,
    }
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M")
    return JSONResponse(
        content=payload,
        headers={"Content-Disposition": f'attachment; filename="truyencloud-backup-{stamp}.json"'},
    )
