"""Các công cụ mở rộng cho Quản lý Kho Truyện 1TB trên Admin:
- Dừng tác vụ dịch ngầm (`POST /{novel_id}/cancel-job`)
- Tạo sẵn Audio VieNeu-TTS vào ổ 1TB (`POST /{novel_id}/prerender-tts`)
- Xuất sách điện tử `.epub` (`GET /{novel_id}/export.epub`)
- Xem & sửa bảng thuật ngữ / tên nhân vật (`GET/PUT /{novel_id}/glossary`)
"""

import asyncio
from typing import Any, Dict, List

from fastapi import APIRouter, Depends, HTTPException, Response
from pydantic import BaseModel, Field

from models.translate import TermItem
from routes.admin_access import verify_admin_access
from services import tts_cache
from services.batch_translation_runner import batch_translation_runner
from services.epub_exporter import build_novel_epub
from services.glossary_store import glossary_store
from services.library_harvester import library_harvester
from services.offline_mirror_service import offline_raw_runner
from services.tts_prerender import PRIORITY_BACKGROUND, render_queue
from services.tts_prerender_plan import build_chapter_plan, enqueue_chapter, summarize

router = APIRouter()


class AdminHarvesterStartRequest(BaseModel):
    pack: str = Field(default="", max_length=60)
    pack_id: str = Field(default="all", max_length=60)
    max_novels: int = Field(default=50, ge=1, le=500)
    raw_chapters: int = Field(default=30, ge=0, le=200)
    pretranslate_chapters: int = Field(default=5, ge=0, le=50)


class AdminDownloadRawRequest(BaseModel):
    from_chapter: int = Field(default=1, ge=1, le=100000)
    to_chapter: int = Field(default=200, ge=1, le=100000)


class AdminTtsPrerenderRequest(BaseModel):
    from_chapter: int = Field(default=1, ge=1, le=100000)
    to_chapter: int = Field(default=20, ge=1, le=100000)
    voice: str = Field(default="mai_phuong", max_length=80)


class AdminGlossaryUpdateRequest(BaseModel):
    terms: List[TermItem] = Field(default_factory=list)


@router.get("/harvester/status")
async def get_harvester_status(_: bool = Depends(verify_admin_access)) -> Dict[str, Any]:
    """Xem trạng thái của Bộ Thu Thập Truyện Tự Động (Auto-Seeder) vào ổ 1TB."""
    return library_harvester.status()


@router.post("/harvester/start")
async def start_harvester(
    req: AdminHarvesterStartRequest, _: bool = Depends(verify_admin_access)
) -> Dict[str, Any]:
    """Khởi chạy thu thập tự động hàng loạt truyện theo gói chủ đề vào ổ 1TB."""
    chosen_pack = (req.pack or req.pack_id or "all").strip()
    return library_harvester.start(
        pack_id=chosen_pack,
        max_novels=req.max_novels,
        raw_chapters=req.raw_chapters,
        pretranslate_chapters=req.pretranslate_chapters,
    )


@router.post("/harvester/stop")
async def stop_harvester(_: bool = Depends(verify_admin_access)) -> Dict[str, Any]:
    """Dừng tiến trình thu thập truyện tự động."""
    return library_harvester.stop()


@router.post("/{novel_id}/download-raw")
async def download_novel_raw_chapters(
    novel_id: str,
    req: AdminDownloadRawRequest,
    _: bool = Depends(verify_admin_access),
) -> Dict[str, Any]:
    """Tải sẵn hàng loạt chương bản gốc chữ Hán (`Raw-First Mirror`) vào ổ 1TB."""
    if req.from_chapter > req.to_chapter:
        raise HTTPException(status_code=400, detail="Chương bắt đầu phải nhỏ hơn hoặc bằng chương kết thúc.")
    return offline_raw_runner.start(novel_id, req.from_chapter, req.to_chapter)


@router.post("/{novel_id}/overnight")
async def start_overnight_novel_translation(
    novel_id: str, _: bool = Depends(verify_admin_access)
) -> Dict[str, Any]:
    """Bật Dịch Ngầm Ban Đêm (Gemini 3.8 Flash Medium + Qwen3-4B-Q4_K_M.gguf) cho toàn bộ truyện."""
    from services.overnight_translator import overnight_translator
    return overnight_translator.enqueue_novel(novel_id, 1, 0)


@router.post("/{novel_id}/cancel-job")
async def cancel_novel_pretranslate_job(
    novel_id: str, _: bool = Depends(verify_admin_access)
) -> Dict[str, Any]:
    """Tạm dừng / huỷ đợt dịch ngầm hoặc tải chương gốc đang chạy của `novel_id`."""
    from services.storage_service import storage_service
    info = storage_service.get_novel_info(novel_id) or {}
    if info.get("overnight_enabled"):
        info["overnight_enabled"] = False
        storage_service.save_novel_info(novel_id, info)
    stopped_ai = batch_translation_runner.cancel("", novel_id)
    stopped_raw = offline_raw_runner.cancel(novel_id)
    if not (stopped_ai or stopped_raw):
        return {"status": "idle", "novel_id": novel_id, "message": "Đã tắt Dịch Ngầm cho truyện."}
    return {
        "status": "cancelling",
        "novel_id": novel_id,
        "message": "Đã gửi lệnh dừng. Hệ thống sẽ dừng ngay sau khi hoàn tất chương hiện tại.",
    }


def _enqueue_tts_range(novel_id: str, from_ch: int, to_ch: int, voice: str) -> Dict[str, Any]:
    import routes.tts as tts_routes

    cache_dir = getattr(tts_routes, "CACHE_DIR", tts_cache.CACHE_DIR)
    queued_chapters = 0
    ready_chapters = 0
    no_text_chapters = 0
    for ch_num in range(from_ch, to_ch + 1):
        plan = build_chapter_plan(novel_id, ch_num, voice, "vi", cache_dir)
        if not plan.has_text:
            no_text_chapters += 1
            continue
        enqueue_chapter(plan, render_queue, PRIORITY_BACKGROUND, 0, front=False)
        info = summarize(plan, render_queue, 0, PRIORITY_BACKGROUND)
        if info.get("state") == "ready":
            ready_chapters += 1
        else:
            queued_chapters += 1
    return {
        "status": "ok",
        "novel_id": novel_id,
        "voice": voice,
        "queued_chapters": queued_chapters,
        "ready_chapters": ready_chapters,
        "no_text_chapters": no_text_chapters,
        "message": (
            f"Đã xếp hàng tạo Audio cho {queued_chapters} chương "
            f"(đã có sẵn {ready_chapters} chương, {no_text_chapters} chương chưa có bản dịch)."
        ),
    }


@router.post("/{novel_id}/prerender-tts")
async def admin_prerender_novel_tts(
    novel_id: str,
    req: AdminTtsPrerenderRequest,
    _: bool = Depends(verify_admin_access),
) -> Dict[str, Any]:
    """Dựng sẵn giọng đọc VieNeu-TTS vào ổ 1TB cho dải chương đã dịch của `novel_id`."""
    if req.from_chapter > req.to_chapter:
        raise HTTPException(status_code=400, detail="Chương bắt đầu phải nhỏ hơn hoặc bằng chương kết thúc.")
    end_ch = min(req.to_chapter, req.from_chapter + 49)
    return await asyncio.to_thread(_enqueue_tts_range, novel_id, req.from_chapter, end_ch, req.voice)


@router.get("/{novel_id}/export.epub")
async def export_novel_epub(
    novel_id: str,
    translated_only: bool = True,
    _: bool = Depends(verify_admin_access),
) -> Response:
    """Đóng gói và tải về tệp `.epub` cho các chương đã dịch của `novel_id`."""
    res = await asyncio.to_thread(build_novel_epub, novel_id, translated_only)
    if not res:
        raise HTTPException(
            status_code=404,
            detail="Truyện chưa có chương nào trên ổ đĩa để xuất EPUB.",
        )
    epub_bytes, filename = res
    return Response(
        content=epub_bytes,
        media_type="application/epub+zip",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


@router.get("/{novel_id}/glossary")
async def get_novel_glossary(
    novel_id: str, _: bool = Depends(verify_admin_access)
) -> Dict[str, Any]:
    """Lấy danh sách thuật ngữ / tên nhân vật của `novel_id`."""
    entries = glossary_store.list_entries(novel_id)
    return {"novel_id": novel_id, "total": len(entries), "terms": entries}


@router.put("/{novel_id}/glossary")
async def update_novel_glossary(
    novel_id: str,
    req: AdminGlossaryUpdateRequest,
    _: bool = Depends(verify_admin_access),
) -> Dict[str, Any]:
    """Cập nhật bảng thuật ngữ / tên nhân vật của `novel_id`."""
    saved = glossary_store.replace_all(novel_id, req.terms)
    entries = glossary_store.list_entries(novel_id)
    return {
        "status": "updated",
        "novel_id": novel_id,
        "total": saved,
        "terms": entries,
        "message": f"Đã lưu {saved} thuật ngữ cho truyện.",
    }
