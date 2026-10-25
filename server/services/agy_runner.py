import os
import codecs
import shutil
import subprocess
import asyncio
import logging
import threading
from contextlib import aclosing
from enum import Enum
from typing import Optional, List
from models.translate import TermItem
from services.json_utils import extract_json_object
from services.task_coalescer import task_coalescer
from services.agy_effort import effort_for_model

logger = logging.getLogger("truyencloud.agy_runner")


class TaskPriority(str, Enum):
    """Phân làn ưu tiên cho các tác vụ Antigravity AI."""
    INTERACTIVE = "interactive"  # Tra từ, chat đồng hành, tìm kiếm (< 3s)
    READER = "reader"            # Dịch chương hiện tại, dịch 15 tiêu đề chương đầu
    BACKGROUND = "background"    # Dịch hàng loạt, auto updater, bài tập đọc hiểu


# Phân làn tài nguyên độc lập để tác vụ ngầm không bao giờ bóp nghẹt tác vụ tương tác
_interactive_semaphore = asyncio.Semaphore(int(os.getenv("AGY_INTERACTIVE_CONCURRENCY", "2")))
_reader_semaphore = asyncio.Semaphore(int(os.getenv("AGY_READER_CONCURRENCY", "2")))
_background_semaphore = asyncio.Semaphore(int(os.getenv("AGY_BACKGROUND_CONCURRENCY", "1")))


async def _reap_process(proc: asyncio.subprocess.Process) -> None:
    """Thu dọn triệt để tiến trình con, chống rò rỉ zombie [python3] <defunct>."""
    if proc.returncode is not None:
        return
    try:
        proc.terminate()
        await asyncio.wait_for(proc.wait(), timeout=1.5)
    except (asyncio.TimeoutError, ProcessLookupError, Exception):
        try:
            proc.kill()
            await asyncio.wait_for(proc.wait(), timeout=2.0)
        except Exception:
            pass


class AgyRunnerMixin:
    """Provides local CLI execution, multi-lane process management, and term glossary substitution."""

    def _get_agy_path(self) -> Optional[str]:
        """Locates the installed Antigravity CLI binary."""
        candidates = [
            shutil.which("agy"),
            os.path.expanduser("~/.local/bin/agy"),
            "/usr/local/bin/agy",
        ]
        for path in candidates:
            if path and os.path.exists(path) and os.access(path, os.X_OK):
                return path
        return None

    def _get_semaphore_for_priority(self, priority: TaskPriority) -> asyncio.Semaphore:
        """Chọn làn thực thi thích hợp theo độ ưu tiên tác vụ."""
        if priority == TaskPriority.INTERACTIVE:
            return _interactive_semaphore
        elif priority == TaskPriority.BACKGROUND:
            return _background_semaphore
        return _reader_semaphore

    async def _execute_agy_process(
        self,
        prompt: str,
        timeout: float = 30.0,
        model: Optional[str] = None,
        priority: TaskPriority = TaskPriority.INTERACTIVE
    ) -> Optional[str]:
        # 1. Thử thực thi nhanh qua Warm Worker Pool (< 2s) nếu là làn tương tác hoặc đọc trực tiếp
        if priority in (TaskPriority.INTERACTIVE, TaskPriority.READER):
            try:
                from services.agy_pool import agy_pool
                warm_out = await agy_pool.execute(
                    prompt=prompt,
                    timeout=min(timeout, 25.0),
                    model=model or "gemini-3.8-flash-low",
                    effort=effort_for_model(model, "low"),
                )
                if warm_out:
                    return warm_out
            except Exception as e:
                logger.debug(f"Warm pool bypassed: {e}")

        agy = self._get_agy_path()
        if not agy:
            return None

        cmd = [
            agy, "-p", prompt,
            "--output-format", "text",
            "--effort", effort_for_model(model, "low"),
            "--disable-slash-commands",
            "--dangerously-skip-permissions",
        ]
        if model:
            cmd.extend(["--model", model])

        sem = self._get_semaphore_for_priority(priority)

        # Làn ngầm chủ động nhường khi làn đọc đang tải nặng
        if priority == TaskPriority.BACKGROUND and _reader_semaphore.locked():
            await asyncio.sleep(0.5)

        async with sem:
            proc = None
            try:
                proc = await asyncio.create_subprocess_exec(
                    *cmd,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE,
                )
                try:
                    stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=timeout)
                except asyncio.TimeoutError:
                    if proc:
                        await _reap_process(proc)
                    logger.warning(f"Antigravity CLI quá thời gian chờ ({timeout}s) [Lane: {priority.value}].")
                    return None

                if proc.returncode != 0:
                    logger.warning(
                        f"Antigravity CLI lỗi (mã {proc.returncode}): "
                        f"{(stderr or b'').decode('utf-8', 'ignore')[:300]}"
                    )
                    return None
                return (stdout or b"").decode("utf-8", "ignore").strip() or None
            except Exception as e:
                logger.warning(f"Lỗi chạy Antigravity CLI: {e}")
                return None
            finally:
                if proc:
                    await _reap_process(proc)

    async def _run_agy(
        self,
        prompt: str,
        timeout: float = 30.0,
        model: Optional[str] = None,
        priority: TaskPriority = TaskPriority.INTERACTIVE,
        task_key: Optional[str] = None
    ) -> Optional[str]:
        """Chạy một lệnh Antigravity CLI theo làn ưu tiên, hỗ trợ khử trùng lặp task_key."""
        if task_key:
            return await task_coalescer.run_coalesced(
                task_key,
                lambda: self._execute_agy_process(prompt, timeout=timeout, model=model, priority=priority)
            )
        return await self._execute_agy_process(prompt, timeout=timeout, model=model, priority=priority)

    async def _run_agy_stream(
        self,
        prompt: str,
        timeout: float = 45.0,
        model: Optional[str] = None,
        priority: TaskPriority = TaskPriority.INTERACTIVE
    ):
        """Chạy agy và yield từng đoạn text nhận được từ stdout theo thời gian thực."""
        # 1. Thử stream qua Warm Worker Pool (< 0.8s TTFT)
        try:
            from services.agy_pool import agy_pool
            has_streamed = False
            # aclosing: khách đóng luồng giữa chừng thì luồng của pool được đóng NGAY (nhả khoá +
            # dừng worker đang dở lượt), không chờ bộ gom rác quyết định lúc nào mới đóng.
            async with aclosing(agy_pool.execute_stream(
                prompt=prompt,
                timeout=timeout,
                model=model or "gemini-3.8-flash-low",
                effort="low",
            )) as pool_stream:
                async for chunk in pool_stream:
                    has_streamed = True
                    yield chunk
            if has_streamed:
                return
        except Exception as e:
            logger.debug(f"Warm stream bypassed: {e}")

        agy = self._get_agy_path()
        if not agy:
            return

        cmd = [
            agy, "-p", prompt,
            "--output-format", "text",
            "--effort", effort_for_model(model, "low"),
            "--disable-slash-commands",
            "--dangerously-skip-permissions",
        ]
        if model:
            cmd.extend(["--model", model])

        sem = self._get_semaphore_for_priority(priority)
        async with sem:
            proc = None
            try:
                proc = await asyncio.create_subprocess_exec(
                    *cmd,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE,
                )
                # Bộ giải mã tăng dần: ký tự tiếng Việt nhiều byte bị cắt giữa hai lần
                # read() được ghép lại, thay vì bị errors="ignore" vứt mất.
                decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
                try:
                    while True:
                        chunk = await asyncio.wait_for(proc.stdout.read(64), timeout=timeout)
                        if not chunk:
                            tail = decoder.decode(b"", final=True)
                            if tail:
                                yield tail
                            break
                        text = decoder.decode(chunk)
                        if text:
                            yield text
                    await proc.wait()
                except asyncio.TimeoutError:
                    if proc:
                        await _reap_process(proc)
                    logger.warning(f"Antigravity CLI stream quá thời gian chờ ({timeout}s).")
            except Exception as e:
                logger.warning(f"Lỗi chạy Antigravity CLI stream: {e}")
            finally:
                if proc:
                    await _reap_process(proc)

    async def _run_agy_json(
        self,
        prompt: str,
        timeout: float = 30.0,
        priority: TaskPriority = TaskPriority.INTERACTIVE,
        task_key: Optional[str] = None
    ) -> Optional[dict]:
        """Chạy agy và parse kết quả thành dict JSON theo làn ưu tiên."""
        raw = await self._run_agy(prompt, timeout=timeout, priority=priority, task_key=task_key)
        if not raw:
            return None
        return extract_json_object(raw)

    def is_agy_ready(self, force_refresh: bool = False) -> bool:
        """Checks if Antigravity CLI is installed and authenticated with Google Account."""
        agy = self._get_agy_path()
        if not agy:
            return False

        state_file = os.path.expanduser("~/.gemini/antigravity-cli/jetski_state.pbtxt")
        if os.path.exists(state_file):
            return True

        import time
        now = time.time()
        if not force_refresh and (now - getattr(self, "_last_agy_check", 0.0) < 60.0):
            return getattr(self, "_cached_agy_ready", False)

        self._last_agy_check = now

        def _probe():
            try:
                res = subprocess.run(
                    [agy, "-p", "ping", "--disable-slash-commands", "--dangerously-skip-permissions"],
                    capture_output=True,
                    text=True,
                    timeout=12
                )
                if "authentication required" in (res.stderr or "").lower():
                    self._cached_agy_ready = False
                else:
                    self._cached_agy_ready = (res.returncode == 0)
            except Exception:
                self._cached_agy_ready = False

        if force_refresh:
            _probe()
            return getattr(self, "_cached_agy_ready", False)

        # Probe 12s chạy ở thread nền để không chặn event loop của server;
        # lượt gọi hiện tại trả giá trị cache cũ, lượt sau nhận kết quả mới.
        threading.Thread(target=_probe, daemon=True).start()
        return getattr(self, "_cached_agy_ready", False)

    def _apply_glossary_to_text(self, text: str, glossary: Optional[List[TermItem]] = None) -> str:
        """Thay tên riêng / thuật ngữ theo bảng thuật ngữ."""
        if not glossary:
            return text

        terms = sorted(
            ((it.source, it.target) for it in glossary if it.source and it.target),
            key=lambda pair: len(pair[0]),
            reverse=True,
        )

        processed = text
        for source, target in terms:
            processed = processed.replace(source, target)
        return processed
