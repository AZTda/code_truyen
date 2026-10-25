"""Tra URL chương từ mục lục (TOC) nguồn, có cache và giới hạn tần suất theo truyện.

Ứng dụng tải trước chương N+1..N+5. Với 5 chương cuối, mỗi lượt GET một chương
vượt quá cuối truyện từng khiến máy chủ cào lại TOÀN BỘ mục lục nguồn rồi mới trả
404 — khoảng 15 lần cào mục lục cho mỗi lần đọc tới cuối. Mục lục vừa cào được
giữ 30 phút; các lượt tra trong thời gian đó (kể cả vượt cuối truyện) trả lời ngay,
và các lượt đồng thời của cùng một truyện chờ chung một lần cào.
"""

import asyncio
import logging
import time
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Dict, List, Optional, Tuple

from services.lru_dict import LruDict

logger = logging.getLogger("truyencloud.toc_lookup")

TOC_FRESH_SECONDS = 1800
# Cào lỗi thì không thử lại ngay: nguồn đang chặn/sập, dồn thêm request chỉ tệ hơn.
FAILED_RETRY_SECONDS = 60


@dataclass
class _TocEntry:
    fetched_at: float
    chapters: List[Any] = field(default_factory=list)


class TocUnavailableError(RuntimeError):
    """Mục lục nguồn vừa cào lỗi, đang trong thời gian chờ thử lại."""


class TocLookup:
    def __init__(self):
        self._entries: LruDict = LruDict(500)
        self._failed_at: LruDict = LruDict(500)
        self._locks: Dict[str, asyncio.Lock] = {}

    def remember(self, novel_id: str, chapters: List[Any]) -> None:
        """Ghi nhận mục lục vừa cào (auto_updater cũng gọi hàm này sau mỗi lượt quét).

        Mục lục RỖNG không được ghi nhận là "mới": nhiều scraper nuốt lỗi và trả [] khi
        trang bị chặn, nên ghi nó sẽ khiến mọi lượt tra chương trong 30 phút tới đều 404.
        """
        if not chapters:
            return
        self._entries[novel_id] = _TocEntry(time.time(), list(chapters))
        self._failed_at.pop(novel_id, None)

    def mark_failed(self, novel_id: str) -> None:
        """Ghi nhận lượt cào vừa thất bại (gồm cả cào "mềm" trả mục lục rỗng): tạm chưa cào lại."""
        self._failed_at[novel_id] = time.time()

    def refreshed_recently(self, novel_id: str) -> bool:
        return self._fresh(novel_id) is not None

    def _fresh(self, novel_id: str) -> Optional[_TocEntry]:
        entry = self._entries.get(novel_id)
        if entry and time.time() - entry.fetched_at < TOC_FRESH_SECONDS:
            return entry
        return None

    @staticmethod
    def _pick(chapters: List[Any], chapter_num: int) -> Optional[Tuple[str, str]]:
        target = next((c for c in chapters if getattr(c, "chapter_number", None) == chapter_num), None)
        if not target and 0 <= chapter_num - 1 < len(chapters):
            target = chapters[chapter_num - 1]
        if not target or not getattr(target, "url", None):
            return None
        return target.url, getattr(target, "title", "") or f"Chương {chapter_num}"

    async def find(
        self,
        novel_id: str,
        source_url: str,
        chapter_num: int,
        fetch: Callable[[str], Awaitable[List[Any]]],
    ) -> Optional[Tuple[str, str]]:
        """(url, tựa) của chương, hoặc None nếu mục lục (còn mới) không có chương này."""
        entry = self._fresh(novel_id)
        if entry:
            return self._pick(entry.chapters, chapter_num)

        lock = self._locks.setdefault(novel_id, asyncio.Lock())
        async with lock:
            entry = self._fresh(novel_id)
            if entry:
                return self._pick(entry.chapters, chapter_num)
            failed = self._failed_at.get(novel_id)
            if failed and time.time() - failed < FAILED_RETRY_SECONDS:
                raise TocUnavailableError("Mục lục nguồn vừa cào lỗi, thử lại sau ít phút.")
            try:
                chapters = await fetch(source_url)
            except Exception:
                self.mark_failed(novel_id)
                raise
            if not chapters:
                # Cào "mềm" thất bại (nguồn chặn, trang trống) chứ không phải truyện chưa có chương:
                # tính là lỗi để lượt sau còn được cào lại, thay vì 404 suốt 30 phút.
                self.mark_failed(novel_id)
                logger.warning(f"Mục lục truyện {novel_id} cào về rỗng, coi là lỗi nguồn.")
                raise TocUnavailableError("Mục lục nguồn trả về rỗng, thử lại sau ít phút.")
            self.remember(novel_id, chapters)
            logger.info(f"Đã cào mục lục truyện {novel_id}: {len(chapters)} chương.")
            return self._pick(chapters, chapter_num)


toc_lookup = TocLookup()
