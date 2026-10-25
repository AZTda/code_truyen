"""Lớp dán giữa tuyến TTS và hàng đợi dựng: lấy tệp cho một đoạn văn (cache hit hoặc dựng ưu tiên 0).

Giọng local không dùng được (đang nạp, lỗi, tắt, quá chậm, quá tải) -> `EngineUnavailable`; route đổi thành HTTP 503
`local_tts_unavailable` để app dùng giọng máy. Không có engine dự phòng nào khác.
"""

import logging
import os
from typing import Callable, Optional

from services import tts_cache
from services.tts_local import EngineUnavailable, local_tts
from services.tts_prerender import (
    PRIORITY_CURRENT,
    PRIORITY_ONDEMAND,
    JobFailed,
    QueueFull,
    render_queue,
)
from services.tts_voice_service import KIND_DEVICE, KIND_RENDER, KIND_SILENT, TtsPlan, ensure_silence_file, resolve_tts_plan

logger = logging.getLogger("truyencloud.tts.synthesis")


def ondemand_timeout() -> float:
    try:
        return max(1.0, float(os.getenv("TTS_ONDEMAND_TIMEOUT", "90")))
    except ValueError:
        return 90.0


def max_text_chars() -> int:
    try:
        return max(50, int(os.getenv("TTS_MAX_TEXT_CHARS", "2000")))
    except ValueError:
        return 2000


async def render_for_stream(
    plan: TtsPlan, timeout: Optional[float] = None, on_submitted: Optional[Callable[[], None]] = None,
) -> str:
    """Đường dẫn MP3 của một đoạn kiểu 'silent'/'render'. Ném EngineUnavailable / JobFailed khi không dựng được.

    `on_submitted` chạy NGAY SAU khi việc ưu tiên 0 đã vào hàng đợi (hoặc đã có cache): tiền nạp đoạn kế phải xếp sau
    đó, kẻo worker đang rảnh nhặt việc tiền nạp trước và người nghe phải chờ thêm một đoạn.
    """
    if plan.kind == KIND_SILENT:
        path = ensure_silence_file(plan)
        if path is None:
            raise JobFailed("không ghi được tệp im lặng")
        if on_submitted:
            on_submitted()
        return path
    if plan.kind == KIND_DEVICE:
        raise ValueError("đoạn tiếng Trung do giọng máy đọc")
    if tts_cache.is_cached(plan.cache_file):
        tts_cache.touch(plan.cache_file)
        if on_submitted:
            on_submitted()
        return plan.cache_file
    if not local_tts.is_ready():
        local_tts.request()
        raise local_tts.unavailable()
    try:
        job = render_queue.submit(plan, PRIORITY_ONDEMAND)
    except QueueFull as exc:
        raise EngineUnavailable("busy", 5) from exc
    if on_submitted:
        on_submitted()
    return await render_queue.wait(job, ondemand_timeout() if timeout is None else timeout)


def enqueue_ahead(plan: TtsPlan, priority: int = PRIORITY_CURRENT) -> bool:
    """Tiền nạp một đoạn (không chờ). True nếu đã xếp mới. Đoạn im lặng/tiếng Trung/đã có cache thì bỏ qua."""
    if plan.kind != KIND_RENDER or tts_cache.is_cached(plan.cache_file):
        return False
    if not local_tts.is_ready():
        local_tts.request()
        return False
    try:
        existed = render_queue.pending_for(plan.md5) is not None
        render_queue.submit(plan, priority)
        return not existed
    except QueueFull:
        return False


async def synthesize_to_cache(
    cache_dir: str, text: str, voice: str = "mai_phuong", speed: float = 1.0, pitch: Optional[str] = None,
    lang: Optional[str] = None, *, priority: int = PRIORITY_ONDEMAND, wait: bool = True,
) -> Optional[str]:
    """Tên cũ của tuyến TTS: tệp MP3 của `text` hoặc None khi không dựng được/không phải việc của máy chủ.

    `speed`/`pitch` được nhận và BỎ QUA (VieNeu không có; app áp tốc độ ở trình phát). `wait=False` chỉ xếp hàng.
    """
    clean = (text or "").strip()
    if not clean:
        return None
    plan = resolve_tts_plan(clean, voice, lang, cache_dir)
    if plan.kind == KIND_DEVICE:
        return None
    if not wait:
        enqueue_ahead(plan, priority)
        return plan.cache_file if tts_cache.is_cached(plan.cache_file) else None
    try:
        return await render_for_stream(plan)
    except (EngineUnavailable, JobFailed) as exc:
        logger.info("Không dựng được giọng: %s", exc)
        return None
