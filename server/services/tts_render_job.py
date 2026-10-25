"""Kiểu dữ liệu của hàng đợi dựng giọng: việc dựng, số đo RTF và các hằng số ưu tiên (xem tts_prerender.py)."""

import os
import threading
import time
from collections import deque
from concurrent.futures import Future
from dataclasses import dataclass, field
from typing import Deque, Hashable, Optional, Set, Tuple

from services import tts_catalog_data
from services.tts_local_catalog import active_mode

PRIORITY_ONDEMAND, PRIORITY_CURRENT, PRIORITY_UPCOMING, PRIORITY_BACKGROUND = 0, 1, 2, 3
QUEUED, RUNNING, DONE, FAILED_STATE = "queued", "running", "done", "failed"
MAX_ONDEMAND_PENDING = 24
FAILURE_COOLDOWN_SECONDS = 60


def prior_seconds_per_char() -> float:
    """Giây âm thanh / ký tự trước khi đo được (đo với mô hình thật: Nano ~0,09, Turbo ~0,065)."""
    return tts_catalog_data.PRIOR_SECONDS_PER_CHAR[active_mode()]


class QueueFull(Exception):
    """Hàng đợi đầy (hoặc đoạn này vừa lỗi): việc ưu tiên thấp bị từ chối, app sẽ gọi lại sau."""


class JobFailed(Exception):
    pass


def assumed_rtf() -> float:
    """TTS_ASSUMED_RTF nếu đặt; không thì theo mô hình (Nano 0,8, Turbo 1,3 - đo trên MiniPC)."""
    default = tts_catalog_data.ASSUMED_RTF[active_mode()]
    raw = (os.getenv("TTS_ASSUMED_RTF") or "").strip()
    if not raw:
        return default
    try:
        return max(0.1, float(raw))
    except ValueError:
        return default


def queue_limit() -> int:
    try:
        return max(10, int(os.getenv("TTS_QUEUE_MAX", "2000")))
    except ValueError:
        return 2000


@dataclass(eq=False)
class RenderJob:
    md5: str
    text: str
    preset: str
    cache_file: str
    priority: int
    seq: int
    chars: int
    state: str = QUEUED
    waiters: int = 0
    failures: int = 0
    contexts: Set[Hashable] = field(default_factory=set)
    future: "Future[str]" = field(default_factory=Future)
    enqueued_at: float = field(default_factory=time.monotonic)


class RenderStats:
    """RTF = giây tính toán / giây âm thanh, trung bình có trọng số trên 30 lần dựng gần nhất."""

    def __init__(self, window: int = 30):
        self._samples: Deque[Tuple[float, float, int]] = deque(maxlen=window)
        self.total = 0
        self._lock = threading.Lock()

    def record(self, compute_s: float, audio_s: float, chars: int) -> None:
        if audio_s <= 0 or compute_s <= 0:
            return
        with self._lock:
            self._samples.append((compute_s, audio_s, max(chars, 1)))
            self.total += 1

    def rtf(self) -> Optional[float]:
        with self._lock:
            audio = sum(a for _, a, _ in self._samples)
            return round(sum(c for c, _, _ in self._samples) / audio, 3) if audio > 0 else None

    def effective_rtf(self) -> float:
        """RTF dùng để ước lượng: số đo thật nếu đã có, chưa thì giá trị giả định theo mô hình."""
        measured = self.rtf()
        return assumed_rtf() if measured is None else max(measured, 0.01)

    def seconds_per_char(self) -> float:
        with self._lock:
            chars = sum(n for _, _, n in self._samples)
            return sum(a for _, a, _ in self._samples) / chars if chars else prior_seconds_per_char()
