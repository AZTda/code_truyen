"""Engine giọng đọc cục bộ: VieNeu-TTS (ONNX, CPU, không torch) bọc trong một singleton nạp lười.

Quy tắc:
- Chạy NỀN: nạp mô hình ở thread riêng lúc máy chủ khởi động (không chặn /v1/health). Chưa nạp xong/lỗi/tắt thì
  `is_ready()` là False và mọi yêu cầu giọng AI trả 503 gọn để app dùng giọng máy; không có engine nào khác.
- Một lần chỉ MỘT lần tổng hợp: chỉ worker của hàng đợi (tts_prerender.py) gọi `synth_blocking`, nên engine luôn chạy
  tuần tự, ngoài event loop, và ở thread đã hạ độ ưu tiên (nice) để chừa CPU cho ứng dụng khác chung máy.
- Engine tiêm được (Protocol `TtsEngine`): test dùng engine giả, không bao giờ nạp mô hình thật.
Biến môi trường: TTS_LOCAL_ENABLED (mặc định 1), TTS_LOCAL_MODEL (v3nano mặc định | v3turbo), TTS_LOCAL_THREADS (3),
TTS_LOCAL_NICE (5), TTS_MODEL_DIR (xem tts_model_store.py), TTS_LOCAL_WARMUP (1).
Mô hình chỉ đọc lúc nạp: đổi TTS_LOCAL_MODEL thì phải khởi động lại máy chủ (danh mục giọng và khoá cache theo nó).
"""

import logging
import os
import sys
import threading
import time
from typing import Any, Callable, Dict, List, Optional, Protocol, Tuple

from services.tts_catalog_data import MODE_NANO, MODE_TURBO
from services.tts_local_catalog import active_mode

logger = logging.getLogger("truyencloud.tts.local")

UNLOADED, LOADING, READY, FAILED, DISABLED = "unloaded", "loading", "ready", "failed", "disabled"
_BACKOFF_SECONDS = (30, 60, 120, 300, 600)
_ERRORS_BEFORE_RELOAD = 5
WARMUP_TEXT = "Xin chào, đây là giọng đọc truyện."


class EngineUnavailable(Exception):
    """Giọng local không phục vụ được lúc này; `reason`: disabled | loading | failed | busy | timeout."""

    def __init__(self, reason: str, retry_after: int = 15):
        super().__init__(reason)
        self.reason = reason
        self.retry_after = retry_after


class TtsEngine(Protocol):
    sample_rate: int

    def synth(self, text: str, voice_name: str) -> Tuple[Any, int]:
        """(mảng float32 một chiều trong [-1, 1], tần số lấy mẫu)."""


def _flag(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    return raw.strip().lower() not in ("0", "false", "no", "off")


def _int_env(name: str, default: int, low: int, high: int) -> int:
    try:
        return min(max(int(os.getenv(name, str(default))), low), high)
    except ValueError:
        return default


def lower_thread_priority(nice: Optional[int] = None) -> None:
    """Hạ độ ưu tiên CHỈ thread đang chạy (Linux: nice theo từng thread; thread con như pool của onnxruntime kế thừa)."""
    if not sys.platform.startswith("linux"):
        return
    value = _int_env("TTS_LOCAL_NICE", 5, 0, 19) if nice is None else nice
    try:
        os.setpriority(os.PRIO_PROCESS, threading.get_native_id(), value)
    except (OSError, AttributeError):
        pass


class VieneuEngine:
    """Bọc `vieneu.Vieneu` trên CPU, không torch: v3nano (ONNX, 24 kHz, chỉ giọng có sẵn) hoặc v3turbo (ONNX, 48 kHz)."""

    def __init__(self, threads: int, mode: str):
        from services.tts_model_store import ensure_model_files

        info = ensure_model_files(mode=mode)
        from vieneu import Vieneu

        self.mode = mode
        if mode == MODE_NANO:
            self._tts = Vieneu(mode=MODE_NANO, threads=threads, onnx_dir=info.get("model_dir"))
        else:
            self._tts = Vieneu(mode=MODE_TURBO, backend="onnx", device="cpu", threads=threads)
        self.sample_rate = int(self._tts.sample_rate)
        self._lock = threading.Lock()
        self.voices: List[str] = [name for _label, name in self._tts.list_preset_voices()]

    def synth(self, text: str, voice_name: str) -> Tuple[Any, int]:
        import numpy as np

        with self._lock:
            wav = self._tts.infer(text, voice=voice_name)
        return np.asarray(wav, dtype=np.float32).reshape(-1), self.sample_rate


def _default_factory(threads: int) -> TtsEngine:
    return VieneuEngine(threads, active_mode())


class LocalTts:
    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._gen = 0
        self._engine: Optional[TtsEngine] = None
        self._factory: Callable[[int], TtsEngine] = _default_factory
        self._thread: Optional[threading.Thread] = None
        self.state = UNLOADED
        self.reason = ""
        self._load_failures = 0
        self._retry_at = 0.0
        self._errors_in_a_row = 0
        self._requested = False
        self.load_seconds: Optional[float] = None
        self.loaded_at: Optional[float] = None
        self.mode = active_mode()

    # ---- cấu hình
    @staticmethod
    def enabled() -> bool:
        return _flag("TTS_LOCAL_ENABLED", True)

    @staticmethod
    def threads() -> int:
        return _int_env("TTS_LOCAL_THREADS", 3, 1, 16)

    def set_factory(self, factory: Optional[Callable[[int], TtsEngine]]) -> None:
        with self._lock:
            self._factory = factory or _default_factory

    # ---- vòng đời
    def install(self, engine: TtsEngine) -> None:
        """Gắn engine có sẵn (test) và coi như đã sẵn sàng."""
        with self._lock:
            self._gen += 1
            self._engine = engine
            self.mode = getattr(engine, "mode", None) or active_mode()
            self.state, self.reason = READY, ""
            self._errors_in_a_row = 0
            self.loaded_at = time.time()

    def reset(self) -> None:
        with self._lock:
            self._gen += 1
            self._engine = None
            self.state, self.reason = UNLOADED, ""
            self._load_failures = 0
            self._retry_at = 0.0
            self._errors_in_a_row = 0
            self._requested = False
            self.load_seconds = self.loaded_at = None
            self.mode = active_mode()

    def start_warmup(self) -> None:
        """Nạp mô hình ở nền nếu chưa nạp (và đã đến lúc thử lại sau lỗi). Không bao giờ chặn người gọi."""
        with self._lock:
            if not self.enabled():
                self.state, self.reason = DISABLED, "TTS_LOCAL_ENABLED=0"
                return
            if self.state in (READY, LOADING):
                return
            if self.state == FAILED and time.monotonic() < self._retry_at:
                return
            if not _flag("TTS_LOCAL_WARMUP", True) and not self._requested:
                return  # chế độ lười: chỉ nạp khi có người cần (request())
            self._gen += 1
            gen = self._gen
            self.state, self.reason = LOADING, ""
            self._thread = threading.Thread(target=self._load, args=(gen,), name="tts-local-load", daemon=True)
            self._thread.start()

    def request(self) -> None:
        """Có người cần giọng local (đặc biệt khi TTS_LOCAL_WARMUP=0): bắt đầu/thử lại việc nạp, không chờ."""
        self._requested = True
        if self.state in (UNLOADED, FAILED):
            self.start_warmup()

    def _load(self, gen: int = 0) -> None:
        lower_thread_priority()
        started = time.perf_counter()
        self.mode = active_mode()
        try:
            engine = self._factory(self.threads())
            try:  # lần suy luận đầu chậm hơn hẳn (cấp phát, JIT): làm nóng trước khi báo sẵn sàng
                engine.synth(WARMUP_TEXT, _warmup_voice(engine))
            except Exception as exc:
                logger.info("Làm nóng engine giọng đọc không thành công (bỏ qua): %s", exc)
        except Exception as exc:
            with self._lock:
                if self._gen != gen:
                    return
                self._load_failures += 1
                delay = _BACKOFF_SECONDS[min(self._load_failures - 1, len(_BACKOFF_SECONDS) - 1)]
                self._retry_at = time.monotonic() + delay
                self.state, self.reason = FAILED, f"{type(exc).__name__}: {exc}"[:300]
            logger.warning("Không nạp được giọng đọc local (thử lại sau %ss): %s", delay, self.reason)
            return
        with self._lock:
            if self._gen != gen:
                return
            self._engine = engine
            self.state, self.reason = READY, ""
            self._load_failures = 0
            self._errors_in_a_row = 0
            self.load_seconds = round(time.perf_counter() - started, 1)
            self.loaded_at = time.time()
        logger.info("Giọng đọc local (%s) đã sẵn sàng sau %.1fs (threads=%d)", self.mode, self.load_seconds, self.threads())

    # ---- sử dụng
    def is_ready(self) -> bool:
        return self.state == READY and self._engine is not None

    def unavailable(self) -> EngineUnavailable:
        """Ngoại lệ mô tả vì sao chưa dùng được (kèm gợi ý Retry-After)."""
        if self.state == DISABLED or not self.enabled():
            return EngineUnavailable("disabled", 3600)
        if self.state == FAILED:
            return EngineUnavailable("failed", 60)
        return EngineUnavailable("loading", 20)

    def synth_blocking(self, text: str, voice_name: str) -> Tuple[Any, int]:
        engine = self._engine
        if self.state != READY or engine is None:
            raise self.unavailable()
        return engine.synth(text, voice_name)

    def report_success(self) -> None:
        self._errors_in_a_row = 0

    def report_failure(self, exc: BaseException) -> None:
        """Nhiều lần lỗi liên tiếp = engine hỏng: đánh dấu FAILED để nạp lại (có lùi thời gian)."""
        with self._lock:
            self._errors_in_a_row += 1
            if self._errors_in_a_row < _ERRORS_BEFORE_RELOAD or self.state != READY:
                return
            self._engine = None
            self._load_failures += 1
            self._retry_at = time.monotonic() + _BACKOFF_SECONDS[0]
            self.state, self.reason = FAILED, f"nhiều lần lỗi liên tiếp: {type(exc).__name__}: {exc}"[:300]
        logger.error("Engine giọng đọc hỏng, sẽ nạp lại: %s", self.reason)

    def status(self) -> Dict[str, Any]:
        return {
            "engine": "vieneu",
            "model": self.mode if self.is_ready() else active_mode(),
            "state": self.state,
            "model_loaded": self.is_ready(),
            "enabled": self.enabled(),
            "reason": self.reason,
            "threads": self.threads(),
            "load_seconds": self.load_seconds,
        }


def _warmup_voice(engine: TtsEngine) -> str:
    from services.tts_local_catalog import by_id, default_voice_id

    voices = getattr(engine, "voices", None)
    preset = by_id()[default_voice_id(False)]["preset_name"]
    return preset if not voices or preset in voices else voices[0]


local_tts = LocalTts()
