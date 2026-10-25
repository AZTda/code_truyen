"""Dấu vân tay (chỉ ĐỌC) của dữ liệu thật trong server/ — để chứng minh test không ghi vào đó."""

import hashlib
import os
from pathlib import Path
from typing import Dict

SERVER_DIR = Path(__file__).resolve().parent.parent

# Tệp đơn: băm nội dung. (Không tính -wal/-shm: máy chủ thật đang chạy có thể ghi vào đó bất kỳ lúc nào.)
FILES = (
    "truyencloud.db",
    "data/users.json",
    "data/app_version.json",
    "data/models_cache.json",
    "data/translation_profile.json",
)
# Thư mục: liệt kê (đường dẫn tương đối, kích thước, mtime). Thêm/bớt/sửa tệp đều làm đổi dấu vân tay.
DIRS = (
    "data/novels",
    "data/glossaries",
    "data/tts_cache",
    "data/covers_cache",
    "static/flashcards",
)


def _hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _list_dir(path: Path) -> str:
    rows = []
    for root, dirs, files in os.walk(path):
        dirs.sort()
        for name in sorted(files):
            full = Path(root) / name
            try:
                stat = full.stat()
            except OSError:
                continue
            rows.append(f"{full.relative_to(path)}|{stat.st_size}|{stat.st_mtime_ns}")
    return hashlib.sha256("\n".join(rows).encode("utf-8")).hexdigest() + f":{len(rows)}"


def fingerprint(root: Path = SERVER_DIR) -> Dict[str, str]:
    result: Dict[str, str] = {}
    for rel in FILES:
        path = root / rel
        result[rel] = _hash_file(path) if path.is_file() else "<absent>"
    for rel in DIRS:
        path = root / rel
        result[rel + "/"] = _list_dir(path) if path.is_dir() else "<absent>"
    return result


def changed(before: Dict[str, str], after: Dict[str, str]):
    return sorted(key for key in set(before) | set(after) if before.get(key) != after.get(key))
