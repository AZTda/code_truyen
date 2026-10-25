"""Sửa `chapters_index.json` bị nhân đôi bởi auto-updater bản cũ (không bao giờ tự chạy cùng server).

Bản cũ so id nguồn với id = số chương nên coi mọi chương nguồn là mới: mục lục dài gấp đôi, trong đó
nửa sau là bản sao URL của nửa đầu, và hai chương đầu bị cào + dịch lại thành chương N+1, N+2.

    cd server
    .venv/bin/python -m services.chapters_index_repair              # chỉ báo cáo, không sửa gì
    .venv/bin/python -m services.chapters_index_repair --apply      # sửa tất cả truyện bị nhân đôi
    .venv/bin/python -m services.chapters_index_repair --apply 10_10527   # chỉ một truyện

--apply sao lưu chapters_index.json thành chapters_index.json.bak-<thời điểm>, giữ mục XUẤT HIỆN ĐẦU TIÊN
của mỗi URL và chuyển các tệp chapter_<n>.json của mục bị bỏ (bản sao thừa) sang thư mục
removed_duplicate_chapters-<thời điểm>/ trong thư mục truyện thay vì xoá. Nếu sau khi bỏ trùng số chương còn
bị hổng (có chương mới thật được thêm SAU khối nhân đôi) thì KHÔNG tự sửa: đánh số lại chương động tới tệp
chương, tiến độ đọc và dấu trang nên phải làm tay.
"""

import argparse
import json
import os
import shutil
import sys
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple

from services.toc_identity import normalize_url


def split_duplicates(index: List[Dict[str, Any]]) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    """(mục giữ lại, mục trùng URL bị bỏ): mục đầu tiên của mỗi URL chuẩn hoá thắng."""
    kept: List[Dict[str, Any]] = []
    dropped: List[Dict[str, Any]] = []
    seen = set()
    for entry in index:
        url = normalize_url(entry.get("url")) if isinstance(entry, dict) else ""
        if url and url in seen:
            dropped.append(entry)
            continue
        if url:
            seen.add(url)
        kept.append(entry)
    return kept, dropped


def count_duplicates(index: List[Any]) -> int:
    return len(split_duplicates([e for e in index if isinstance(e, dict)])[1])


def _numbers(entries: List[Dict[str, Any]]) -> List[int]:
    out = []
    for entry in entries:
        try:
            out.append(int(entry.get("chapter_number")))
        except (TypeError, ValueError):
            pass
    return out


def repair_novel(novel_dir: str, apply: bool = False, now: Optional[datetime] = None) -> Dict[str, Any]:
    """Phân tích (và nếu apply=True thì sửa) chỉ mục chương của một truyện. Trả về bản báo cáo."""
    novel_id = os.path.basename(os.path.normpath(novel_dir))
    index_path = os.path.join(novel_dir, "chapters_index.json")
    report: Dict[str, Any] = {"novel_id": novel_id, "status": "ok", "entries": 0, "duplicates": 0}
    if not os.path.exists(index_path):
        report["status"] = "no_index"
        return report
    try:
        with open(index_path, "r", encoding="utf-8") as f:
            index = json.load(f)
    except (OSError, ValueError) as e:
        report.update(status="unreadable", error=str(e))
        return report
    if not isinstance(index, list):
        report["status"] = "unreadable"
        return report

    kept, dropped = split_duplicates([e for e in index if isinstance(e, dict)])
    report.update(entries=len(index), duplicates=len(dropped), entries_after=len(kept))
    if not dropped:
        return report

    dropped_numbers = sorted(_numbers(dropped))
    kept_numbers = sorted(_numbers(kept))
    report["dropped_chapter_numbers"] = f"{dropped_numbers[0]}-{dropped_numbers[-1]}" if dropped_numbers else ""
    chapters_dir = os.path.join(novel_dir, "chapters")
    spurious = [n for n in dropped_numbers if os.path.exists(os.path.join(chapters_dir, f"chapter_{n}.json"))]
    report["spurious_chapter_files"] = len(spurious)

    if kept_numbers and kept_numbers != list(range(kept_numbers[0], kept_numbers[0] + len(kept_numbers))):
        report["status"] = "needs_manual_renumber"
        report["note"] = "Sau khi bỏ trùng số chương còn bị hổng: có chương thật được thêm sau khối nhân đôi."
        return report

    report["status"] = "duplicated"
    if not apply:
        return report

    stamp = (now or datetime.now()).strftime("%Y%m%d-%H%M%S")
    shutil.copy2(index_path, f"{index_path}.bak-{stamp}")
    tmp_path = f"{index_path}.tmp"
    with open(tmp_path, "w", encoding="utf-8") as f:
        json.dump(kept, f, ensure_ascii=False, indent=2)
    os.replace(tmp_path, index_path)
    if spurious:
        trash = os.path.join(novel_dir, f"removed_duplicate_chapters-{stamp}")
        os.makedirs(trash, exist_ok=True)
        for n in spurious:
            shutil.move(os.path.join(chapters_dir, f"chapter_{n}.json"), os.path.join(trash, f"chapter_{n}.json"))
    report["status"] = "repaired"
    return report


def scan_all(base_dir: str, apply: bool = False, only: Optional[List[str]] = None) -> List[Dict[str, Any]]:
    reports = []
    for name in sorted(os.listdir(base_dir)):
        path = os.path.join(base_dir, name)
        if os.path.isdir(path) and (not only or name in only):
            reports.append(repair_novel(path, apply=apply))
    return reports


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Phát hiện và sửa chapters_index.json bị nhân đôi.")
    parser.add_argument("novels", nargs="*", help="id truyện cần xử lý (mặc định: tất cả)")
    parser.add_argument("--apply", action="store_true", help="thực sự sửa (mặc định chỉ báo cáo)")
    parser.add_argument("--data-dir", default=None, help="thư mục novels (mặc định: server/data/novels)")
    args = parser.parse_args(argv)

    base_dir = args.data_dir
    if base_dir is None:
        from services.storage_service import storage_service
        base_dir = storage_service.base_dir
    reports = scan_all(base_dir, apply=args.apply, only=args.novels or None)
    for r in reports:
        print(json.dumps(r, ensure_ascii=False))
    damaged = [r for r in reports if r["status"] in ("duplicated", "needs_manual_renumber")]
    if damaged and not args.apply:
        print(f"\n{len(damaged)} truyện có mục lục nhân đôi. Chạy lại với --apply để sửa (có sao lưu).")
    return 1 if any(r["status"] == "needs_manual_renumber" for r in reports) else 0


if __name__ == "__main__":
    sys.exit(main())
