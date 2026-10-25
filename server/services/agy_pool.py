"""Persistent Warm Worker Pool for Antigravity CLI.

Eliminates cold-start runtime initialization, TLS handshakes, and plugin scanning
by keeping a warm `agy` process open via `--input-format stream-json`.

Giao thức là MỘT lượt = một prompt vào, rồi các `step_update` và đúng một `result`
ra, không có mã định danh lượt. Vì vậy bất biến sống còn của pool này là: worker
chỉ được tái sử dụng khi lượt trước đã được đọc HẾT tới `result`. Lối thoát nào
khác (khách đóng kết nối giữa luồng, bị huỷ, hết hạn, EOF) đều phải dừng worker,
nếu không lượt kế tiếp đọc nhầm phần còn sót của lượt trước và trả câu trả lời
của người khác như của mình.
"""

import os
import json
import shutil
import asyncio
import logging
from services.agy_effort import effort_for_model
from typing import Optional, AsyncGenerator

logger = logging.getLogger("truyencloud.agy_pool")

MAX_TURNS_BEFORE_RECYCLE = 50
STREAM_LINE_LIMIT = 8 * 1024 * 1024


class AgyPool:
    """Maintains a persistent warm Antigravity CLI session to achieve sub-second to 2-second AI responses."""

    def __init__(self):
        self._proc: Optional[asyncio.subprocess.Process] = None
        self._lock = asyncio.Lock()
        self._turn_count = 0
        # True từ lúc ghi prompt vào worker tới lúc đọc xong sự kiện `result` của lượt đó
        self._turn_open = False
        self._model: Optional[str] = None
        self._effort: Optional[str] = None
        self._enabled = os.getenv("ENABLE_AGY_WARM_POOL", "true").lower() in ("true", "1", "yes")

    async def warmup(self) -> None:
        """Khởi động sẵn worker lúc server startup để lời gọi AI đầu tiên không tốn ~10s cold start."""
        if not self._enabled:
            return
        async with self._lock:
            await self._ensure_worker()

    def _get_agy_path(self) -> Optional[str]:
        candidates = [
            shutil.which("agy"),
            os.path.expanduser("~/.local/bin/agy"),
            "/usr/local/bin/agy",
        ]
        for p in candidates:
            if p and os.path.exists(p) and os.access(p, os.X_OK):
                return p
        return None

    async def _ensure_worker(self, model: str = "gemini-3.8-flash-low", effort: str = "low") -> bool:
        if self._proc and self._proc.returncode is None:
            # Worker đang chạy model/effort khác với yêu cầu: tái khởi động để
            # không âm thầm trả kết quả từ sai model (caller đang giữ _lock nên
            # worker chắc chắn rảnh tại thời điểm này).
            if (self._model, self._effort) != (model, effort):
                logger.info(f"Recycling warm worker: model {self._model}->{model}, effort {self._effort}->{effort}.")
                await self._recycle()
            else:
                return True

        agy = self._get_agy_path()
        if not agy:
            return False

        cmd = [
            agy,
            "-p", "",
            "--input-format", "stream-json",
            "--output-format", "stream-json",
            "--model", model,
            "--effort", effort_for_model(model, effort),
            "--disable-slash-commands",
            "--dangerously-skip-permissions",
        ]

        try:
            self._proc = await asyncio.create_subprocess_exec(
                *cmd,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL,
                limit=STREAM_LINE_LIMIT,
            )
            self._turn_count = 0
            self._turn_open = False
            self._model, self._effort = model, effort

            # Read the init event from worker stdout
            if self._proc.stdout:
                line = await asyncio.wait_for(self._proc.stdout.readline(), timeout=10.0)
                if line:
                    try:
                        data = json.loads(line.decode("utf-8", "ignore"))
                        if data.get("event") == "init":
                            logger.info(f"Warm Antigravity worker started successfully (model={model}, effort={effort}).")
                            return True
                    except Exception:
                        pass
            return True
        except Exception as e:
            logger.warning(f"Failed to start warm Antigravity worker: {e}")
            await self._recycle()
            return False
        except BaseException:
            # Bị huỷ khi đang chờ worker khởi động: không để lại tiến trình nửa vời
            await self._recycle()
            raise

    async def _cleanup(self):
        # Tách tiến trình khỏi pool NGAY, trước mọi await: dù người gọi bị huỷ giữa
        # chừng, lượt kế tiếp chắc chắn khởi động worker mới thay vì đọc nhầm phần
        # còn sót của lượt này (tiến trình cũ vẫn được dừng ngay sau đó).
        proc, self._proc = self._proc, None
        self._turn_open = False
        if not proc:
            return
        try:
            if proc.stdin:
                proc.stdin.close()
            proc.terminate()
            await asyncio.wait_for(proc.wait(), timeout=1.5)
        except Exception:
            try:
                proc.kill()
                await proc.wait()
            except Exception:
                pass

    async def _recycle(self) -> None:
        """Dừng worker kể cả khi người gọi đang bị huỷ: shield giữ cho việc dọn dẹp chạy cho xong."""
        await asyncio.shield(self._cleanup())

    async def _start_turn(self, prompt: str, model: str, effort: str):
        """Bật worker nếu cần rồi gửi prompt. Trả về tiến trình đang giữ lượt, hoặc None nếu không dùng được pool.

        Caller PHẢI đang giữ `_lock`. Sau khi trả về tiến trình, `_turn_open` là True cho tới khi
        caller đọc xong sự kiện `result`; mọi lối thoát sớm hơn phải dừng worker (xem `_release_turn`).
        """
        ready = await self._ensure_worker(model=model, effort=effort)
        proc = self._proc
        if not ready or not proc or not proc.stdin or not proc.stdout:
            return None

        msg = {
            "event": "user",
            "message": {
                "content": [{"type": "text", "text": prompt}]
            }
        }
        payload = json.dumps(msg, ensure_ascii=False) + "\n"
        self._turn_open = True
        proc.stdin.write(payload.encode("utf-8"))
        await proc.stdin.drain()
        return proc

    async def _release_turn(self) -> None:
        """Kết thúc một lượt: lượt chưa đọc tới `result` thì dừng worker; luôn nhả khoá."""
        try:
            if self._turn_open:
                logger.info("Warm worker bị bỏ dở một lượt chưa đọc hết, dừng worker để lượt sau không đọc nhầm.")
                await self._recycle()
        finally:
            self._lock.release()

    async def execute(
        self,
        prompt: str,
        timeout: float = 30.0,
        model: str = "gemini-3.8-flash-low",
        effort: str = "low"
    ) -> Optional[str]:
        """Runs a prompt against the warm worker session for maximum speed."""
        if not self._enabled:
            return None

        # Try to acquire lock without blocking indefinitely
        try:
            await asyncio.wait_for(self._lock.acquire(), timeout=timeout)
        except asyncio.TimeoutError:
            logger.warning("Warm worker pool busy, falling back to ad-hoc process.")
            return None

        try:
            proc = await self._start_turn(prompt, model, effort)
            if proc is None:
                return None

            full_response = ""
            loop = asyncio.get_running_loop()
            deadline = loop.time() + timeout

            while True:
                remaining = deadline - loop.time()
                if remaining <= 0:
                    raise asyncio.TimeoutError()

                line = await asyncio.wait_for(proc.stdout.readline(), timeout=remaining)
                if not line:
                    break

                try:
                    event_data = json.loads(line.decode("utf-8", "ignore"))
                    ev_type = event_data.get("event")

                    if ev_type == "result":
                        self._turn_open = False
                        res_obj = event_data.get("result", {})
                        if res_obj.get("status") == "SUCCESS":
                            full_response = res_obj.get("response", full_response).strip()
                            self._turn_count += 1
                            if self._turn_count >= MAX_TURNS_BEFORE_RECYCLE:
                                logger.info("Recycling warm worker after turn limit reached.")
                                await self._recycle()
                            return full_response
                        else:
                            err = res_obj.get("error", "Unknown error")
                            logger.warning(f"Warm worker returned error result: {err}")
                            await self._recycle()
                            return None

                    elif ev_type == "step_update":
                        delta = event_data.get("step_update", {}).get("text_delta", "")
                        if delta:
                            full_response += delta

                except json.JSONDecodeError:
                    continue

            # EOF trước `result`: worker đã chết giữa lượt, không trả bản cụt; `_release_turn` sẽ dọn nó
            return None

        except asyncio.TimeoutError:
            logger.warning(f"Warm worker timed out ({timeout}s). Recycling worker.")
            return None
        except Exception as e:
            logger.warning(f"Error executing prompt in warm worker: {e}")
            return None
        finally:
            await self._release_turn()

    async def execute_stream(
        self,
        prompt: str,
        timeout: float = 60.0,
        model: str = "gemini-3.8-flash-low",
        effort: str = "low"
    ) -> AsyncGenerator[str, None]:
        """Streams tokens in real time from the warm worker."""
        if not self._enabled:
            return

        try:
            await asyncio.wait_for(self._lock.acquire(), timeout=timeout)
        except asyncio.TimeoutError:
            return

        try:
            proc = await self._start_turn(prompt, model, effort)
            if proc is None:
                return

            loop = asyncio.get_running_loop()
            deadline = loop.time() + timeout
            while True:
                remaining = deadline - loop.time()
                if remaining <= 0:
                    logger.warning(f"Warm worker stream timed out ({timeout}s). Recycling worker.")
                    break

                line = await asyncio.wait_for(proc.stdout.readline(), timeout=remaining)
                if not line:
                    break

                try:
                    event_data = json.loads(line.decode("utf-8", "ignore"))
                    ev_type = event_data.get("event")

                    if ev_type == "step_update":
                        delta = event_data.get("step_update", {}).get("text_delta", "")
                        if delta:
                            yield delta
                    elif ev_type == "result":
                        self._turn_open = False
                        status = (event_data.get("result") or {}).get("status")
                        if status is not None and status != "SUCCESS":
                            await self._recycle()
                            break
                        self._turn_count += 1
                        if self._turn_count >= MAX_TURNS_BEFORE_RECYCLE:
                            await self._recycle()
                        break
                except json.JSONDecodeError:
                    continue
        except Exception as e:
            logger.warning(f"Error in warm worker stream: {e}")
        finally:
            # Chạy cả khi khách đóng luồng giữa chừng (GeneratorExit) hay bị huỷ (CancelledError)
            await self._release_turn()

    async def stop(self):
        """Stops and cleans up the warm worker on server shutdown."""
        async with self._lock:
            await self._cleanup()


agy_pool = AgyPool()
