import asyncio
import logging
from pathlib import Path
from datetime import datetime, timezone
from typing import Dict, Any, Optional
from database import db
from services.storage_service import storage_service
from services.glossary_store import glossary_store
from services.scraper_registry import get_scraper_for_url
from services.translation_worker import translation_worker
from services.agy_runner import TaskPriority
from services.batch_translation_runner import batch_translation_runner
from services.chapter_translation_service import (
    SourceText,
    novel_preferred_model,
    persist_shared,
    with_chapter_heading,
)
from services.chapters_index_repair import count_duplicates
from services.novel_chapter_stats import invalidate_chapter_counts
from services.toc_identity import diff_toc
from services.toc_lookup import toc_lookup

logger = logging.getLogger("truyencloud.auto_updater")

# Số chương mới đầu tiên được dịch trước ở làn nền sau mỗi lượt quét
PRETRANSLATE_FIRST_NEW = 2

class AutoUpdater:
    """Service that periodically scans monitored novels from web sources,
    discovers new chapters, and pre-translates them via Antigravity CLI in background."""

    def __init__(self, check_interval_seconds: int = 1800):
        self.check_interval_seconds = check_interval_seconds
        self.is_running = False
        self.last_run: Optional[str] = None
        self.next_run: Optional[str] = None
        self.total_checks_count: int = 0
        self.new_chapters_discovered: int = 0
        self.pretranslated_chapters_count: int = 0
        self._background_task: Optional[asyncio.Task] = None
        self._active_jobs: Dict[str, str] = {}
        self._lock_handle = None
        self._lock_path = Path(__file__).parent.parent / "data" / ".auto_updater.lock"

    def _acquire_process_lock(self) -> bool:
        """Prevent duplicate schedulers when multiple server workers are running."""
        try:
            import fcntl
            self._lock_path.parent.mkdir(parents=True, exist_ok=True)
            handle = open(self._lock_path, "a+", encoding="utf-8")
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            self._lock_handle = handle
            return True
        except (ImportError, OSError):
            try:
                if self._lock_handle:
                    self._lock_handle.close()
            except Exception:
                pass
            self._lock_handle = None
            return False

    def _release_process_lock(self) -> None:
        if not self._lock_handle:
            return
        try:
            import fcntl
            fcntl.flock(self._lock_handle.fileno(), fcntl.LOCK_UN)
        except (ImportError, OSError):
            pass
        try:
            self._lock_handle.close()
        finally:
            self._lock_handle = None

    def get_status(self) -> Dict[str, Any]:
        return {
            "is_running": self.is_running,
            "check_interval_seconds": self.check_interval_seconds,
            "last_run": self.last_run,
            "next_run": self.next_run,
            "total_checks_count": self.total_checks_count,
            "new_chapters_discovered": self.new_chapters_discovered,
            "pretranslated_chapters_count": self.pretranslated_chapters_count,
            "active_jobs": self._active_jobs
        }

    async def check_novel_for_updates(self, novel_id: str, auto_pretranslate: bool = True) -> Dict[str, Any]:
        """Checks a specific novel against its remote source URL for new chapters."""
        novel_info = storage_service.get_novel_info(novel_id)
        if not novel_info:
            res = db.table("novels").select("*").eq("id", novel_id).single().execute()
            if res.data:
                novel_info = res.data
                storage_service.save_novel_info(novel_id, novel_info)

        if not novel_info:
            return {"status": "error", "message": f"Không tìm thấy truyện ID: {novel_id}"}

        source_url = novel_info.get("source_url")
        if not source_url:
            return {"status": "skipped", "message": "Truyện không có source_url (truyện import hoặc ngoại tuyến)."}

        self._active_jobs[novel_id] = "Đang quét mục lục từ nguồn..."
        try:
            scraper = get_scraper_for_url(source_url)
            remote_detail = await scraper.fetch_novel_info(source_url)
            remote_chapters = remote_detail.chapters or []
            if not remote_chapters:
                # Scraper nuốt lỗi và trả [] khi trang bị chặn: không phải "truyện hết chương".
                # Tính là lỗi (không ghi nhận mục lục rỗng, tạm ngưng cào lại) thay vì báo up_to_date.
                toc_lookup.mark_failed(novel_id)
                logger.warning(f"AutoUpdater: Truyện '{novel_id}' - nguồn trả về mục lục rỗng, bỏ qua lượt quét.")
                return {"status": "error", "message": "Nguồn trả về mục lục rỗng, bỏ qua lượt quét này."}
            # Mục lục vừa cào: route đọc chương dùng lại, không cào lại khi tải trước vượt cuối truyện
            toc_lookup.remember(novel_id, remote_chapters)

            current_chapters = storage_service.list_chapters(novel_id)
            stored_count = len(current_chapters)
            remote_count = len(remote_chapters)

            logger.info(f"AutoUpdater: Truyện '{novel_id}' - Lưu trữ: {stored_count} ch, Nguồn gốc: {remote_count} ch.")
            duplicates = count_duplicates(current_chapters)
            if duplicates:
                # Mục lục đã bị nhân đôi từ bản cũ: chỉ báo, không tự sửa (xem services/chapters_index_repair.py)
                logger.warning(
                    f"AutoUpdater: Truyện '{novel_id}' có {duplicates} mục trùng URL trong chapters_index.json. "
                    "Sửa bằng: python -m services.chapters_index_repair --apply " + novel_id
                )

            # So khop theo DANH TINH chuong (URL chuan hoa hoac id nguon that) thay vi cat theo chi so,
            # xem services/toc_identity.py. Ban cu dung `remote_chapters[stored_count:]` — gia dinh
            # chuong moi luon nam o CUOI; ban sau so id voi id nhung muc luc da luu ghi id = so chuong
            # con scraper tra id nguon nen khong bao gio khop va moi chuong deu thanh "moi".
            diff = diff_toc(current_chapters, remote_chapters)
            new_chapters = diff.new
            if diff.identity_mismatch(stored_count, remote_count):
                logger.warning(
                    f"AutoUpdater: Truyện '{novel_id}' - không khớp được chương nào giữa {stored_count} chương đã lưu "
                    f"và {remote_count} chương ở nguồn; bỏ qua để không nhân đôi mục lục."
                )
                return {
                    "status": "error",
                    "message": "Không khớp được chương nào giữa mục lục đã lưu và mục lục nguồn "
                               "(nguồn đổi dạng URL/tên miền?). Cần cào lại truyện thủ công.",
                }

            if not new_chapters:
                return {
                    "status": "up_to_date",
                    "stored_chapters": stored_count,
                    "remote_chapters": remote_count,
                    "new_chapters_found": 0,
                    **({"duplicate_index_entries": duplicates} if duplicates else {}),
                }

            logger.info(f"AutoUpdater: Phát hiện {len(new_chapters)} chương mới cho truyện '{novel_id}'!")
            self.new_chapters_discovered += len(new_chapters)

            # Auto-translate titles of new chapters
            new_titles = [c.title for c in new_chapters]
            translated_titles = await translation_worker.translate_chapter_titles_batch(new_titles, novel_id=novel_id)

            updated_index = list(current_chapters)
            # Danh so tiep noi so lon nhat da luu, thay vi tin vao chi so DOM cua trang nguon
            max_stored = 0
            for c in current_chapters:
                try:
                    n = int(c.get("chapter_number") or 0) if isinstance(c, dict) else int(getattr(c, "chapter_number", 0) or 0)
                    max_stored = max(max_stored, n)
                except (TypeError, ValueError):
                    continue

            for idx, ch in enumerate(new_chapters):
                title_vi = translated_titles[idx] if idx < len(translated_titles) else ""
                updated_index.append({
                    "chapter_number": max_stored + idx + 1,
                    "id": ch.id,
                    "title": ch.title,
                    "title_vi": title_vi,
                    "url": ch.url
                })

            storage_service.save_chapters_index(novel_id, updated_index)

            # Update novel total chapters
            novel_info["total_chapters"] = remote_count
            storage_service.save_novel_info(novel_id, novel_info)
            db.table("novels").update({"total_chapters": remote_count}).eq("id", novel_id).execute()

            # Dịch trước N chương MỚI ĐẦU TIÊN: độc giả đã đọc kịp sẽ mở chương mới
            # đầu tiên, không phải chương cuối (bản cũ chỉ dịch new_chapters[-1]).
            pretranslated = False
            if auto_pretranslate and new_chapters:
                for idx, ch in enumerate(new_chapters[:PRETRANSLATE_FIRST_NEW]):
                    title_vi = translated_titles[idx] if idx < len(translated_titles) else ""
                    if await self._pretranslate_chapter(
                        novel_id, max_stored + idx + 1, ch, title_vi, source_url
                    ):
                        pretranslated = True

            return {
                "status": "updated",
                "new_chapters_found": len(new_chapters),
                "stored_chapters": remote_count,
                "latest_chapter_number": max_stored + len(new_chapters),
                "latest_chapter_title": new_chapters[-1].title,
                "pretranslated": pretranslated
            }
        except Exception as e:
            logger.error(f"Lỗi khi quét cập nhật cho truyện {novel_id}: {e}")
            return {"status": "error", "message": str(e)}
        finally:
            self._active_jobs.pop(novel_id, None)

    async def _pretranslate_chapter(
        self, novel_id: str, number: int, ch: Any, title_vi: str, source_url: str
    ) -> bool:
        """Cào + dịch một chương mới ở làn nền. AI lỗi thì vẫn lưu bản gốc, không lưu bản Google."""
        self._active_jobs[novel_id] = f"Đang dịch trước chương {number} qua Antigravity CLI..."
        try:
            raw_content = await get_scraper_for_url(ch.url or source_url).fetch_chapter_content(ch.url)
            if not raw_content or not raw_content.strip():
                return False
            ch_title = getattr(ch, "title", "") or ""
            record = {
                "novel_id": novel_id,
                "chapter_number": number,
                "title": ch_title,
                "title_vi": title_vi,
                "url": ch.url,
                "original_content": raw_content,
            }
            # Chay ngam, khong co ung dung gui bang thuat ngu len — dung bang da luu, nap lai
            # MOI chuong de ten rieng AI vua trich o chuong truoc (auto_glossary) duoc ap ngay.
            result = await translation_worker.translate_chapter_detailed(
                content=with_chapter_heading(raw_content, ch_title, number),
                glossary=glossary_store.load(novel_id),
                priority=TaskPriority.BACKGROUND,
                allow_fallback=False,
                model_override=novel_preferred_model(novel_id),
            )
            if not (result and result.text):
                storage_service.save_chapter(novel_id, number, record)
                invalidate_chapter_counts(novel_id)
                return False
            persist_shared(
                novel_id,
                number,
                "vi",
                "c1",
                result,
                SourceText(
                    content=raw_content,
                    title=ch_title,
                    title_vi=title_vi or "",
                    url=ch.url or "",
                ),
            )
            invalidate_chapter_counts(novel_id)
            batch_translation_runner._enqueue_tts_background(novel_id, number)
            self.pretranslated_chapters_count += 1
            logger.info(f"AutoUpdater: Dịch trước thành công chương {number} của truyện '{novel_id}'.")
            return True
        except Exception as e:
            logger.warning(f"AutoUpdater: Không thể dịch trước chương {number} của {novel_id}: {e}")
            return False

    async def check_all_novels(self) -> Dict[str, Any]:
        """Iterates over all stored novels and checks for updates."""
        self.last_run = datetime.now(timezone.utc).isoformat()
        self.total_checks_count += 1
        all_novels = storage_service.list_all_novels()
        results = []

        for novel in all_novels[:25]:
            n_id = novel.get("id")
            s_url = novel.get("source_url")
            if n_id and s_url:
                res = await self.check_novel_for_updates(n_id, auto_pretranslate=True)
                results.append({"novel_id": n_id, "title": novel.get("title_vi") or novel.get("title"), "result": res})
                # Polite throttle between requests
                await asyncio.sleep(5)

        return {
            "timestamp": self.last_run,
            "checked_novels": len(results),
            "details": results
        }

    async def _loop(self):
        self.is_running = True
        logger.info(f"AutoUpdater: Khởi động vòng lặp kiểm tra định kỳ mỗi {self.check_interval_seconds}s.")
        await asyncio.sleep(self.check_interval_seconds)
        while self.is_running:
            try:
                await self.check_all_novels()
            except Exception as e:
                logger.error(f"Lỗi trong vòng lặp AutoUpdater: {e}")
            await asyncio.sleep(self.check_interval_seconds)

    def start(self):
        if not self.is_running and self._acquire_process_lock():
            self._background_task = asyncio.create_task(self._loop())
        elif not self.is_running:
            logger.info("AutoUpdater: đã có worker khác giữ lock, bỏ qua scheduler ở process này.")

    def stop(self):
        self.is_running = False
        if self._background_task:
            self._background_task.cancel()
            self._background_task = None
        self._release_process_lock()
        logger.info("AutoUpdater: Đã dừng tiến trình kiểm tra ngầm.")

auto_updater = AutoUpdater()
