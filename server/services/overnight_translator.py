"""Bộ Dịch Ngầm Truyện Theo Yêu Cầu (`OvernightTranslator`) dùng `gemini-3.8-flash-medium` + `Qwen3-4B-Q4_K_M.gguf`.

Chỉ dịch ngầm những bộ truyện được người dùng bấm nút **"🌙 Dịch ngầm"** trên Web hoặc App
(đánh dấu `overnight_enabled = True` trong `info.json`).
"""

import asyncio
import itertools
import json
import logging
import os
from typing import Any, Dict, List, Optional, Tuple

from database import db
from services.batch_translation_runner import batch_translation_runner
from services.job_registry import job_registry
from services.storage_service import NOVELS_DIR, storage_service
from services.title_refiner import tts_listener_waiting
from services.translation_validation import ENGINE_FALLBACK, is_usable_translation

logger = logging.getLogger("truyencloud.overnight")


class OvernightTranslator:
    """Tiến trình dịch ngầm các bộ truyện đã bật cờ `overnight_enabled`."""

    def __init__(self) -> None:
        self._task: Optional[asyncio.Task] = None
        self.running: bool = False
        self.current_novel_id: str = ""
        self.current_batch: List[int] = []
        self.translated_total: int = 0
        self._last_novel_id: str = ""

    def start(self) -> None:
        if (os.getenv("OVERNIGHT_TRANSLATOR_ENABLED", "1").strip() or "1") == "0":
            return
        if self._task is not None and not self._task.done():
            return
        self.running = True
        self._task = asyncio.create_task(self._loop(), name="overnight-translator")
        logger.info("Đã khởi động bộ Dịch Ngầm (Gemini 3.8 Flash Medium + Qwen3-4B-Q4_K_M.gguf).")

    def enqueue_novel(
        self, novel_id: str, from_chapter: int = 1, to_chapter: int = 0
    ) -> Dict[str, Any]:
        """Bật chế độ dịch ngầm cho `novel_id` và khởi chạy ngay lô chương đầu tiên nếu làn nền đang rảnh."""
        info = storage_service.get_novel_info(novel_id) or {}
        total = int(info.get("total_chapters") or 0)
        if total <= 0:
            idx_path = storage_service.get_chapters_index_path(novel_id)
            try:
                with open(idx_path, "r", encoding="utf-8") as f:
                    total = len(json.load(f))
            except Exception:
                total = 2000
        target_to = min(total, to_chapter) if (to_chapter > 0 and total > 0) else (total or to_chapter or 2000)
        start_ch = max(1, from_chapter or 1)
        info["overnight_enabled"] = True
        info["overnight_from"] = start_ch
        info["overnight_to"] = target_to
        info["preferred_model"] = "gemini-3.8-flash-medium"
        storage_service.save_novel_info(novel_id, info)
        batch_translation_runner.clear_cooldown(novel_id)

        # Tìm ngay các chương chưa dịch từ start_ch..target_to (tối đa 500 chương mỗi đợt job)
        batch = self._find_untranslated_range(novel_id, start_ch, target_to, batch_size=300, skip_cooldown=False)
        if not batch:
            for ch_num in range(start_ch, min(target_to, start_ch + 2) + 1):
                batch_translation_runner._enqueue_tts_background(novel_id, ch_num)
            return {
                "novel_id": novel_id,
                "status": "completed",
                "job_id": "",
                "queued_chapters": 0,
                "from_chapter": start_ch,
                "to_chapter": target_to,
                "message": "Toàn bộ chương trong khoảng này đã được dịch sẵn và xếp hàng dựng Audio VieNeu-TTS!",
            }

        owner = "overnight_daemon"
        active = batch_translation_runner._active.get((owner, novel_id))
        if active:
            return {
                "novel_id": novel_id,
                "status": "running",
                "job_id": active.job_id,
                "queued_chapters": len(active.chapters),
                "from_chapter": min(active.chapters),
                "to_chapter": max(active.chapters),
                "message": f"Đang dịch ngầm {len(active.chapters)} chương bằng Gemini Flash Medium + Qwen3-4B.",
            }

        job_id = ""
        try:
            loop = asyncio.get_running_loop()
            job = job_registry.create("batch_translation", owner)
            job_id = job.id
            self.current_novel_id = novel_id
            self.current_batch = list(batch)
            batch_translation_runner.activate(owner, novel_id, job.id, batch)
            loop.create_task(
                batch_translation_runner.run(owner, novel_id, batch, [], job.id, False)
            )
        except RuntimeError:
            pass
        return {
            "novel_id": novel_id,
            "status": "queued",
            "job_id": job_id,
            "queued_chapters": len(batch),
            "from_chapter": batch[0],
            "to_chapter": batch[-1],
            "message": (
                f"Đã bật Dịch Ngầm cho truyện ({batch[0]} → {target_to}) "
                f"bằng Gemini 3.8 Flash Medium + Qwen3-4B-Q4_K_M.gguf!"
            ),
        }

    def disable_novel(self, novel_id: str) -> Dict[str, Any]:
        """Tạm dừng / tắt chế độ dịch ngầm ban đêm cho `novel_id`."""
        info = storage_service.get_novel_info(novel_id) or {}
        info["overnight_enabled"] = False
        storage_service.save_novel_info(novel_id, info)
        batch_translation_runner.cancel("overnight_daemon", novel_id)
        if self.current_novel_id == novel_id:
            self.current_novel_id = ""
            self.current_batch = []
        return {
            "novel_id": novel_id,
            "status": "disabled",
            "job_id": "",
            "from_chapter": int(info.get("overnight_from") or 1),
            "to_chapter": int(info.get("overnight_to") or info.get("total_chapters") or 0),
            "message": "Đã tạm dừng Dịch Ngầm cho bộ truyện này.",
        }

    def status(self) -> Dict[str, Any]:
        """Trạng thái trực tiếp của tiến trình Dịch Ngầm trên máy chủ."""
        enabled_novels: List[Dict[str, Any]] = []
        try:
            for nid in sorted(os.listdir(NOVELS_DIR)):
                if nid.startswith("_"):
                    continue
                info = storage_service.get_novel_info(nid) or {}
                if info.get("overnight_enabled"):
                    from services.novel_chapter_stats import get_chapter_counts
                    counts = get_chapter_counts(nid)
                    enabled_novels.append({
                        "id": nid,
                        "title": info.get("title_vi") or info.get("title") or nid,
                        "total_chapters": int(info.get("total_chapters") or 0),
                        "translated_chapters": int(counts.get("translated_chapters") or 0),
                    })
        except Exception:
            pass
        cur_nid = self.current_novel_id
        cur_batch = list(self.current_batch)
        if (not cur_nid or not cur_batch) and batch_translation_runner._active:
            (_, cur_nid), active_batch = next(iter(batch_translation_runner._active.items()))
            cur_batch = sorted(active_batch.chapters)
        live_batch = [
            ch for ch in cur_batch
            if not batch_translation_runner._already_translated(cur_nid, ch)
        ] if cur_nid and cur_batch else list(cur_batch)
        return {
            "running": self.running,
            "current_novel_id": cur_nid,
            "current_batch": live_batch or cur_batch[-1:],
            "translated_total": self.translated_total + max(0, len(cur_batch) - len(live_batch)),
            "models": ["gemini-3.8-flash-medium", "Qwen3-4B-Q4_K_M.gguf"],
            "enabled_novels": enabled_novels,
        }

    def _get_reading_pivot(self, novel_id: str, start_ch: int, end_ch: int) -> int:
        try:
            res = (
                db.table("reading_progress_backend")
                .select("current_chapter,shelf_name,updated_at_ms")
                .eq("novel_id", novel_id)
                .execute()
            )
            rows = getattr(res, "data", None) or []
            best_ch = start_ch
            best_ts = -1
            for row in rows:
                if not isinstance(row, dict) or row.get("shelf_name") == "removed":
                    continue
                ch = int(row.get("current_chapter") or 0)
                if start_ch <= ch <= end_ch:
                    ts = int(row.get("updated_at_ms") or 0)
                    if ts >= best_ts:
                        best_ts = ts
                        best_ch = ch
            return best_ch
        except Exception:
            return start_ch

    def _find_untranslated_range(
        self,
        novel_id: str,
        start_ch: int,
        end_ch: int,
        batch_size: int = 20,
        skip_cooldown: bool = True,
    ) -> List[int]:
        lo = max(1, start_ch)
        hi = max(start_ch, end_ch)
        pivot = self._get_reading_pivot(novel_id, lo, hi)
        missing: List[int] = []
        for ch_num in itertools.chain(range(pivot, hi + 1), range(lo, pivot)):
            if skip_cooldown and batch_translation_runner.is_in_cooldown(novel_id, ch_num):
                continue
            stored = storage_service.get_chapter(novel_id, ch_num) or {}
            if is_usable_translation(stored.get("translated_content")) and stored.get("translation_engine") != ENGINE_FALLBACK:
                continue
            missing.append(ch_num)
            if len(missing) >= batch_size:
                break
        return missing

    def _pick_next_work(self) -> Optional[Tuple[str, List[int]]]:
        try:
            all_ids = [nid for nid in sorted(os.listdir(NOVELS_DIR)) if not nid.startswith("_")]
        except Exception:
            return None
        enabled_ids: List[str] = []
        infos: Dict[str, Dict[str, Any]] = {}
        for nid in all_ids:
            info = storage_service.get_novel_info(nid) or {}
            if info.get("overnight_enabled"):
                enabled_ids.append(nid)
                infos[nid] = info
        if not enabled_ids:
            return None
        # Xoay vòng công bằng (Round-Robin): truyện vừa chạy lô trước sẽ xuống cuối hàng đợi
        if self._last_novel_id in enabled_ids and len(enabled_ids) > 1:
            idx = enabled_ids.index(self._last_novel_id)
            enabled_ids = enabled_ids[idx + 1:] + enabled_ids[:idx + 1]

        fallback_cooled: Optional[Tuple[str, List[int]]] = None
        for nid in enabled_ids:
            info = infos[nid]
            start_ch = int(info.get("overnight_from") or 1)
            end_ch = int(info.get("overnight_to") or info.get("total_chapters") or 2000)
            batch = self._find_untranslated_range(nid, start_ch, end_ch, batch_size=20, skip_cooldown=True)
            if batch:
                self._last_novel_id = nid
                return (nid, batch)
            cooled = self._find_untranslated_range(nid, start_ch, end_ch, batch_size=20, skip_cooldown=False)
            if cooled:
                if fallback_cooled is None:
                    fallback_cooled = (nid, cooled)
                continue
            # Đã dịch xong toàn bộ khoảng yêu cầu -> tắt cờ overnight_enabled
            info["overnight_enabled"] = False
            storage_service.save_novel_info(nid, info)
        if fallback_cooled is not None:
            self._last_novel_id = fallback_cooled[0]
        return fallback_cooled

    async def _loop(self) -> None:
        await asyncio.sleep(5.0)
        while self.running:
            try:
                if batch_translation_runner._active:
                    await asyncio.sleep(5.0)
                    continue
                work = await asyncio.to_thread(self._pick_next_work)
                if not work:
                    await asyncio.sleep(30.0)
                    continue
                nid, chs = work
                self.current_novel_id = nid
                self.current_batch = chs
                owner = "overnight_daemon"
                job = job_registry.create("batch_translation", owner)
                batch_translation_runner.activate(owner, nid, job.id, chs)
                logger.info(f"OvernightTranslator: Dịch ngầm {nid} chương {chs[0]}..{chs[-1]}")
                await batch_translation_runner.run(owner, nid, chs, [], job.id, False)
                self.translated_total += len(chs)
                self.current_novel_id = ""
                self.current_batch = []
                await asyncio.sleep(2.0)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.warning(f"OvernightTranslator gặp lỗi tạm thời: {exc}")
                await asyncio.sleep(20.0)


overnight_translator = OvernightTranslator()
