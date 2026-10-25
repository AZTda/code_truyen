"""Maintenance script to sanitize, deduplicate, and repair Table of Contents (TOC)

for all existing novels in TruyenCloud.
"""

import os
import json
from typing import Dict, Any
from services.toc_normalizer import normalize_chapters
from database import db

DATA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "novels")


def repair_novel_toc(novel_id: str) -> Dict[str, Any]:
    novel_dir = os.path.join(DATA_DIR, novel_id)
    info_path = os.path.join(novel_dir, "info.json")
    idx_path = os.path.join(novel_dir, "chapters_index.json")

    if not os.path.exists(idx_path):
        return {"novel_id": novel_id, "status": "no_index"}

    with open(idx_path, "r", encoding="utf-8") as f:
        raw_index = json.load(f)

    # Normalize and deduplicate chapters
    normalized = normalize_chapters(raw_index)
    normalized_dicts = [c.model_dump() for c in normalized]

    # Save sanitized index back
    with open(idx_path, "w", encoding="utf-8") as f:
        json.dump(normalized_dicts, f, ensure_ascii=False, indent=2)

    total_chapters = len(normalized_dicts)

    # Update info.json
    info = {}
    if os.path.exists(info_path):
        with open(info_path, "r", encoding="utf-8") as f:
            info = json.load(f)
        info["total_chapters"] = total_chapters
        with open(info_path, "w", encoding="utf-8") as f:
            json.dump(info, f, ensure_ascii=False, indent=2)

    # Sync to SQLite DB novels table
    if info:
        db.table("novels").upsert(info).execute()

    return {
        "novel_id": novel_id,
        "title": info.get("title", ""),
        "title_vi": info.get("title_vi", ""),
        "before_count": len(raw_index),
        "after_count": total_chapters,
        "status": "repaired"
    }


def main():
    print("--- BẮT ĐẦU KIỂM TRA VÀ CHUẨN HOÁ MỤC LỤC TẤT CẢ TRUYỆN ---")
    results = []
    for nid in sorted(os.listdir(DATA_DIR)):
        p = os.path.join(DATA_DIR, nid)
        if not os.path.isdir(p):
            continue
        res = repair_novel_toc(nid)
        results.append(res)
        print(f"[{res['novel_id']}] Trước: {res.get('before_count')} -> Sau: {res.get('after_count')} chương | {res.get('title_vi')}")

    print("\n--- HOÀN TẤT CHUẨN HOÁ MỤC LỤC ---")


if __name__ == "__main__":
    main()
