"""Engine giọng giả cho test: không nạp mô hình, không ra mạng, sinh sóng sin độ dài tỉ lệ với văn bản."""

import threading
import time
from typing import List, Optional, Set, Tuple

import numpy as np

from services.tts_local import local_tts


class FakeEngine:
    """Giống TtsEngine: synth(text, voice) -> (float32 một chiều, tần số lấy mẫu)."""

    def __init__(self, sample_rate: int = 24_000, seconds_per_char: float = 0.05, delay: float = 0.0,
                 fail_on: Optional[Set[str]] = None, voices: Optional[List[str]] = None):
        self.sample_rate = sample_rate
        self.seconds_per_char = seconds_per_char
        self.delay = delay
        self.fail_on = fail_on or set()
        self.voices = voices
        self.calls: List[Tuple[str, str]] = []
        self.started: List[str] = []
        self.gate: Optional[threading.Event] = None  # đặt để giữ synth đứng lại cho tới khi gate.set()
        self._lock = threading.Lock()

    def synth(self, text: str, voice_name: str):
        with self._lock:
            self.started.append(text)
        if self.gate is not None:
            self.gate.wait(10)
        if self.delay:
            time.sleep(self.delay)
        with self._lock:
            self.calls.append((text, voice_name))
        if any(marker in text for marker in self.fail_on):
            raise RuntimeError("engine giả: lỗi theo yêu cầu")
        count = max(int(len(text) * self.seconds_per_char * self.sample_rate), self.sample_rate // 5)
        t = np.arange(count, dtype=np.float32) / self.sample_rate
        return (0.2 * np.sin(2 * np.pi * 220.0 * t)).astype(np.float32), self.sample_rate


def install(engine: Optional[FakeEngine] = None) -> FakeEngine:
    engine = engine or FakeEngine()
    local_tts.install(engine)
    return engine


def wait_until(predicate, timeout: float = 5.0, step: float = 0.01) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(step)
    return predicate()
