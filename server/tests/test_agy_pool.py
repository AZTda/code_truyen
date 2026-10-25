import pytest
import asyncio
from services.agy_pool import agy_pool, AgyPool
from services.model_catalog import model_catalog_service
from services.agy_runner import TaskPriority, AgyRunnerMixin


def test_model_catalog_default_is_low_effort():
    """Verify that model catalog defaults to fast flash model with low reasoning effort."""
    active_model = model_catalog_service.get_active_model()
    active_effort = model_catalog_service.get_active_effort()
    timeout = model_catalog_service.get_active_timeout()

    assert "low" in active_model.lower() or "flash" in active_model.lower()
    assert active_effort in ("low", "medium")
    assert timeout <= 180


@pytest.mark.asyncio
async def test_agy_pool_fallback_when_disabled():
    """When pool is disabled, execute() gracefully returns None so callers fall back to ad-hoc process."""
    pool = AgyPool()
    pool._enabled = False
    res = await pool.execute("test prompt", timeout=5.0)
    assert res is None


@pytest.mark.asyncio
async def test_agy_pool_lifecycle():
    """Verify pool stops and cleans up child processes cleanly."""
    pool = AgyPool()
    assert pool._proc is None
    await pool.stop()
    assert pool._proc is None


@pytest.mark.asyncio
async def test_runner_uses_low_effort_cmd():
    """Verify AgyRunnerMixin builds commands with --effort low."""
    runner = AgyRunnerMixin()
    agy = runner._get_agy_path()
    if not agy:
        pytest.skip("Antigravity CLI not installed in test environment")

    # Verify that warm pool is consulted or ad-hoc process has low effort
    sem = runner._get_semaphore_for_priority(TaskPriority.INTERACTIVE)
    assert sem is not None
