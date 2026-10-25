"""Chạy việc dài tách khỏi vòng đời của request.

Dịch một chương mất 1-2 phút. Nếu độc giả rời màn hình (hoặc ứng dụng huỷ lời gọi
tải trước) mà việc dịch bị huỷ theo, toàn bộ lượt AI đó bị bỏ phí và lần mở sau
phải dịch lại từ đầu. Ở đây việc vẫn chạy xong và kết quả được lưu; chỉ phần chờ
kết quả của request bị huỷ.
"""

import asyncio
import logging
from typing import Any, Coroutine, Set

logger = logging.getLogger("truyencloud.detached_task")

_running: Set[asyncio.Future] = set()


def _on_done(task: asyncio.Future) -> None:
    _running.discard(task)
    if task.cancelled():
        return
    exc = task.exception()  # đọc ngoại lệ để asyncio không cảnh báo "never retrieved"
    if exc is not None:
        logger.debug(f"Việc tách rời kết thúc với lỗi: {exc!r}")


async def run_detached(coro: Coroutine[Any, Any, Any]) -> Any:
    """Chạy coro như một task độc lập và chờ kết quả; huỷ người chờ không huỷ task."""
    task = asyncio.ensure_future(coro)
    _running.add(task)
    task.add_done_callback(_on_done)
    return await asyncio.shield(task)


def running_count() -> int:
    return len(_running)
