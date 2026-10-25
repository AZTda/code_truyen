"""Hàng đợi dựng giọng: MỘT worker, ưu tiên 0..3, loại trùng theo khoá cache, số đo tốc độ dựng thực tế.

Độ ưu tiên (số nhỏ chạy trước): 0 = /stream chưa có trong cache (người nghe đang chờ), 1 = chương đang đọc,
2 = các chương sắp tới, 3 = nền. Worker chạy từng đoạn một và không bị ngắt giữa chừng, nên một việc ưu tiên thấp
đang chạy chỉ làm việc ưu tiên 0 chờ tối đa MỘT đoạn. Việc ưu tiên 0 mà không còn ai chờ (app đã huỷ yêu cầu) tự hạ về 1.
Đĩa là nguồn sự thật: khởi động lại thì tính lại từ cache, hàng đợi không cần lưu.
Người dùng đổi ý: chương của lời gọi /prerender mới nhất lên đầu mức ưu tiên của nó (`bring_to_front`), giọng cũ của chương vừa
đổi giọng bị hạ về nền (`demote`), hàng đợi đầy thì việc kém quan trọng nhất nhường chỗ.
"""

import asyncio
import heapq
import itertools
import logging
import os
import threading
import time
from collections import OrderedDict
from typing import Callable, Dict, Hashable, List, Optional, Tuple

from services import tts_cache
from services.tts_audio import pcm_to_mp3
from services.tts_local import FAILED, UNLOADED, EngineUnavailable, local_tts, lower_thread_priority
from services.tts_render_job import (  # noqa: F401  (các tên này vẫn import được từ services.tts_prerender)
    DONE,
    FAILED_STATE,
    FAILURE_COOLDOWN_SECONDS,
    MAX_ONDEMAND_PENDING,
    PRIORITY_BACKGROUND,
    PRIORITY_CURRENT,
    PRIORITY_ONDEMAND,
    PRIORITY_UPCOMING,
    QUEUED,
    RUNNING,
    JobFailed,
    QueueFull,
    RenderJob,
    RenderStats,
    queue_limit,
)

logger = logging.getLogger("truyencloud.tts.queue")


class RenderQueue:
    def __init__(self) -> None:
        self._cond = threading.Condition()
        self._heap: List[Tuple[int, int, str]] = []
        self._jobs: Dict[str, RenderJob] = {}
        self._seq = itertools.count()
        self._queued = 0
        self._running: Optional[RenderJob] = None
        self._thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()  # mỗi worker một cờ riêng: reset() không làm sống lại worker cũ
        self._failed: Dict[str, Tuple[int, float]] = {}
        self._ctx: "OrderedDict[Hashable, int]" = OrderedDict()  # ngữ cảnh -> số đoạn đã xong trong tiến trình này
        self._front_batches = itertools.count()
        self._front_key: Optional[Tuple] = None
        self.stats = RenderStats()

    # ---- gửi việc
    def submit(self, plan, priority: int, context: Optional[Hashable] = None) -> RenderJob:
        """Xếp (hoặc gộp) việc dựng cho một TtsPlan loại render. Không chờ. Ném QueueFull khi bị từ chối."""
        with self._cond:
            existing = self._jobs.get(plan.md5)
            if existing is not None:
                self._tag(existing, context)
                if priority < existing.priority and existing.state == QUEUED:
                    self._reprioritize(existing, priority)
                return existing
            failures, until = self._failed.get(plan.md5, (0, 0.0))
            if failures and time.monotonic() < until:
                raise QueueFull("đoạn này vừa dựng lỗi, đang chờ thử lại")
            if priority == PRIORITY_ONDEMAND:
                pending = sum(1 for j in self._jobs.values() if j.priority == PRIORITY_ONDEMAND and j.state == QUEUED)
                if pending >= MAX_ONDEMAND_PENDING:
                    raise QueueFull("quá nhiều yêu cầu nghe trực tiếp đang chờ")
            elif self._queued >= queue_limit() and not self._evict_for_locked(priority):
                raise QueueFull("hàng đợi dựng giọng đã đầy")
            job = RenderJob(plan.md5, plan.norm_text, plan.preset_name, plan.cache_file, priority, next(self._seq),
                            len(plan.norm_text), failures=failures)
            if priority == PRIORITY_ONDEMAND and not local_tts.is_ready():
                if local_tts.state in (UNLOADED, FAILED):
                    local_tts.request()
                job.state = FAILED_STATE
                job.future.set_exception(local_tts.unavailable())
                return job
            self._tag(job, context)
            self._jobs[plan.md5] = job
            self._queued += 1
            heapq.heappush(self._heap, (job.priority, job.seq, job.md5))
            self._ensure_worker()
            self._cond.notify_all()
            return job

    def _tag(self, job: RenderJob, context: Optional[Hashable]) -> None:
        if context is None:
            return
        job.contexts.add(context)
        self._ctx.setdefault(context, 0)
        self._ctx.move_to_end(context)
        while len(self._ctx) > 512:
            self._ctx.popitem(last=False)

    def _reprioritize(self, job: RenderJob, priority: int, seq: Optional[int] = None) -> None:
        job.priority = priority
        job.seq = next(self._seq) if seq is None else seq
        heapq.heappush(self._heap, (job.priority, job.seq, job.md5))
        self._cond.notify_all()

    def _evict_for_locked(self, priority: int) -> bool:
        """Hàng đợi đầy: bỏ việc kém quan trọng nhất (ưu tiên số lớn hơn, vào sau cùng) để nhận việc `priority`."""
        victims = [j for j in self._jobs.values() if j.state == QUEUED and j.waiters <= 0 and j.priority > priority]
        if not victims:
            return False
        self._finish_locked(max(victims, key=lambda j: (j.priority, j.seq)), error=QueueFull("bị việc quan trọng hơn thay chỗ"))
        return True

    # ---- người dùng đổi ý (đổi giọng, mở chương khác)
    def bring_to_front(self, md5s: List[str], priority: int) -> int:
        """Đưa các việc đang chờ của lời gọi dựng trước MỚI NHẤT lên đầu mức `priority`, giữ đúng thứ tự `md5s`.

        Người dùng vừa mở chương / đổi giọng thì chương đó cần trước những chương mở trước đó; việc ưu tiên cao hơn (0) không
        bị đụng. Gọi lại với đúng khối đang đứng đầu (app hỏi tiến độ mỗi 2 giây) thì không làm gì.
        """
        with self._cond:
            key = (priority, tuple(md5s))
            if key == self._front_key:
                return 0
            self._front_key = key
            base = -(next(self._front_batches) + 1) * 10_000_000
            moved = 0
            for offset, md5 in enumerate(md5s):
                job = self._jobs.get(md5)
                if job is not None and job.state == QUEUED and job.priority >= priority:
                    self._reprioritize(job, priority, base + offset)
                    moved += 1
            return moved

    def demote(self, superseded: Callable[[Hashable], bool]) -> int:
        """Hạ về nền (3) việc ưu tiên 1-2 đang chờ mà MỌI ngữ cảnh đều đã bị thay (vd. giọng cũ của chương vừa đổi giọng).

        Không xoá: ai còn cần (gọi /prerender lại với ngữ cảnh đó) thì được nâng lại ngay.
        """
        with self._cond:
            moved = 0
            for job in list(self._jobs.values()):
                if (job.state == QUEUED and PRIORITY_ONDEMAND < job.priority < PRIORITY_BACKGROUND and job.contexts
                        and job.waiters <= 0 and all(superseded(ctx) for ctx in job.contexts)):
                    self._reprioritize(job, PRIORITY_BACKGROUND)
                    moved += 1
            if moved:
                self._front_key = None
            return moved

    # ---- người chờ (on-demand)
    def attach(self, job: RenderJob) -> None:
        with self._cond:
            job.waiters += 1

    def release(self, job: RenderJob) -> None:
        with self._cond:
            job.waiters -= 1
            if job.waiters <= 0 and job.state == QUEUED and job.priority == PRIORITY_ONDEMAND:
                self._reprioritize(job, PRIORITY_CURRENT)  # không ai nghe nữa: đừng chặn người khác

    async def wait(self, job: RenderJob, timeout: float) -> str:
        """Chờ job xong (đường dẫn tệp). Hết giờ: ném EngineUnavailable('timeout'); job vẫn chạy tiếp để lần sau trúng cache."""
        self.attach(job)
        try:
            inner = asyncio.wrap_future(job.future)
            inner.add_done_callback(lambda f: f.cancelled() or f.exception())  # tránh cảnh báo "never retrieved"
            return await asyncio.wait_for(asyncio.shield(inner), timeout)
        except asyncio.TimeoutError:
            raise EngineUnavailable("timeout", 5)
        finally:
            self.release(job)

    # ---- worker
    def _ensure_worker(self) -> None:
        if self._thread is not None and self._thread.is_alive() and not self._stop_event.is_set():
            return
        self._stop_event = threading.Event()
        self._thread = threading.Thread(target=self._loop, args=(self._stop_event,), name="tts-render", daemon=True)
        self._thread.start()

    def _pop_locked(self) -> Optional[RenderJob]:
        while self._heap:
            priority, seq, md5 = heapq.heappop(self._heap)
            job = self._jobs.get(md5)
            if job is None or job.state != QUEUED or job.priority != priority or job.seq != seq:
                continue  # mục cũ đã bị gộp / đổi ưu tiên
            return job
        return None

    def _fail_ondemand_locked(self, exc: Exception) -> None:
        for md5, job in list(self._jobs.items()):
            if job.state == QUEUED and job.priority == PRIORITY_ONDEMAND:
                self._finish_locked(job, error=exc)

    def _finish_locked(self, job: RenderJob, path: Optional[str] = None, error: Optional[Exception] = None) -> None:
        if job.state == QUEUED:
            self._queued -= 1
        self._jobs.pop(job.md5, None)
        if error is None:
            job.state = DONE
            self._failed.pop(job.md5, None)
            for ctx in job.contexts:
                if ctx in self._ctx:
                    self._ctx[ctx] += 1
            if not job.future.done():
                job.future.set_result(path or job.cache_file)
        else:
            job.state = FAILED_STATE
            if not isinstance(error, (EngineUnavailable, QueueFull)):  # bị thay chỗ không phải lỗi dựng
                count = job.failures + 1
                self._failed[job.md5] = (count, time.monotonic() + FAILURE_COOLDOWN_SECONDS * min(count, 10))
            if not job.future.done():
                job.future.set_exception(error)

    def _take(self, stop: threading.Event) -> Optional[RenderJob]:
        with self._cond:
            while not stop.is_set():
                if not local_tts.is_ready():
                    self._fail_ondemand_locked(local_tts.unavailable())
                    if local_tts.state in (UNLOADED, FAILED):
                        local_tts.request()  # việc đang chờ mà engine chưa nạp/đang lỗi: thử nạp (có lùi thời gian)
                    self._cond.wait(timeout=1.0)
                    continue
                job = self._pop_locked()
                if job is not None:
                    job.state = RUNNING
                    self._queued -= 1
                    self._running = job
                    return job
                self._cond.wait(timeout=1.0)
        return None

    def _loop(self, stop: threading.Event) -> None:
        lower_thread_priority()
        while True:
            job = self._take(stop)
            if job is None:
                return
            self._execute(job)

    def _execute(self, job: RenderJob) -> None:
        error: Optional[Exception] = None
        path: Optional[str] = None
        try:
            if tts_cache.is_cached(job.cache_file):
                path = job.cache_file
            else:
                started = time.perf_counter()
                pcm, rate = local_tts.synth_blocking(job.text, job.preset)
                compute = time.perf_counter() - started
                mp3, duration_ms = pcm_to_mp3(pcm, rate)
                path = tts_cache.write_audio(os.path.dirname(job.cache_file), job.md5, mp3, duration_ms)
                self.stats.record(compute, duration_ms / 1000.0, job.chars)
                local_tts.report_success()
                tts_cache.sweeper.maybe_run(os.path.dirname(job.cache_file))
        except EngineUnavailable as exc:
            error = exc
        except Exception as exc:
            logger.warning("Dựng giọng lỗi (%s ký tự, giọng %s): %s: %s", job.chars, job.preset, type(exc).__name__, exc)
            local_tts.report_failure(exc)
            error = JobFailed(f"{type(exc).__name__}: {exc}")
        with self._cond:
            self._running = None
            self._finish_locked(job, path=path, error=error)

    # ---- quan sát
    def length(self) -> int:
        with self._cond:
            return self._queued

    def is_running_context(self, context: Hashable) -> bool:
        with self._cond:
            return self._running is not None and context in self._running.contexts

    def context_done(self, context: Hashable) -> int:
        with self._cond:
            return self._ctx.get(context, 0)

    def pending_for(self, md5: str) -> Optional[RenderJob]:
        with self._cond:
            return self._jobs.get(md5)

    def backlog_seconds(self, before_priority: int) -> float:
        """Giây tính toán ước lượng của các việc đang chờ có ưu tiên CAO HƠN `before_priority` (chạy trước nó)."""
        spc, rtf = self.stats.seconds_per_char(), self.stats.effective_rtf()
        with self._cond:
            chars = sum(j.chars for j in self._jobs.values() if j.state == QUEUED and j.priority < before_priority)
            if self._running is not None and self._running.priority < before_priority:
                chars += self._running.chars // 2
        return chars * spc * rtf

    def listener_waiting(self) -> bool:
        """Có người đang nghe (việc ưu tiên cao hơn dựng nền đang chạy/chờ): việc nền khác nên nhường CPU."""
        with self._cond:
            if self._running is not None and self._running.priority < PRIORITY_BACKGROUND:
                return True
            return any(j.state == QUEUED and j.priority < PRIORITY_BACKGROUND for j in self._jobs.values())

    def queued_by_priority(self) -> Dict[int, int]:
        with self._cond:
            out: Dict[int, int] = {}
            for job in self._jobs.values():
                if job.state == QUEUED:
                    out[job.priority] = out.get(job.priority, 0) + 1
            return out

    # ---- vòng đời
    def stop(self, join_seconds: float = 2.0) -> None:
        with self._cond:
            self._stop_event.set()
            self._cond.notify_all()
        thread = self._thread
        if thread is not None and thread.is_alive() and thread is not threading.current_thread():
            thread.join(join_seconds)

    def reset(self) -> None:
        """Dùng trong kiểm thử: dừng worker, bỏ mọi việc và số đo."""
        self.stop()
        with self._cond:
            error = EngineUnavailable("loading")
            for job in list(self._jobs.values()):
                if not job.future.done():
                    job.future.set_exception(error)
            self._heap.clear()
            self._jobs.clear()
            self._queued = 0
            self._running = None
            self._failed.clear()
            self._ctx.clear()
            self._front_key = None
            self._thread = None
            self.stats = RenderStats()


render_queue = RenderQueue()
