"""Background refinement queue: Hy-MT2 rewrites modern book titles and chapter subtitles the dictionary got wrong.

Single asyncio worker, dedup by (kind, text), priorities (book titles from search/discover first, then other book
titles, then TOC subtitles), capped. Polite: works only while the MT server answers /health, one request at a
time with a short pause, so VieNeu-TTS (same CPU) keeps priority. Pending items are also written to the title cache
('dict' rows) and reloaded after a restart. Requests never wait on this queue.
"""

import asyncio
import heapq
import itertools
import logging
import os
from typing import Dict, List, Optional, Set, Tuple

from services.title_cache_store import title_cache
from services.title_mt_client import title_mt

logger = logging.getLogger("truyencloud.title_refiner")

PRIORITY_SEARCH_BOOK, PRIORITY_BOOK, PRIORITY_CHAPTER, PRIORITY_RESTORED = 0, 1, 2, 3
# Đuôi mục lục dài (ngoài TITLE_REFINE_TOC_SCAN chương đầu): kém ưu tiên nhất để không chiếm chỗ đầu mục lục truyện khác.
PRIORITY_TOC_TAIL = PRIORITY_RESTORED
# Dài hơn thế không còn là tựa (đoạn văn lọt vào ô tựa): bản dịch sẽ vượt max_tokens và bị bỏ, khỏi tốn lượt mô hình.
MAX_SRC_CHARS = 80


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, "") or default)
    except ValueError:
        return default


def tts_listener_waiting() -> bool:
    """Giọng đọc đang phục vụ người nghe (không tính dựng nền) — tinh chỉnh tựa đề chờ tới khi xong."""
    try:
        from services.tts_prerender import render_queue
        return render_queue.listener_waiting()
    except Exception:
        return False


class _Job:
    __slots__ = ("kind", "src", "dict_vi", "novels", "priority")

    def __init__(self, kind: str, src: str, dict_vi: str, priority: int):
        self.kind, self.src, self.dict_vi, self.priority = kind, src, dict_vi, priority
        self.novels: Set[str] = set()


class TitleRefiner:
    def __init__(self):
        self._jobs: Dict[Tuple[str, str], _Job] = {}
        # Mức ưu tiên -> các khoá đang chờ (dict giữ thứ tự chèn): tìm mục để đẩy ra khi đầy là O(1), không quét
        # cả hàng đợi trên event loop cho mỗi tựa của một mục lục nghìn chương.
        self._buckets: Dict[int, Dict[Tuple[str, str], None]] = {}
        self._heap: List[Tuple[int, int, Tuple[str, str]]] = []
        self._seq = itertools.count()
        self._unsaved: List[_Job] = []
        self._wake: Optional[asyncio.Event] = None
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._task: Optional[asyncio.Task] = None
        self.generation = 0  # tăng mỗi khi có bản tinh chỉnh mới (bộ quét TOC dựa vào để biết cần quét lại)
        self.stats = {"done": 0, "failed": 0, "dropped": 0}

    @property
    def max_items(self) -> int:
        return _env_int("TITLE_REFINE_QUEUE_MAX", 3000)

    @property
    def enabled(self) -> bool:
        return title_mt.enabled and (os.getenv("TITLE_REFINE_ENABLED", "1").strip() or "1") != "0"

    def __len__(self) -> int:
        return len(self._jobs)

    def enqueue(self, kind: str, src: str, dict_vi: str, priority: int, novel_id: Optional[str] = None) -> bool:
        """Đưa một tựa vào hàng đợi (không chặn, không I/O). False nếu bỏ qua."""
        if not self.enabled or not src or len(src) > MAX_SRC_CHARS:
            return False
        key = (kind, src)
        job = self._jobs.get(key)
        changed = False
        if job is None:
            hit = title_cache.get(kind, src)
            if hit and hit[1] in ("llm", "final"):
                return False
            if len(self._jobs) >= self.max_items and not self._evict_for(priority):
                self.stats["dropped"] += 1
                return False
            job = _Job(kind, src, dict_vi, priority)
            self._add(job)
            changed = True
        elif priority < job.priority:
            self._buckets.get(job.priority, {}).pop(key, None)
            job.priority = priority
            self._buckets.setdefault(priority, {})[key] = None
            heapq.heappush(self._heap, (priority, next(self._seq), key))
        if novel_id and novel_id not in job.novels:
            job.novels.add(novel_id)
            changed = True
        if changed:
            self._unsaved.append(job)
        self._notify()
        return True

    def _notify(self) -> None:
        """Đánh thức worker; asyncio.Event không an toàn luồng nên từ luồng khác phải đi qua call_soon_threadsafe."""
        if self._wake is None or self._loop is None:
            return
        try:
            running = asyncio.get_running_loop()
        except RuntimeError:
            running = None
        if running is self._loop:
            self._wake.set()
        elif not self._loop.is_closed():
            self._loop.call_soon_threadsafe(self._wake.set)

    def _add(self, job: _Job) -> None:
        key = (job.kind, job.src)
        self._jobs[key] = job
        self._buckets.setdefault(job.priority, {})[key] = None
        heapq.heappush(self._heap, (job.priority, next(self._seq), key))
        if len(self._heap) > 4 * len(self._jobs) + 1000:
            # Mục bị đẩy ra / đổi ưu tiên để lại bản ghi cũ trong heap: dựng lại cho gọn.
            self._heap = [(j.priority, next(self._seq), k) for k, j in self._jobs.items()]
            heapq.heapify(self._heap)

    def _evict_for(self, priority: int) -> bool:
        """Đẩy mục MỚI NHẤT của mức kém ưu tiên nhất (nếu kém hơn `priority`); mục cũ hơn giữ chỗ (FIFO)."""
        worst = max((p for p, keys in self._buckets.items() if keys), default=None)
        if worst is None or worst <= priority:
            return False
        key, _ = self._buckets[worst].popitem()
        self._jobs.pop(key, None)
        self.stats["dropped"] += 1
        return True

    def _pop(self) -> Optional[_Job]:
        while self._heap:
            priority, _, key = heapq.heappop(self._heap)
            job = self._jobs.get(key)
            if job is not None and job.priority == priority:
                del self._jobs[key]
                self._buckets.get(priority, {}).pop(key, None)
                return job
        return None

    def restore(self, rows: Optional[List[Tuple[str, str, List[str]]]] = None) -> int:
        """Nạp lại các mục 'dict' còn chờ từ cache sau khi khởi động lại (chạy trên event loop: không khoá)."""
        n = 0
        for kind, src, novels in (title_cache.pending(self.max_items) if rows is None else rows):
            if (kind, src) in self._jobs or len(self._jobs) >= self.max_items:
                continue
            job = _Job(kind, src, "", PRIORITY_RESTORED)
            job.novels.update(novels)
            self._add(job)
            n += 1
        return n

    async def _flush_unsaved(self) -> None:
        if not self._unsaved:
            return
        batch, self._unsaved = self._unsaved, []
        rows = [(j.kind, j.src, j.dict_vi or j.src, j.novels) for j in batch if j.kind != "query"]
        await asyncio.to_thread(title_cache.add_pending, rows)

    def start(self) -> None:
        if not self.enabled or (self._task is not None and not self._task.done()):
            return
        self._wake = asyncio.Event()
        self._loop = asyncio.get_running_loop()
        self._task = asyncio.create_task(self._run(), name="title-refiner")

    async def stop(self) -> None:
        task, self._task = self._task, None
        if task is not None:
            task.cancel()
            try:
                await task
            except (asyncio.CancelledError, Exception):
                pass

    async def _run(self) -> None:
        pause = _env_int("TITLE_REFINE_PAUSE_MS", 2500) / 1000
        try:
            # Chỉ đọc sqlite ở luồng phụ; ghép vào hàng đợi trên event loop (enqueue của request chạy cùng luồng).
            rows = await asyncio.to_thread(title_cache.pending, self.max_items)
            restored = self.restore(rows)
            if restored:
                logger.info("Khôi phục %d tựa đang chờ tinh chỉnh.", restored)
        except Exception as e:
            logger.warning("Không khôi phục được hàng đợi tựa đề: %s", e)
        while True:
            try:
                await self._flush_unsaved()
                if not self._jobs:
                    self._wake.clear()
                    try:
                        await asyncio.wait_for(self._wake.wait(), timeout=30)
                    except asyncio.TimeoutError:
                        pass
                    continue
                if not await title_mt.healthy():
                    await asyncio.sleep(30)
                    continue
                if tts_listener_waiting():
                    self.stats["yielded"] = self.stats.get("yielded", 0) + 1
                    await asyncio.sleep(3)  # có người đang nghe giọng đọc: nhường CPU cho VieNeu-TTS
                    continue
                job = self._pop()
                if job is not None:
                    await self.process(job)
                await asyncio.sleep(pause)
            except asyncio.CancelledError:
                raise
            except Exception as e:
                logger.warning("Lỗi vòng tinh chỉnh tựa đề: %s", e)
                await asyncio.sleep(5)

    async def process(self, job: _Job) -> Optional[str]:
        from services.title_refine_jobs import refine_one
        vi = await refine_one(job.kind, job.src)
        if not vi and not await title_mt.healthy(max_age=0):
            # Máy chủ mô hình vừa sập / khởi động lại: không tính là một lần thất bại, xếp lại hàng đợi.
            if (job.kind, job.src) not in self._jobs:
                self._add(job)
            return None
        if not vi:
            self.stats["failed"] += 1
            await asyncio.to_thread(title_cache.mark_failed, job.kind, job.src)
            return None
        self.stats["done"] += 1
        self.generation += 1
        await asyncio.to_thread(title_cache.put_llm, job.kind, job.src, vi)
        from services.title_patcher import apply_refined
        await asyncio.to_thread(apply_refined, job.kind, job.src, vi, sorted(job.novels))
        return vi

    def status(self) -> dict:
        kinds: Dict[str, int] = {}
        for kind, _src in self._jobs:
            kinds[kind] = kinds.get(kind, 0) + 1
        return {"enabled": self.enabled, "running": self._task is not None and not self._task.done(),
                "queued": len(self._jobs), "by_kind": kinds, "generation": self.generation, **self.stats}

    def reset(self) -> None:
        self._jobs.clear()
        self._buckets.clear()
        self._heap.clear()
        self._unsaved.clear()
        self.generation = 0
        self.stats = {"done": 0, "failed": 0, "dropped": 0}


title_refiner = TitleRefiner()
