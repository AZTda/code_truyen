"""One-time download of the Hy-MT2-1.8B Q4_K_M GGUF (Apache-2.0) for the truyencloud-mt llama.cpp service.

Pinned Hugging Face revision + sha256 + size; resumable (.part + HTTP Range), verified before the atomic rename.
The file lives in the data volume (default server/data/mt, in Docker /app/data/mt), never in the image.

Deploy (once, inside the backend container, which has network and Python):
    docker compose exec truyencloud-backend python -m services.title_mt_model_store --with-dict
--with-dict also downloads the pinned convert dictionaries and builds data/dict/title_dict.sqlite now instead of
in the background at the next start.
"""

import argparse
import hashlib
import json
import logging
import os
import sys
import urllib.request
from typing import Callable, Dict, Optional

logger = logging.getLogger("truyencloud.title_mt.model")

MODEL_FILE = "Hy-MT2-1.8B-Q4_K_M.gguf"
MODEL_REVISION = "a0c709d9fac510f2c807aa3af52872340dc37a4a"
MODEL_URL = f"https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/{MODEL_REVISION}/{MODEL_FILE}"
MODEL_SHA256 = "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699"
MODEL_SIZE = 1133080448
_CHUNK = 1 << 20


def model_dir() -> str:
    configured = (os.getenv("TITLE_MT_MODEL_DIR") or "").strip()
    if configured:
        return os.path.abspath(configured)
    return os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "mt")


def model_path(directory: Optional[str] = None) -> str:
    return os.path.join(directory or model_dir(), MODEL_FILE)


def _sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(_CHUNK), b""):
            h.update(block)
    return h.hexdigest()


def is_ready(directory: Optional[str] = None, deep: bool = False) -> bool:
    """Đúng kích thước (deep=True: băm sha256, ~3 s/GB)."""
    path = model_path(directory)
    if not os.path.isfile(path) or os.path.getsize(path) != MODEL_SIZE:
        return False
    return _sha256(path) == MODEL_SHA256 if deep else True


def ensure_model(directory: Optional[str] = None, progress: Optional[Callable[[int, int], None]] = None,
                 timeout: float = 60.0) -> Dict[str, object]:
    """Tải nếu thiếu (tiếp tục được khi đứt giữa chừng), kiểm sha256, rồi đổi tên nguyên tử."""
    directory = directory or model_dir()
    os.makedirs(directory, exist_ok=True)
    dest = model_path(directory)
    if os.path.isdir(dest):
        raise RuntimeError(f"{dest} là THƯ MỤC (bind mount tệp khi tệp chưa có?). Xoá thư mục rỗng đó rồi chạy lại.")
    if is_ready(directory, deep=True):
        return {"path": dest, "downloaded": False}
    part = dest + ".part"
    have = os.path.getsize(part) if os.path.exists(part) else 0
    if have > MODEL_SIZE:
        os.remove(part)
        have = 0
    if have < MODEL_SIZE:
        req = urllib.request.Request(MODEL_URL, headers={"User-Agent": "TruyenCloud/1.0"})
        if have:
            req.add_header("Range", f"bytes={have}-")
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if have and resp.status != 206:
                have = 0  # máy chủ không hỗ trợ tải tiếp: tải lại từ đầu
            with open(part, "ab" if have else "wb") as out:
                done = have
                for block in iter(lambda: resp.read(_CHUNK), b""):
                    out.write(block)
                    done += len(block)
                    if progress:
                        progress(done, MODEL_SIZE)
    if os.path.getsize(part) != MODEL_SIZE or _sha256(part) != MODEL_SHA256:
        os.remove(part)
        raise RuntimeError("Tệp mô hình tải về sai kích thước/sha256 — đã xoá, chạy lại để tải lại.")
    os.replace(part, dest)
    return {"path": dest, "downloaded": True}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Tải mô hình Hy-MT2 (và từ điển convert) cho tựa đề TruyenCloud.")
    ap.add_argument("--dir", default=None, help="thư mục mô hình (mặc định data/mt)")
    ap.add_argument("--with-dict", action="store_true", help="tải từ điển convert và dựng chỉ mục sqlite luôn")
    ap.add_argument("--check", action="store_true", help="chỉ kiểm tra, không tải")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    report: Dict[str, object] = {}
    if args.check:
        report["model_ready"] = is_ready(args.dir, deep=True)
    else:
        last = [-1]

        def show(done: int, total: int) -> None:
            pct = done * 100 // total
            if pct != last[0] and pct % 5 == 0:
                last[0] = pct
                print(f"  mô hình {pct}% ({done // (1 << 20)} MB)", flush=True)

        report["model"] = ensure_model(args.dir, progress=show)
    if args.with_dict:
        from services.title_dict_build import build_index
        from services.title_dict_sources import dict_dir, ensure_sources, index_path, missing_sources
        if args.check:
            report["dict_missing"] = [s.name for s in missing_sources()]
            report["dict_index"] = os.path.exists(index_path())
        else:
            report["dict"] = ensure_sources()
            report["index"] = build_index(dict_dir(), index_path())
    print(json.dumps(report, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
