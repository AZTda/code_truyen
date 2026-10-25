import asyncio
import pytest
from services.agy_runner import TaskPriority, AgyRunnerMixin, _interactive_semaphore, _reader_semaphore, _background_semaphore
from services.task_coalescer import TaskCoalescer


@pytest.mark.asyncio
async def test_task_coalescer_deduplication():
    coalescer = TaskCoalescer()
    execution_count = 0

    async def slow_work():
        nonlocal execution_count
        execution_count += 1
        await asyncio.sleep(0.1)
        return "result_success"

    # Launch 3 simultaneous requests with identical task_key
    res1, res2, res3 = await asyncio.gather(
        coalescer.run_coalesced("key_123", slow_work),
        coalescer.run_coalesced("key_123", slow_work),
        coalescer.run_coalesced("key_123", slow_work),
    )

    assert res1 == "result_success"
    assert res2 == "result_success"
    assert res3 == "result_success"
    # Even though 3 callers awaited, only 1 actual execution occurred!
    assert execution_count == 1
    assert coalescer.get_in_flight_count() == 0


@pytest.mark.asyncio
async def test_multi_lane_isolation():
    """Verify that a saturated background lane does not block the interactive lane."""
    runner = AgyRunnerMixin()
    bg_sem = runner._get_semaphore_for_priority(TaskPriority.BACKGROUND)
    inter_sem = runner._get_semaphore_for_priority(TaskPriority.INTERACTIVE)

    assert bg_sem is not inter_sem

    # Acquire the background semaphore (simulate a heavy batch job)
    await bg_sem.acquire()
    try:
        assert bg_sem.locked()

        # The interactive semaphore must STILL be freely acquirable!
        interactive_acquired = False
        async with inter_sem:
            interactive_acquired = True

        assert interactive_acquired is True
    finally:
        bg_sem.release()


@pytest.mark.asyncio
async def test_task_priority_enum_values():
    assert TaskPriority.INTERACTIVE.value == "interactive"
    assert TaskPriority.READER.value == "reader"
    assert TaskPriority.BACKGROUND.value == "background"
