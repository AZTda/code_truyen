"""Warm pool không được để một lượt bị bỏ dở rò câu trả lời sang người dùng kế tiếp.

Worker `agy` nói chuyện kiểu một lượt = prompt vào, các `step_update`, rồi một `result` ra, không có
mã định danh lượt. Nếu người gọi rời đi trước khi đọc tới `result`, phần còn sót của lượt đó nằm lại
trong ống và lượt kế tiếp đọc nhầm làm câu trả lời của mình. Ở đây chỉ tiến trình `agy` được giả (đúng
giao thức mà agy_pool đang dùng); toàn bộ mã của pool chạy thật.
"""

import asyncio
import json

import pytest

from services.agy_pool import AgyPool
from services.agy_runner import AgyRunnerMixin


class FakeStdin:
    def __init__(self, worker):
        self.worker = worker

    def write(self, data):
        self.worker.on_user_message(json.loads(data.decode()))

    async def drain(self):
        pass

    def close(self):
        self.worker.stdin_closed = True


class FakeStdout:
    def __init__(self):
        self.q = asyncio.Queue()

    async def readline(self):
        return await self.q.get()


class FakeWorker:
    """Giả tiến trình agy: mỗi prompt được trả lời "reply to <prompt>" thành 3 mảnh rồi `result`."""

    def __init__(self, delay=0.02):
        self.returncode = None
        self.stdin_closed = False
        self.delay = delay
        self.stdout = FakeStdout()
        self.stdin = FakeStdin(self)
        self.prompts = []
        self.stdout.q.put_nowait(b'{"event": "init"}\n')

    def on_user_message(self, msg):
        text = msg["message"]["content"][0]["text"]
        self.prompts.append(text)
        answer = f"reply to {text}"
        step = max(1, len(answer) // 3)

        async def emit():
            for i in range(0, len(answer), step):
                await asyncio.sleep(self.delay)
                delta = {"event": "step_update", "step_update": {"text_delta": answer[i:i + step]}}
                await self.stdout.q.put((json.dumps(delta) + "\n").encode())
            await asyncio.sleep(self.delay)
            result = {"event": "result", "result": {"status": "SUCCESS", "response": answer}}
            await self.stdout.q.put((json.dumps(result) + "\n").encode())

        asyncio.get_running_loop().create_task(emit())

    def terminate(self):
        self.returncode = -15

    def kill(self):
        self.returncode = -9

    async def wait(self):
        return self.returncode


@pytest.fixture
def pool(monkeypatch):
    """Một AgyPool mới mỗi test; `asyncio.create_subprocess_exec` trả worker giả (conftest chặn agy thật)."""
    spawned = []

    async def fake_exec(*cmd, **kwargs):
        worker = FakeWorker()
        spawned.append(worker)
        return worker

    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_exec)
    p = AgyPool()
    p._enabled = True
    p._get_agy_path = lambda: "/fake/agy"
    p.spawned = spawned
    return p


@pytest.mark.asyncio
async def test_luong_bi_bo_giua_chung_khong_ro_cau_tra_loi_sang_lan_sau(pool):
    gen = pool.execute_stream("CAU-HOI-A", timeout=5)
    assert await gen.__anext__()
    await gen.aclose()                       # khách đóng app khi đang nhận luồng
    assert not pool._lock.locked()

    assert await pool.execute("CAU-HOI-B", timeout=5) == "reply to CAU-HOI-B"
    assert await pool.execute("CAU-HOI-C", timeout=5) == "reply to CAU-HOI-C"
    # Worker dở lượt bị dừng và thay bằng worker mới, không dùng lại
    assert len(pool.spawned) == 2 and pool.spawned[0].returncode is not None
    assert pool.spawned[1].prompts == ["CAU-HOI-B", "CAU-HOI-C"]


@pytest.mark.asyncio
async def test_execute_bi_huy_khong_ro_cau_tra_loi_sang_lan_sau(pool):
    task = asyncio.create_task(pool.execute("CAU-HOI-A", timeout=5))
    await asyncio.sleep(0.03)                # worker đã bắt đầu trả lời
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert not pool._lock.locked()

    assert await pool.execute("CAU-HOI-B", timeout=5) == "reply to CAU-HOI-B"
    assert pool.spawned[0].returncode is not None


@pytest.mark.asyncio
async def test_het_han_giua_hai_manh_khong_ro_cau_tra_loi_sang_lan_sau(pool):
    got = []
    # Người nhận chậm hơn hạn chót: vòng lặp thoát bằng nhánh "hết hạn" chứ không phải bằng `result`
    async for chunk in pool.execute_stream("CAU-HOI-A", timeout=0.3):
        got.append(chunk)
        await asyncio.sleep(0.5)
    assert got, "phải nhận được ít nhất một mảnh trước hạn chót"

    assert await pool.execute("CAU-HOI-B", timeout=5) == "reply to CAU-HOI-B"


@pytest.mark.asyncio
async def test_worker_chet_giua_luot_khong_bi_dung_lai(pool):
    first = await pool.execute("CAU-HOI-A", timeout=5)
    assert first == "reply to CAU-HOI-A"
    # Worker phát dở 1 mảnh step_update rồi thoát (EOF) mà chưa có `result`: tuyệt đối không trả bản dịch cụt
    pool._proc.stdout.q.put_nowait(b'{"event": "step_update", "step_update": {"text_delta": "ban dich cut"}}\n')
    pool._proc.stdout.q.put_nowait(b"")
    assert await pool.execute("CAU-HOI-B", timeout=5) is None
    assert await pool.execute("CAU-HOI-C", timeout=5) == "reply to CAU-HOI-C"
    assert len(pool.spawned) == 2


@pytest.mark.asyncio
async def test_luot_doc_het_thi_tai_su_dung_worker(pool):
    assert await pool.execute("CAU-HOI-A", timeout=5) == "reply to CAU-HOI-A"
    chunks = [c async for c in pool.execute_stream("CAU-HOI-B", timeout=5)]
    assert "".join(chunks) == "reply to CAU-HOI-B"
    assert await pool.execute("CAU-HOI-C", timeout=5) == "reply to CAU-HOI-C"
    assert len(pool.spawned) == 1, "Không được dừng worker khi mọi lượt đã đọc hết"


@pytest.mark.asyncio
async def test_run_agy_stream_dong_luong_pool_ngay_khi_khach_roi_di(pool, monkeypatch):
    """Đường companion: _run_agy_stream phải đóng luồng của pool ngay (nhả khoá, dừng worker dở lượt)."""
    monkeypatch.setattr("services.agy_pool.agy_pool", pool)
    runner = AgyRunnerMixin()
    gen = runner._run_agy_stream("CAU-HOI-A", timeout=5)
    assert await gen.__anext__()
    await gen.aclose()
    assert not pool._lock.locked(), "Luồng của pool phải được đóng ngay, không chờ bộ gom rác"
    assert pool.spawned[0].returncode is not None

    out = await runner._execute_agy_process("CAU-HOI-B", timeout=5)
    assert out == "reply to CAU-HOI-B"


# ---------------------------------------------------------------------------
# Tiến trình THẬT (không phải đối tượng giả): ống, terminate/kill và wait chạy đúng như production
# ---------------------------------------------------------------------------

WORKER_SCRIPT = '''
import json, sys, time
print(json.dumps({"event": "init"}), flush=True)
for line in sys.stdin:
    text = json.loads(line)["message"]["content"][0]["text"]
    answer = "reply to " + text
    step = max(1, len(answer) // 3)
    for i in range(0, len(answer), step):
        time.sleep(0.05)
        print(json.dumps({"event": "step_update", "step_update": {"text_delta": answer[i:i + step]}}), flush=True)
    time.sleep(0.05)
    print(json.dumps({"event": "result", "result": {"status": "SUCCESS", "response": answer}}), flush=True)
'''


@pytest.mark.asyncio
async def test_tien_trinh_that_bi_dung_khi_bo_luong_va_luot_sau_co_tien_trinh_moi(tmp_path):
    import os
    import stat
    import sys

    script = tmp_path / "fake_worker.py"
    script.write_text(WORKER_SCRIPT, encoding="utf-8")
    launcher = tmp_path / "warm-worker"          # tên khác "agy" nên conftest không chặn
    launcher.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{script}"\n', encoding="utf-8")
    launcher.chmod(launcher.stat().st_mode | stat.S_IXUSR)

    pool = AgyPool()
    pool._enabled = True
    pool._get_agy_path = lambda: str(launcher)
    try:
        gen = pool.execute_stream("CAU-HOI-A", timeout=10)
        assert await gen.__anext__()
        first_pid = pool._proc.pid
        await gen.aclose()
        assert pool._proc is None and not pool._lock.locked()
        with pytest.raises(ProcessLookupError):  # đã dừng VÀ đã thu dọn, không để lại zombie
            os.kill(first_pid, 0)

        assert await pool.execute("CAU-HOI-B", timeout=10) == "reply to CAU-HOI-B"
        second_pid = pool._proc.pid
        assert second_pid != first_pid
        assert await pool.execute("CAU-HOI-C", timeout=10) == "reply to CAU-HOI-C"
        assert pool._proc.pid == second_pid, "Lượt đọc hết thì dùng lại cùng worker"
    finally:
        await pool.stop()
