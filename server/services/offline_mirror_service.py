"""Dịch vụ Sao lưu Offline Bản gốc (`Raw-First Mirror`) & Lưu Ảnh bìa Vĩnh viễn lên ổ 1TB.

Cho phép tải trọn bộ hàng nghìn chương chữ Hán gốc của một truyện về ổ 1TB với tốc độ cao (~3 chương/giây,
có giãn cách chống chặn IP) mà không tốn hạn mức AI, đảm bảo truyện luôn đọc được 100% offline.
"""

import asyncio
import logging
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Set

from services.job_registry import job_registry
from services.novel_chapter_stats import invalidate_chapter_counts
from services.storage_service import storage_service

logger = logging.getLogger("truyencloud.offline_mirror")

RAW_MIRROR_OWNER = "admin_console"
MAX_RAW_CHAPTERS_PER_JOB = 5000


async def mirror_cover_to_disk(cover_url: str) -> None:
    """Tải trước ảnh bìa vào bộ đệm đĩa vĩnh viễn (`data/covers_cache/`) trên ổ 1TB."""
    clean = (cover_url or "").strip()
    if not clean or not clean.startswith(("http://", "https://")):
        return
    try:
        from routes.images import proxy_image

        await proxy_image(url=clean)
    except Exception as exc:
        logger.debug(f"Không lưu trước được ảnh bìa '{clean}': {exc}")


@dataclass
class _ActiveRawJob:
    job_id: str
    from_chapter: int
    to_chapter: int
    total: int
    completed: int = 0
    skipped: int = 0
    failed: int = 0
    current_chapter: int = 1
    message: str = ""
    cancelled: bool = False


class OfflineRawRunner:
    """Quản lý các tác vụ tải sẵn chương gốc (`original_content`) về ổ 1TB."""

    def __init__(self) -> None:
        self._active: Dict[str, _ActiveRawJob] = {}

    def active_for(self, novel_id: str) -> Optional[Dict[str, Any]]:
        item = self._active.get(novel_id)
        if not item:
            return None
        return {
            "job_id": item.job_id,
            "kind": "raw_mirror",
            "status": "running",
            "from_chapter": item.from_chapter,
            "to_chapter": item.to_chapter,
            "total": item.total,
            "completed": item.completed,
            "skipped": item.skipped,
            "failed": item.failed,
            "current_chapter": item.current_chapter,
            "message": item.message,
        }

    def cancel(self, novel_id: str) -> bool:
        item = self._active.get(novel_id)
        if not item:
            return False
        item.cancelled = True
        item.message = "Đang dừng tải chương gốc..."
        job_registry.update(
            item.job_id,
            status="failed",
            error="Đã dừng theo yêu cầu.",
            message=item.message,
        )
        return True

    @staticmethod
    def _has_raw_on_disk(novel_id: str, ch_num: int) -> bool:
        stored = storage_service.get_chapter(novel_id, ch_num)
        return bool(stored and (stored.get("original_content") or "").strip())

    def start(self, novel_id: str, from_ch: int, to_ch: int) -> Dict[str, Any]:
        existing = self.active_for(novel_id)
        if existing:
            return {
                "status": "running",
                "job_id": existing["job_id"],
                "job": existing,
                "message": "Truyện này đang có tác vụ tải chương gốc đang chạy.",
            }
        end_ch = min(to_ch, from_ch + MAX_RAW_CHAPTERS_PER_JOB - 1)
        total = max(1, end_ch - from_ch + 1)
        job = job_registry.create("raw_mirror", RAW_MIRROR_OWNER)
        state = _ActiveRawJob(
            job_id=job.id,
            from_chapter=from_ch,
            to_chapter=end_ch,
            total=total,
            current_chapter=from_ch,
            message=f"Đang chuẩn bị tải chương gốc {from_ch} → {end_ch}...",
        )
        self._active[novel_id] = state
        asyncio.create_task(self._run(novel_id, list(range(from_ch, end_ch + 1)), state))
        return {
            "status": "queued",
            "job_id": job.id,
            "queued_chapters": total,
            "job": self.active_for(novel_id),
            "message": f"Đã bắt đầu tải ngầm {total} chương gốc ({from_ch} → {end_ch}) vào ổ 1TB.",
        }

    async def _run(self, novel_id: str, chapters: List[int], state: _ActiveRawJob) -> None:
        from routes.chapters import get_chapter

        try:
            job_registry.update(state.job_id, status="running", message=state.message)
            for idx, ch_num in enumerate(chapters, start=1):
                if state.cancelled:
                    break
                state.current_chapter = ch_num
                if self._has_raw_on_disk(novel_id, ch_num):
                    state.skipped += 1
                else:
                    state.message = f"Đang tải bản gốc chương {ch_num} ({idx}/{state.total})..."
                    job_registry.update(
                        state.job_id,
                        progress=(idx - 1) / state.total,
                        message=state.message,
                    )
                    try:
                        await get_chapter(novel_id, ch_num, recrawl=False)
                        state.completed += 1
                    except Exception as exc:
                        state.failed += 1
                        state.message = f"Bỏ qua chương {ch_num}: {exc}"
                    if idx % 5 == 0:
                        invalidate_chapter_counts(novel_id)
                    await asyncio.sleep(0.30)

                done = state.completed + state.skipped + state.failed
                job_registry.update(state.job_id, progress=done / state.total)

            invalidate_chapter_counts(novel_id)
            summary = (
                f"Đã lưu mới {state.completed} chương gốc, có sẵn {state.skipped} chương"
                + (f", lỗi {state.failed} chương." if state.failed else ".")
            )
            state.message = summary
            job_registry.update(
                state.job_id,
                status="succeeded" if not state.cancelled else "failed",
                progress=1.0,
                message=summary,
            )
        except Exception as exc:
            logger.warning(f"Lỗi tải chương gốc cho {novel_id}: {exc}")
            job_registry.update(state.job_id, status="failed", error=str(exc), message=f"Lỗi: {exc}")
        finally:
            invalidate_chapter_counts(novel_id)
            self._active.pop(novel_id, None)


offline_raw_runner = OfflineRawRunner()
