"""In-flight request deduplication and coalescing for Antigravity AI tasks."""

import asyncio
import logging
from typing import Any, Callable, Coroutine, Dict

logger = logging.getLogger("truyencloud.task_coalescer")


class TaskCoalescer:
    """Ensures identical concurrent requests share the same executing task
    instead of launching redundant Antigravity CLI processes.
    """

    def __init__(self):
        self._inflight: Dict[str, asyncio.Future] = {}
        self._lock = asyncio.Lock()

    async def run_coalesced(
        self,
        task_key: str,
        coro_factory: Callable[[], Coroutine[Any, Any, Any]],
    ) -> Any:
        """Run coro_factory() or await existing in-flight task for task_key."""
        async with self._lock:
            task = self._inflight.get(task_key)
            if task is not None and not task.done():
                logger.info(f"Coalescing onto in-flight task: {task_key}")
                is_leader = False
            else:
                task = asyncio.create_task(coro_factory())
                self._inflight[task_key] = task
                is_leader = True

        if not is_leader:
            return await asyncio.shield(task)

        try:
            return await task
        finally:
            async with self._lock:
                if self._inflight.get(task_key) is task:
                    self._inflight.pop(task_key, None)

    def get_in_flight_count(self) -> int:
        return len([f for f in self._inflight.values() if not f.done()])


task_coalescer = TaskCoalescer()
