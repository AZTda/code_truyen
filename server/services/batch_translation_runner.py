"""Tác vụ nền dịch hàng loạt chương, có chặn lạm dụng.

Mỗi chương là một tiến trình Antigravity 25-90 giây ở làn nền. Không chặn thì
một người gửi liên tục các dải 100 chương là chiếm trọn làn nền của máy chủ.
Quy tắc: khử trùng số chương, mỗi người chỉ một đợt đang chạy cho mỗi truyện (gửi
lại cùng dải thì gắn lại đợt cũ để theo dõi), và tối đa 2 đợt cùng lúc mỗi người.
"""

import asyncio
import logging
import time
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Set, Tuple

from models.translate import TermItem
from services.agy_runner import TaskPriority
from services.chapter_translation_service import (
    SourceText,
    novel_preferred_model,
    persist_shared,
    with_chapter_heading,
)
from services.glossary_store import glossary_store, validate_persistable
from services.job_registry import job_registry
from services.novel_chapter_stats import invalidate_chapter_counts
from services.storage_service import storage_service
from services.translation_validation import ENGINE_FALLBACK, is_usable_translation

logger = logging.getLogger("truyencloud.batch_translation")

MAX_ACTIVE_BATCHES_PER_USER = 2
FAILED_CHAPTER_COOLDOWN_SECONDS = 1800.0


@dataclass
class Reservation:
    chapters: List[int] = field(default_factory=list)
    attach_job_id: Optional[str] = None
    error: Optional[str] = None


@dataclass
class _ActiveBatch:
    job_id: str
    chapters: Set[int]
    cancelled: bool = False


class BatchTranslationRunner:
    def __init__(self):
        self._active: Dict[Tuple[str, str], _ActiveBatch] = {}
        self._failed_cooldown: Dict[Tuple[str, int], float] = {}

    def is_in_cooldown(self, novel_id: str, chapter_number: int) -> bool:
        expiry = self._failed_cooldown.get((novel_id, chapter_number))
        if expiry is None:
            return False
        if time.monotonic() >= expiry:
            self._failed_cooldown.pop((novel_id, chapter_number), None)
            return False
        return True

    def mark_chapter_failed(
        self, novel_id: str, chapter_number: int, cooldown_sec: float = FAILED_CHAPTER_COOLDOWN_SECONDS
    ) -> None:
        self._failed_cooldown[(novel_id, chapter_number)] = time.monotonic() + cooldown_sec

    def clear_cooldown(self, novel_id: str, chapter_number: Optional[int] = None) -> None:
        if chapter_number is not None:
            self._failed_cooldown.pop((novel_id, chapter_number), None)
            return
        for key in [k for k in self._failed_cooldown if k[0] == novel_id]:
            self._failed_cooldown.pop(key, None)

    def reserve(self, user_id: str, novel_id: str, chapters: List[int]) -> Reservation:
        unique = list(dict.fromkeys(chapters))
        active = self._active.get((user_id, novel_id))
        if active:
            if set(unique) <= active.chapters:
                return Reservation(attach_job_id=active.job_id)
            return Reservation(error="Bạn đang có một đợt dịch ngầm cho truyện này, vui lòng chờ đợt đó xong.")
        if sum(1 for (uid, _) in self._active if uid == user_id) >= MAX_ACTIVE_BATCHES_PER_USER:
            return Reservation(error=f"Mỗi tài khoản chỉ chạy tối đa {MAX_ACTIVE_BATCHES_PER_USER} đợt dịch ngầm cùng lúc.")
        return Reservation(chapters=unique)

    def activate(self, user_id: str, novel_id: str, job_id: str, chapters: List[int]) -> None:
        self._active[(user_id, novel_id)] = _ActiveBatch(job_id, set(chapters))

    def release(self, user_id: str, novel_id: str) -> None:
        self._active.pop((user_id, novel_id), None)

    def cancel(self, user_id: str, novel_id: str) -> bool:
        """Đánh dấu dừng đợt dịch ngầm của `novel_id`."""
        for (uid, nid), active in list(self._active.items()):
            if nid == novel_id and (not user_id or uid == user_id):
                active.cancelled = True
                job_registry.update(
                    active.job_id,
                    status="failed",
                    error="Đã dừng theo yêu cầu.",
                    message="Đang dừng tác vụ dịch sau chương hiện tại...",
                )
                return True
        return False

    def is_cancelled(self, user_id: str, novel_id: str) -> bool:
        active = self._active.get((user_id, novel_id))
        return bool(active and active.cancelled)

    @staticmethod
    def _already_translated(novel_id: str, chapter_number: int) -> bool:
        stored = storage_service.get_chapter(novel_id, chapter_number) or {}
        return is_usable_translation(stored.get("translated_content")) and \
            stored.get("translation_engine") != ENGINE_FALLBACK

    @staticmethod
    def _enqueue_tts_background(novel_id: str, chapter_number: int) -> None:
        """Tự động xếp dựng sẵn âm thanh VieNeu-TTS ở ưu tiên nền (PRIORITY_BACKGROUND) cho chương vừa dịch xong."""
        try:
            from services import tts_cache
            from services.tts_prerender import PRIORITY_BACKGROUND, render_queue
            from services.tts_prerender_plan import build_chapter_plan, enqueue_chapter

            if render_queue.listener_waiting() or render_queue.length() >= 120:
                return
            plan = build_chapter_plan(novel_id, chapter_number, "mai_phuong", "vi", tts_cache.CACHE_DIR)
            if plan.has_text:
                enqueue_chapter(plan, render_queue, PRIORITY_BACKGROUND, 0, front=False)
        except Exception:
            pass

    async def run(
        self, user_id: str, novel_id: str, chapters: List[int],
        request_terms: List[TermItem], job_id: str, persist_terms: bool = False,
    ) -> None:
        """persist_terms: thuật ngữ của lời gọi (do quản trị viên gửi) được ghi vào bảng dùng chung,
        chỉ những mục có trong bản gốc của chương đang dịch. Mặc định không ghi."""
        from routes.chapters import get_chapter
        from services.translation_worker import translation_worker

        total = max(1, len(chapters))
        completed = failures = 0
        try:
            job_registry.update(job_id, status="running", message="Đang chuẩn bị dịch.")
            for ch_num in chapters:
                if self.is_cancelled(user_id, novel_id):
                    job_registry.update(
                        job_id,
                        status="failed",
                        error="Đã dừng theo yêu cầu.",
                        message=f"Đã tạm dừng sau {completed}/{total} chương.",
                    )
                    return
                try:
                    job_registry.update(job_id, progress=completed / total,
                                        message=f"Đang xử lý chương {ch_num}.")
                    if self._already_translated(novel_id, ch_num):
                        await asyncio.to_thread(self._enqueue_tts_background, novel_id, ch_num)
                        continue
                    chapter_obj = await get_chapter(novel_id, ch_num)
                    if persist_terms and request_terms:
                        glossary_store.save(novel_id, validate_persistable(request_terms, chapter_obj.original_content))
                    # Nạp lại bảng thuật ngữ MỖI chương: tên riêng AI tự trích ở chương
                    # trước (auto_glossary) phải áp được cho chương sau trong cùng đợt.
                    # Làn nền không có ai chờ: AI lỗi thì bỏ qua, không lưu bản Google.
                    result = await translation_worker.translate_chapter_detailed(
                        content=with_chapter_heading(
                            chapter_obj.original_content, chapter_obj.title, ch_num
                        ),
                        glossary=glossary_store.merged_for_request(novel_id, request_terms),
                        priority=TaskPriority.BACKGROUND,
                        allow_fallback=False,
                        model_override=novel_preferred_model(novel_id),
                    )
                    if result is None or not result.text:
                        raise RuntimeError("AI không dịch được chương này.")
                    persist_shared(novel_id, ch_num, "vi", "c1", result, SourceText(
                        content=chapter_obj.original_content,
                        title=chapter_obj.title,
                        title_vi=chapter_obj.title_vi or "",
                    ))
                    self.clear_cooldown(novel_id, ch_num)
                    invalidate_chapter_counts(novel_id)
                    await asyncio.to_thread(self._enqueue_tts_background, novel_id, ch_num)
                    await asyncio.sleep(1.0)
                except Exception as exc:
                    failures += 1
                    self.mark_chapter_failed(novel_id, ch_num)
                    job_registry.update(job_id, message=f"Bỏ qua chương {ch_num}: {exc}")
                finally:
                    completed += 1
                    job_registry.update(job_id, progress=completed / total)

            suffix = f"; lỗi {failures} chương" if failures else ""
            job_registry.update(
                job_id,
                status="succeeded" if failures < total else "failed",
                progress=1.0,
                error=(f"Không xử lý được {failures} chương." if failures == total else ""),
                message=f"Đã xử lý {completed}/{total} chương{suffix}.",
            )
        except asyncio.CancelledError:
            job_registry.update(job_id, status="failed", error="Tác vụ bị huỷ.", message="Tác vụ dịch đã bị huỷ.")
            raise
        except Exception as exc:
            job_registry.update(job_id, status="failed", error=str(exc), message="Tác vụ dịch gặp lỗi.")
        finally:
            invalidate_chapter_counts(novel_id)
            self.release(user_id, novel_id)


batch_translation_runner = BatchTranslationRunner()
