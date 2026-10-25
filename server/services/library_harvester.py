"""Bộ Thu Thập Truyện Tự Động Theo Chủ Đề (`Auto-Seeder / Topic Harvester`) cho Kho 1TB trên MiniPC.

Quét tự động song song qua các từ khoá chủ đề trên `piaotia`, `uukanshu`, `69shuba`,
nhập mục lục vào ổ 1TB, dịch mượt tên + văn án tiếng Việt, lưu ảnh bìa offline,
tải nhanh các chương mở đầu và xếp hàng tải ngầm chương gốc + dịch Gemini.
"""

import asyncio
import logging
import time
from typing import Any, Dict, List, Set, Tuple

from database import db
from routes.scrape import perform_scrape
from services.cover_service import cover_service
from services.harvester_packs import TOPIC_PACKS, list_topic_packs
from services.library_index import library_index
from services.novel_chapter_stats import invalidate_chapter_counts, polish_novel_synopsis_vi
from services.offline_mirror_service import mirror_cover_to_disk
from services.scraper_registry import registry
from services.search_title_polisher import polish_modern_search_title
from services.storage_service import storage_service
from services.text_similarity import to_simplified

logger = logging.getLogger("truyencloud.harvester")


class LibraryHarvester:
    """Tiến trình thu thập truyện hàng loạt chạy nền vào ổ 1TB."""

    def __init__(self) -> None:
        self.running: bool = False
        self.cancelled: bool = False
        self.pack_id: str = ""
        self.pack_name: str = ""
        self.target_novels: int = 0
        self.imported_count: int = 0
        self.skipped_existing: int = 0
        self.failed_count: int = 0
        self.raw_chapters_downloaded: int = 0
        self.gemini_chapters_queued: int = 0
        self.current_keyword: str = ""
        self.current_novel: str = ""
        self.message: str = "Sẵn sàng thu thập truyện tự động vào ổ 1TB."
        self.recent_titles: List[str] = []
        self.started_at: float = 0.0

    def status(self) -> Dict[str, Any]:
        state = "running" if self.running else ("stopping" if self.cancelled else "idle")
        return {
            "status": state,
            "running": self.running,
            "pack": self.pack_id,
            "pack_id": self.pack_id,
            "pack_label": self.pack_name,
            "pack_name": self.pack_name,
            "target_novels": self.target_novels,
            "imported_novels": self.imported_count,
            "imported_count": self.imported_count,
            "skipped_existing": self.skipped_existing,
            "failed_count": self.failed_count,
            "raw_chapters_downloaded": self.raw_chapters_downloaded,
            "gemini_chapters_queued": self.gemini_chapters_queued,
            "current_keyword": self.current_keyword,
            "current_novel": self.current_novel,
            "message": self.message,
            "recent_titles": list(self.recent_titles[-12:]),
            "packs": list_topic_packs(),
        }

    def stop(self) -> Dict[str, Any]:
        if not self.running:
            self.cancelled = False
            return self.status()
        self.cancelled = True
        self.message = "Đang dừng thu thập sau bộ truyện hiện tại..."
        return self.status()

    def start(
        self,
        pack_id: str = "all",
        max_novels: int = 50,
        raw_chapters: int = 20,
        pretranslate_chapters: int = 5,
    ) -> Dict[str, Any]:
        if self.running:
            return self.status()
        packs = {p["id"]: p for p in list_topic_packs()}
        chosen = packs.get(pack_id) or packs["all"]
        self.running = True
        self.cancelled = False
        self.pack_id = chosen["id"]
        self.pack_name = chosen["name"]
        self.target_novels = max(1, min(500, int(max_novels or 50)))
        self.imported_count = 0
        self.skipped_existing = 0
        self.failed_count = 0
        self.raw_chapters_downloaded = 0
        self.gemini_chapters_queued = 0
        self.current_keyword = ""
        self.current_novel = ""
        self.recent_titles = []
        self.started_at = time.time()
        self.message = f"Đang khởi động thu thập '{self.pack_name}' (mục tiêu {self.target_novels} bộ)..."
        asyncio.create_task(
            self._run(
                chosen["keywords"],
                self.target_novels,
                max(0, min(200, raw_chapters)),
                max(0, min(50, pretranslate_chapters)),
            )
        )
        return self.status()

    def _existing_keys(self) -> Set[str]:
        keys: Set[str] = set()
        for entry in library_index.get_entries():
            nv = entry.get("novel") or {}
            if nv.get("id"):
                keys.add(str(nv["id"]))
            if nv.get("source_url"):
                keys.add(str(nv["source_url"]).rstrip("/"))
            if nv.get("title"):
                norm = cover_service.normalize_key(to_simplified(str(nv["title"])))
                if norm:
                    keys.add(norm)
        return keys

    async def _search_keyword_parallel(self, kw: str, sources: Tuple[str, ...]) -> List[Any]:
        async def _one(sid: str) -> List[Any]:
            scraper = registry.get_by_source_id(sid)
            if not scraper:
                return []
            try:
                res = await asyncio.wait_for(scraper.search(kw), timeout=6.5)
                return res if isinstance(res, list) else []
            except Exception:
                return []

        results = await asyncio.gather(*(_one(s) for s in sources))
        merged: List[Any] = []
        for r in results:
            merged.extend(r)
        return merged

    async def _run(
        self,
        keywords: List[str],
        target_novels: int,
        raw_chapters: int,
        pretranslate_chapters: int = 5,
    ) -> None:
        from routes.chapters import get_chapter

        known = self._existing_keys()
        deferred_jobs: List[Tuple[str, int, int]] = []
        sources = ("piaotia", "uukanshu", "69shuba")
        pack_kw_simp = tuple(dict.fromkeys(to_simplified(k) for k in keywords if k))
        max_per_kw = max(4, min(10, (target_novels // max(1, len(keywords))) + 4))
        try:
            for kw in keywords:
                if self.cancelled or self.imported_count >= target_novels:
                    break
                kw_simp = to_simplified(kw)
                self.current_keyword = kw
                self.message = f"Đang tìm nguồn truyện cho chủ đề '{kw}' ({self.imported_count}/{target_novels} bộ)..."
                raw_candidates = await self._search_keyword_parallel(kw, sources)

                candidates = [
                    c for c in raw_candidates
                    if any(
                        pk in to_simplified((c.title or "") + " " + (getattr(c, "description", "") or getattr(c, "synopsis", "") or ""))
                        for pk in pack_kw_simp
                    )
                ]
                candidates.sort(
                    key=lambda c: (0 if kw_simp in to_simplified(c.title or "") else 1)
                )

                kw_added = 0
                for item in candidates:
                    if self.cancelled or self.imported_count >= target_novels or kw_added >= max_per_kw:
                        break
                    url = (item.url or "").strip().rstrip("/")
                    norm_t = cover_service.normalize_key(to_simplified(item.title or ""))
                    if not url or not url.startswith("http"):
                        continue
                    if url in known or (norm_t and norm_t in known):
                        self.skipped_existing += 1
                        continue

                    known.add(url)
                    if norm_t:
                        known.add(norm_t)

                    self.current_novel = item.title_vi or item.title or url
                    self.message = f"Đang nhập kho '{self.current_novel}' ({self.imported_count + 1}/{target_novels})..."
                    try:
                        detail = await asyncio.wait_for(perform_scrape(url), timeout=22.0)
                        info = storage_service.get_novel_info(detail.id) or {}
                        polished_title = polish_modern_search_title(detail.title, detail.title_vi or "")
                        polished_desc = polish_novel_synopsis_vi(detail.description or "")
                        updates: Dict[str, Any] = {}
                        if polished_title and polished_title != detail.title_vi:
                            detail.title_vi = polished_title
                            info["title_vi"] = polished_title
                            updates["title_vi"] = polished_title
                        if polished_desc and polished_desc != detail.description:
                            detail.description = polished_desc
                            info["description"] = polished_desc
                            updates["description"] = polished_desc
                        if updates and info:
                            storage_service.save_novel_info(detail.id, info)
                            try:
                                db.table("novels").update(updates).eq("id", detail.id).execute()
                            except Exception:
                                pass

                        await mirror_cover_to_disk(detail.cover_url or "")

                        # Tải ngay 5 chương mở đầu để đọc tức thì, phần còn lại đẩy sang hàng đợi nền để nhập nhanh hàng trăm bộ
                        total_ch = max(0, detail.total_chapters or raw_chapters)
                        max_raw = min(raw_chapters, total_ch)
                        immediate_raw = min(5, max_raw)
                        for ch_num in range(1, immediate_raw + 1):
                            if self.cancelled:
                                break
                            try:
                                await asyncio.wait_for(get_chapter(detail.id, ch_num, recrawl=False), timeout=10.0)
                                self.raw_chapters_downloaded += 1
                            except Exception:
                                pass

                        invalidate_chapter_counts(detail.id)
                        library_index.invalidate()
                        self.imported_count += 1
                        kw_added += 1
                        self.recent_titles.append(detail.title_vi or detail.title)
                        max_pre = min(pretranslate_chapters, max(1, total_ch)) if pretranslate_chapters > 0 else 0
                        if max_raw > immediate_raw or max_pre > 0:
                            deferred_jobs.append((detail.id, max_raw, max_pre))
                            self.gemini_chapters_queued += max_pre
                        await asyncio.sleep(0.2)
                    except Exception as exc:
                        self.failed_count += 1
                        logger.debug(f"Bỏ qua truyện '{url}': {exc}")

            if deferred_jobs and not self.cancelled:
                asyncio.create_task(self._drain_background_queues(list(deferred_jobs)))

            if self.cancelled:
                self.message = (
                    f"Đã dừng thu thập: nhập mới {self.imported_count} bộ truyện "
                    f"({self.raw_chapters_downloaded} chương gốc) vào ổ 1TB."
                )
            else:
                self.message = (
                    f"Hoàn tất nhập nhanh {self.imported_count} bộ truyện mới! "
                    f"Đang tiếp tục tải ngầm chương gốc và dịch Gemini ({self.gemini_chapters_queued} chương)."
                )
        finally:
            self.running = False
            self.cancelled = False
            self.current_keyword = ""
            self.current_novel = ""
            library_index.invalidate()

    async def _drain_background_queues(self, deferred_jobs: List[Tuple[str, int, int]]) -> None:
        from routes.chapters import get_chapter
        from services.batch_translation_runner import batch_translation_runner
        from services.job_registry import job_registry

        for nid, max_raw, max_pre in deferred_jobs:
            for ch_num in range(6, max_raw + 1):
                try:
                    await asyncio.wait_for(get_chapter(nid, ch_num, recrawl=False), timeout=10.0)
                    self.raw_chapters_downloaded += 1
                except Exception:
                    pass
                await asyncio.sleep(0.15)
            invalidate_chapter_counts(nid)
            if max_pre > 0:
                chs = list(range(1, max_pre + 1))
                owner = f"harvester_{nid}"
                job = job_registry.create("batch_translation", owner)
                batch_translation_runner.activate(owner, nid, job.id, chs)
                try:
                    await batch_translation_runner.run(owner, nid, chs, [], job.id, False)
                except Exception as exc:
                    logger.debug(f"Lỗi dịch nền Gemini cho '{nid}': {exc}")


library_harvester = LibraryHarvester()
