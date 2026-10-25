"""Tác vụ nền dọn dẹp các thư mục đệm trên máy chủ.

VÌ SAO CẦN:
`data/tts_cache` sinh một tệp MP3 cho MỖI đoạn × MỖI giọng × MỖI tốc độ.
Nghe hết một bộ 1000 chương với hai giọng là vài GB. `covers_cache` và
`static/flashcards` cũng chỉ ghi thêm chứ không bao giờ xoá. Trên MiniPC,
đây là vấn đề của vài tháng chứ không phải vài năm.

Chiến lược: xoá theo tuổi truy cập (TTL), sau đó nếu vẫn vượt trần dung lượng
thì xoá tiếp theo LRU (tệp lâu không dùng nhất đi trước).
"""

import asyncio
import logging
import os
import time
from typing import Dict, List, Tuple

import database
from services.tts_cache import max_cache_bytes as tts_cache_max_bytes

logger = logging.getLogger("truyencloud.cache_janitor")

_BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class CacheDir:
    def __init__(self, path: str, max_bytes: int, ttl_days: int, label: str):
        self.path = path
        self.max_bytes = max_bytes
        self.ttl_seconds = ttl_days * 86400
        self.label = label


CACHE_DIRS: List[CacheDir] = [
    # Trần dùng chung biến TTS_CACHE_MAX_MB với tts_cache.sweeper (dọn mỗi vài phút, không đụng tệp vừa dùng)
    CacheDir(os.path.join(_BASE, "data", "tts_cache"), tts_cache_max_bytes(), 30, "TTS"),
    CacheDir(os.path.join(_BASE, "data", "covers_cache"), 512 * 1024**2, 90, "Ảnh bìa"),
    CacheDir(os.path.join(_BASE, "static", "flashcards"), 256 * 1024**2, 180, "Flashcard"),
]


def _scan(path: str) -> List[Tuple[str, int, float]]:
    """Liệt kê (đường dẫn, kích thước, thời điểm truy cập gần nhất)."""
    entries = []
    if not os.path.isdir(path):
        return entries
    for name in os.listdir(path):
        full = os.path.join(path, name)
        try:
            if not os.path.isfile(full):
                continue
            st = os.stat(full)
            # atime có thể bị tắt trên một số hệ thống tệp -> lùi về mtime
            last_used = max(st.st_atime, st.st_mtime)
            entries.append((full, st.st_size, last_used))
        except OSError:
            continue
    return entries


def sweep_dir(cache: CacheDir, dry_run: bool = False) -> Dict[str, int]:
    """Dọn một thư mục. Trả về thống kê."""
    entries = _scan(cache.path)
    if not entries:
        return {"scanned": 0, "removed_ttl": 0, "removed_lru": 0, "freed_bytes": 0}

    now = time.time()
    removed_ttl = removed_lru = freed = 0

    # Bước 1 — xoá theo tuổi
    survivors = []
    for full, size, last_used in entries:
        if now - last_used > cache.ttl_seconds:
            if not dry_run:
                try:
                    os.remove(full)
                except OSError:
                    survivors.append((full, size, last_used))
                    continue
            removed_ttl += 1
            freed += size
        else:
            survivors.append((full, size, last_used))

    # Bước 2 — nếu vẫn vượt trần thì xoá tiếp theo LRU
    total = sum(s for _, s, _ in survivors)
    if total > cache.max_bytes:
        survivors.sort(key=lambda x: x[2])  # cũ nhất trước
        for full, size, _ in survivors:
            if total <= cache.max_bytes:
                break
            if not dry_run:
                try:
                    os.remove(full)
                except OSError:
                    continue
            removed_lru += 1
            freed += size
            total -= size

    if removed_ttl or removed_lru:
        logger.info(
            "Dọn cache %s: xoá %d tệp quá hạn + %d tệp LRU, giải phóng %.1f MB",
            cache.label, removed_ttl, removed_lru, freed / 1024**2,
        )
    return {
        "scanned": len(entries),
        "removed_ttl": removed_ttl,
        "removed_lru": removed_lru,
        "freed_bytes": freed,
    }


def sweep_all(dry_run: bool = False) -> Dict[str, Dict[str, int]]:
    result = {c.label: sweep_dir(c, dry_run) for c in CACHE_DIRS}
    # Dọn luôn các dòng search_cache hết hạn trong SQLite (trước đây không ai gọi
    # purge_expired nên bảng phình vô hạn). An toàn trên thread janitor vì
    # get_db_connection là thread-local.
    if not dry_run:
        try:
            from services.search_cache_service import search_cache_service
            result["search_cache_sql"] = {"removed_ttl": search_cache_service.purge_expired(), "removed_lru": 0, "freed_bytes": 0}
        except Exception:
            pass
    return result


def usage_report() -> Dict[str, Dict[str, object]]:
    """Dung lượng thật của từng thư mục đệm — dùng cho endpoint trạng thái."""
    report = {}
    for c in CACHE_DIRS:
        entries = _scan(c.path)
        total = sum(s for _, s, _ in entries)
        report[c.label] = {
            "files": len(entries),
            "bytes": total,
            "mb": round(total / 1024**2, 2),
            "limit_mb": round(c.max_bytes / 1024**2, 2),
            "usage_percent": round(total * 100 / c.max_bytes, 1) if c.max_bytes else 0,
        }
    return report


class CacheJanitor:
    def __init__(self, interval_seconds: int = 6 * 3600, checkpoint_interval_seconds: int = 600):
        self.interval_seconds = interval_seconds
        self.checkpoint_interval_seconds = checkpoint_interval_seconds
        self._task = None
        self._checkpoint_task = None
        self.is_running = False
        self.last_run = None
        self.last_result = {}
        self.last_checkpoint = None

    async def _loop(self):
        self.is_running = True
        # Chạy một lượt sau 5 phút để không tranh tài nguyên lúc khởi động
        await asyncio.sleep(300)
        while self.is_running:
            try:
                self.last_result = await asyncio.to_thread(sweep_all)
                self.last_run = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            except Exception as e:
                logger.warning("Lỗi khi dọn cache: %s", e)
            await asyncio.sleep(self.interval_seconds)

    async def _checkpoint_loop(self):
        """Gộp -wal của SQLite vào tệp chính định kỳ.

        docker-compose chỉ bind-mount MỘT tệp truyencloud.db nên -wal/-shm nằm trong lớp ghi của container
        và mất khi container bị tạo lại; checkpoint định kỳ giới hạn phần dữ liệu có thể mất sau một lần sập.
        """
        while self.is_running:
            await asyncio.sleep(self.checkpoint_interval_seconds)
            try:
                self.last_checkpoint = await asyncio.to_thread(database.checkpoint)
            except Exception as e:
                logger.warning("Lỗi checkpoint SQLite: %s", e)

    def start(self):
        if not self.is_running:
            # Đặt cờ TRƯỚC khi tạo task: vòng checkpoint kiểm tra cờ ngay lượt đầu.
            self.is_running = True
            self._task = asyncio.create_task(self._loop())
            self._checkpoint_task = asyncio.create_task(self._checkpoint_loop())
            logger.info(
                "CacheJanitor: bật dọn dẹp mỗi %d giờ, checkpoint SQLite mỗi %d phút.",
                self.interval_seconds // 3600, self.checkpoint_interval_seconds // 60,
            )

    def stop(self):
        self.is_running = False
        for attr in ("_task", "_checkpoint_task"):
            task = getattr(self, attr)
            if task:
                task.cancel()
                setattr(self, attr, None)


cache_janitor = CacheJanitor()
