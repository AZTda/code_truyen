"""Bộ đệm MP3 TTS trên đĩa (data/tts_cache): khoá, ghi nguyên tử, sidecar thời lượng, dọn LRU theo dung lượng.

Khoá = md5("v5_vieneu|<mô hình>|<văn bản đã chuẩn hoá>|<tên giọng thật>"). Mô hình (v3nano/v3turbo) nằm trong khoá vì
cùng một tên giọng ở hai mô hình là hai giọng khác nhau. KHÔNG có tốc độ trong khoá: mọi tệp được dựng ở 1.0x và trình
phát của app tự áp tốc độ. Mỗi `<md5>.mp3` kèm `<md5>.ms` (số nguyên ms,
thời lượng tính từ độ dài PCM lúc dựng) để cache hit cũng biết X-Audio-Duration-Ms. Đĩa là nguồn sự thật: khởi động
lại máy chủ chỉ cần tính lại, không có trạng thái nào khác phải khôi phục.
"""

import hashlib
import logging
import os
import threading
import time
import uuid
from typing import Dict, Optional, Tuple

from services.tts_audio import estimate_duration_ms

logger = logging.getLogger("truyencloud.tts.cache")

CACHE_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "data", "tts_cache")

KEY_VERSION = "v5_vieneu"
SILENCE_KEY = "v5_silence"  # mọi đoạn im lặng dùng chung một tệp, không phụ thuộc giọng
RECENT_SECONDS = 60  # tệp dùng trong vòng ngần này giây không bao giờ bị xoá
SWEEP_INTERVAL_SECONDS = 300
STALE_TMP_SECONDS = 600
TARGET_FRACTION = 0.9  # dọn xuống còn 90% trần để không dọn lại sau mỗi tệp ghi


def max_cache_bytes() -> int:
    try:
        mb = float(os.getenv("TTS_CACHE_MAX_MB", "4096"))
    except ValueError:
        mb = 4096.0
    return int(max(mb, 1.0) * 1024 * 1024)


def cache_key(norm_text: str, preset_name: str, mode: str) -> str:
    return f"{KEY_VERSION}|{mode}|{norm_text}|{preset_name}"


def md5_of(key: str) -> str:
    return hashlib.md5(key.encode("utf-8")).hexdigest()


def paths(cache_dir: str, md5: str) -> Tuple[str, str]:
    base = os.path.join(cache_dir, md5)
    return base + ".mp3", base + ".ms"


def is_cached(mp3_path: str) -> bool:
    try:
        return os.path.getsize(mp3_path) > 0
    except OSError:
        return False


def _discard(path: str) -> None:
    try:
        os.remove(path)
    except OSError:
        pass


def _atomic_write(path: str, data: bytes) -> None:
    # uuid, không phải id(): hai luồng cùng ghi một khoá không được đè lên một tệp tạm
    tmp = f"{path}.tmp_{uuid.uuid4().hex}"
    try:
        with open(tmp, "wb") as handle:
            handle.write(data)
        os.replace(tmp, path)
    except BaseException:
        _discard(tmp)
        raise


def write_audio(cache_dir: str, md5: str, mp3: bytes, duration_ms: int) -> str:
    """Ghi sidecar thời lượng TRƯỚC rồi mới đặt MP3: tệp .mp3 xuất hiện thì thời lượng đã có sẵn."""
    os.makedirs(cache_dir, exist_ok=True)
    mp3_path, ms_path = paths(cache_dir, md5)
    _atomic_write(ms_path, str(int(duration_ms)).encode("ascii"))
    _atomic_write(mp3_path, mp3)
    return mp3_path


def read_duration_ms(mp3_path: str) -> int:
    """Thời lượng đã lưu lúc dựng; thiếu sidecar (tệp cũ/bị dọn một nửa) thì ước lượng từ kích thước CBR."""
    try:
        with open(mp3_path[:-4] + ".ms", "rb") as handle:
            return max(0, int(handle.read(24).decode("ascii").strip()))
    except (OSError, ValueError):
        try:
            return estimate_duration_ms(os.path.getsize(mp3_path))
        except OSError:
            return 0


def touch(path: str) -> None:
    """Đánh dấu vừa dùng để LRU đúng kể cả khi hệ tệp bật relatime (atime hầu như không cập nhật)."""
    try:
        os.utime(path, None)
    except OSError:
        pass


def usage(cache_dir: str) -> Tuple[int, int]:
    """(số tệp mp3, tổng byte của mp3 + sidecar)."""
    files = total = 0
    try:
        with os.scandir(cache_dir) as it:
            for entry in it:
                if not entry.is_file(follow_symlinks=False):
                    continue
                if entry.name.endswith(".mp3"):
                    files += 1
                if entry.name.endswith((".mp3", ".ms")):
                    total += entry.stat(follow_symlinks=False).st_size
    except OSError:
        pass
    return files, total


def sweep(cache_dir: str, max_bytes: Optional[int] = None, now: Optional[float] = None) -> Dict[str, int]:
    """Xoá tệp lâu không dùng nhất (LRU theo max(atime, mtime)) khi vượt trần; không đụng tệp dùng <1 phút trước."""
    limit = max_cache_bytes() if max_bytes is None else int(max_bytes)
    now = time.time() if now is None else now
    entries = []  # (last_used, mp3_path, bytes)
    total = removed = freed = 0
    try:
        scan = list(os.scandir(cache_dir))
    except OSError:
        return {"files": 0, "bytes": 0, "removed": 0, "freed_bytes": 0}
    mp3_names = {e.name[:-4] for e in scan if e.name.endswith(".mp3")}
    for entry in scan:
        try:
            if not entry.is_file(follow_symlinks=False):
                continue
            st = entry.stat(follow_symlinks=False)
            name = entry.name
            if ".tmp_" in name:
                if now - st.st_mtime > STALE_TMP_SECONDS:
                    _discard(entry.path)
                continue
            if name.endswith(".ms"):
                if name[:-3] not in mp3_names and now - st.st_mtime > RECENT_SECONDS:
                    _discard(entry.path)  # sidecar mồ côi (mp3 đã bị xoá hoặc lần ghi dở)
                else:
                    total += st.st_size
                continue
            if name.endswith(".mp3"):
                size = st.st_size
                ms_path = entry.path[:-4] + ".ms"
                try:
                    size += os.stat(ms_path).st_size
                except OSError:
                    pass
                total += size
                entries.append((max(st.st_atime, st.st_mtime), entry.path, size))
        except OSError:
            continue

    if total > limit:
        target = int(limit * TARGET_FRACTION)
        entries.sort()
        for last_used, path, size in entries:
            if total <= target:
                break
            if now - last_used < RECENT_SECONDS:
                break  # đã sắp xếp tăng dần: phần còn lại đều mới hơn
            _discard(path)
            _discard(path[:-4] + ".ms")
            total -= size
            removed += 1
            freed += size
        if removed:
            logger.info("Dọn bộ đệm TTS: xoá %d tệp, giải phóng %.1f MB", removed, freed / 1024**2)
    return {"files": len(entries) - removed, "bytes": max(total, 0), "removed": removed, "freed_bytes": freed}


class Sweeper:
    """Chạy `sweep` nhiều nhất mỗi SWEEP_INTERVAL_SECONDS (gọi sau mỗi lần ghi) và một lần lúc khởi động."""

    def __init__(self, interval: float = SWEEP_INTERVAL_SECONDS):
        self.interval = interval
        self._last = 0.0
        self._lock = threading.Lock()
        self.last_result: Dict[str, int] = {}

    def maybe_run(self, cache_dir: str, force: bool = False) -> bool:
        now = time.monotonic()
        with self._lock:
            if not force and self._last and now - self._last < self.interval:
                return False
            self._last = now
        try:
            self.last_result = sweep(cache_dir)
        except Exception as exc:  # dọn cache hỏng không được làm hỏng đường dựng giọng
            logger.warning("Dọn bộ đệm TTS lỗi: %s", exc)
        return True

    def reset(self) -> None:
        with self._lock:
            self._last = 0.0
            self.last_result = {}


sweeper = Sweeper()
