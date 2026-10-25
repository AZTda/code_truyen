"""Quản trị kho truyện trên máy chủ MiniPC: xem kho, cào truyện mới vào ổ 1TB và tải dịch trước hàng loạt."""

import os
import json
import shutil
import logging
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException
from pydantic import BaseModel, Field

from database import db
from routes.admin_access import verify_admin_access
from routes.admin_novel_tools import router as admin_novel_tools_router
from routes.scrape import perform_scrape
from services.batch_translation_runner import batch_translation_runner
from services.glossary_store import glossary_store
from services.job_registry import job_registry
from services.library_index import library_index
from services.novel_catalog import novel_summary, _is_private, _recency_ms
from services.novel_chapter_stats import (
    get_chapter_counts,
    invalidate_chapter_counts,
    polish_novel_synopsis_vi,
)
from services.offline_mirror_service import mirror_cover_to_disk, offline_raw_runner
from services.storage_service import storage_service

logger = logging.getLogger("truyencloud.admin_novels")

router = APIRouter(prefix="/v1/admin/novels", tags=["Admin Novels"])
router.include_router(admin_novel_tools_router)

ADMIN_BATCH_OWNER = "admin_console"
ADMIN_MAX_BATCH_CHAPTERS = 3000


class AdminImportNovelRequest(BaseModel):
    source_url: str = Field(..., min_length=8, max_length=500)
    pretranslate_count: int = Field(default=0, ge=0, le=3000)
    from_chapter: int = Field(default=0, ge=0, le=100000)
    to_chapter: int = Field(default=0, ge=0, le=100000)


class AdminPretranslateRequest(BaseModel):
    from_chapter: int = Field(default=1, ge=1, le=100000)
    to_chapter: int = Field(default=20, ge=1, le=100000)


def _count_novel_chapters_on_disk(novel_id: str) -> Dict[str, int]:
    """Đếm số chương đã tải bản gốc và số chương đã dịch AI trên ổ đĩa (có đệm theo mtime)."""
    return get_chapter_counts(novel_id)


def _active_job_for_novel(novel_id: str) -> Optional[Dict[str, Any]]:
    """Kiểm tra xem truyện có đang chạy tác vụ dịch ngầm không."""
    for (uid, nid), active in list(batch_translation_runner._active.items()):
        if nid == novel_id:
            job = job_registry.get_for_owner(active.job_id, uid)
            if job:
                chs = sorted(active.chapters) if active.chapters else [1]
                total = max(1, len(chs))
                prog = float(job.get("progress", 0.0) if isinstance(job, dict) else getattr(job, "progress", 0.0))
                raw_status = job.get("status", "running") if isinstance(job, dict) else getattr(job, "status", "running")
                msg = job.get("message", "") if isinstance(job, dict) else getattr(job, "message", "")
                done = int(round(prog * total))
                return {
                    "job_id": active.job_id,
                    "status": "running" if raw_status in ("queued", "running") else raw_status,
                    "from_chapter": chs[0],
                    "to_chapter": chs[-1],
                    "total": total,
                    "completed": done,
                    "skipped": 0,
                    "current_chapter": chs[min(done, total - 1)],
                    "message": msg,
                }
    raw_job = offline_raw_runner.active_for(novel_id)
    if raw_job:
        return raw_job
    return None


def _start_pretranslate_job(
    novel_id: str, from_chapter: int, to_chapter: int, bg_tasks: BackgroundTasks
) -> Dict[str, Any]:
    if from_chapter > to_chapter:
        raise HTTPException(status_code=400, detail="Chương bắt đầu phải nhỏ hơn hoặc bằng chương kết thúc.")
    if to_chapter - from_chapter >= ADMIN_MAX_BATCH_CHAPTERS:
        raise HTTPException(status_code=400, detail=f"Mỗi đợt dịch trước tối đa {ADMIN_MAX_BATCH_CHAPTERS} chương.")

    chapters = list(range(from_chapter, to_chapter + 1))
    active = batch_translation_runner._active.get((ADMIN_BATCH_OWNER, novel_id))
    if active:
        job = job_registry.get_for_owner(active.job_id, ADMIN_BATCH_OWNER)
        return {
            "status": "running",
            "job_id": active.job_id,
            "queued_chapters": len(active.chapters),
            "job": job,
            "message": "Truyện này đang có đợt dịch ngầm đang chạy.",
        }

    job = job_registry.create("batch_translation", ADMIN_BATCH_OWNER)
    batch_translation_runner.activate(ADMIN_BATCH_OWNER, novel_id, job.id, chapters)
    bg_tasks.add_task(
        batch_translation_runner.run,
        ADMIN_BATCH_OWNER,
        novel_id,
        chapters,
        [],
        job.id,
        False,
    )
    return {
        "status": "queued",
        "job_id": job.id,
        "queued_chapters": len(chapters),
        "job": job_registry.get_for_owner(job.id, ADMIN_BATCH_OWNER),
        "message": f"Đã xếp hàng tải & dịch trước {len(chapters)} chương ({from_chapter} → {to_chapter}).",
    }


@router.get("")
async def list_library_novels(_: bool = Depends(verify_admin_access)):
    """Liệt kê toàn bộ truyện đang lưu trong kho MiniPC kèm tiến độ chương đã dịch."""
    entries = library_index.get_entries()
    items: List[Dict[str, Any]] = []
    for entry in entries:
        novel = entry.get("novel") or {}
        if not novel.get("title") or _is_private(novel):
            continue
        summary = novel_summary(novel, title_vi=entry.get("title_vi"))
        nid = summary["id"]
        counts = _count_novel_chapters_on_disk(nid)
        summary["cached_chapters"] = counts["cached_chapters"]
        summary["translated_chapters"] = counts["translated_chapters"]
        summary["glossary_count"] = glossary_store.count(nid)
        summary["overnight_enabled"] = bool(novel.get("overnight_enabled"))
        summary["active_job"] = _active_job_for_novel(nid)
        summary["updated_ms"] = _recency_ms(novel)
        items.append(summary)

    items.sort(key=lambda x: x.get("updated_ms", 0), reverse=True)
    return {"items": items, "novels": items, "total": len(items)}


@router.post("/import")
async def import_novel_to_library(
    req: AdminImportNovelRequest,
    bg_tasks: BackgroundTasks,
    _: bool = Depends(verify_admin_access),
):
    """Cào truyện từ URL nguồn lưu thẳng vào kho 1TB trên MiniPC và tuỳ chọn dịch trước chương."""
    detail = await perform_scrape(req.source_url)
    # Tự động làm mượt văn án tiếng Trung sang tiếng Việt khi nhập kho
    if detail.description:
        polished_desc = polish_novel_synopsis_vi(detail.description)
        if polished_desc and polished_desc != detail.description:
            detail.description = polished_desc
            info = storage_service.get_novel_info(detail.id) or {}
            if info:
                info["description"] = polished_desc
                storage_service.save_novel_info(detail.id, info)
            try:
                db.table("novels").update({"description": polished_desc}).eq("id", detail.id).execute()
            except Exception:
                pass
    if detail.cover_url:
        await mirror_cover_to_disk(detail.cover_url)
    library_index.invalidate()
    job_info = None
    target_to = req.to_chapter if req.to_chapter > 0 else req.pretranslate_count
    if target_to and target_to > 0:
        start_ch = max(1, req.from_chapter or 1)
        end_ch = min(max(start_ch, target_to), max(1, detail.total_chapters or target_to))
        if end_ch - start_ch >= ADMIN_MAX_BATCH_CHAPTERS:
            end_ch = start_ch + ADMIN_MAX_BATCH_CHAPTERS - 1
        job_info = _start_pretranslate_job(detail.id, start_ch, end_ch, bg_tasks)

    counts = _count_novel_chapters_on_disk(detail.id)
    return {
        "novel": {
            "id": detail.id,
            "title": detail.title,
            "title_vi": detail.title_vi or detail.title,
            "author": detail.author or "",
            "cover_url": detail.cover_url or "",
            "source_id": detail.source_id,
            "source_url": detail.source_url,
            "total_chapters": detail.total_chapters,
            "status": detail.status,
            "cached_chapters": counts["cached_chapters"],
            "translated_chapters": counts["translated_chapters"],
            "glossary_count": glossary_store.count(detail.id),
            "active_job": job_info.get("job") if job_info else _active_job_for_novel(detail.id),
        },
        "pretranslate": job_info,
    }


@router.post("/{novel_id}/pretranslate")
async def pretranslate_novel_chapters(
    novel_id: str,
    req: AdminPretranslateRequest,
    bg_tasks: BackgroundTasks,
    _: bool = Depends(verify_admin_access),
):
    """Kích hoạt tải bản gốc và dịch AI ngầm một dải chương cho truyện đã có trong kho."""
    info = storage_service.get_novel_info(novel_id)
    if not info:
        res = db.table("novels").select("*").eq("id", novel_id).single().execute()
        info = res.data
    if not info:
        raise HTTPException(status_code=404, detail=f"Không tìm thấy truyện '{novel_id}' trong kho.")
    return _start_pretranslate_job(novel_id, req.from_chapter, req.to_chapter, bg_tasks)


@router.get("/jobs/{job_id}")
async def get_admin_pretranslate_job(job_id: str, _: bool = Depends(verify_admin_access)):
    """Xem tiến độ tác vụ dịch trước do quản trị viên khởi tạo."""
    job = job_registry.get_for_owner(job_id, ADMIN_BATCH_OWNER)
    if not job:
        raise HTTPException(status_code=404, detail="Không tìm thấy tác vụ dịch hoặc tác vụ đã hết hạn.")
    return job


@router.delete("/{novel_id}")
async def delete_novel_from_library(novel_id: str, _: bool = Depends(verify_admin_access)):
    """Xoá một truyện khỏi kho lưu trữ trên MiniPC."""
    batch_translation_runner.cancel("", novel_id)
    offline_raw_runner.cancel(novel_id)
    novel_dir = storage_service.get_novel_dir(novel_id)
    removed_disk = False
    if os.path.isdir(novel_dir):
        shutil.rmtree(novel_dir, ignore_errors=True)
        removed_disk = True
    try:
        db.table("chapters").delete().eq("novel_id", novel_id).execute()
    except Exception:
        pass
    try:
        db.table("novels").delete().eq("id", novel_id).execute()
    except Exception:
        pass
    invalidate_chapter_counts(novel_id)
    library_index.invalidate()
    return {"status": "deleted", "novel_id": novel_id, "removed_disk": removed_disk}
