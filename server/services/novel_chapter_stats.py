"""Bộ đệm thống kê số chương đã tải / đã dịch trên đĩa 1TB và làm mượt văn án tiếng Việt.

Giúp trang Admin mở tức thì (< 1ms) ngay cả khi kho 1TB có hàng trăm truyện và hàng chục nghìn chương,
bằng cách đệm theo `(mtime_ns, số_tệp)` của thư mục `chapters/` thay vì `json.load` lại từng tệp ở mỗi request.
"""

import json
import os
import re
import threading
from typing import Any, Dict, List, Tuple

from services.hanviet_dict import has_cjk
from services.search_title_polisher import _convert_phrase_vi
from services.storage_service import storage_service
from services.text_similarity import to_simplified
from services.title_dict import title_dict
from services.title_dict_text import tidy_spaces
from services.translation_validation import ENGINE_FALLBACK, is_usable_translation

_LOCK = threading.Lock()
# novel_id -> (dir_mtime_ns, entry_count, cached_chapters, translated_chapters, translated_nums)
_STATS_CACHE: Dict[str, Tuple[int, int, int, int, List[int]]] = {}

_SENTENCE_SPLIT_RE = re.compile(r"([。！？；\n]+)")


def invalidate_chapter_counts(novel_id: str) -> None:
    """Xoá bộ đệm đếm chương của `novel_id` khi vừa lưu hoặc dịch xong chương mới."""
    if not novel_id:
        return
    with _LOCK:
        _STATS_CACHE.pop(novel_id, None)


def get_chapter_counts(novel_id: str) -> Dict[str, Any]:
    """Đếm số chương đã tải bản gốc và danh sách chương đã dịch AI của `novel_id` (có bộ đệm theo mtime thư mục)."""
    chapters_dir = storage_service.get_chapters_dir(novel_id)
    if not os.path.isdir(chapters_dir):
        return {"cached_chapters": 0, "translated_chapters": 0, "translated_chapter_numbers": []}
    try:
        st = os.stat(chapters_dir)
        mtime_ns = int(getattr(st, "st_mtime_ns", int(st.st_mtime * 1e9)))
        names = [
            f for f in os.listdir(chapters_dir)
            if f.startswith("chapter_") and f.endswith(".json")
        ]
        entry_count = len(names)
    except Exception:
        return {"cached_chapters": 0, "translated_chapters": 0, "translated_chapter_numbers": []}

    with _LOCK:
        cached_row = _STATS_CACHE.get(novel_id)
        if cached_row and cached_row[0] == mtime_ns and cached_row[1] == entry_count:
            return {
                "cached_chapters": cached_row[2],
                "translated_chapters": cached_row[3],
                "translated_chapter_numbers": cached_row[4],
            }

    cached = 0
    translated_nums: List[int] = []
    for fname in names:
        cached += 1
        fpath = os.path.join(chapters_dir, fname)
        try:
            with open(fpath, "r", encoding="utf-8") as f:
                data = json.load(f)
            if (
                isinstance(data, dict)
                and is_usable_translation(data.get("translated_content"))
                and data.get("translation_engine") != ENGINE_FALLBACK
            ):
                ch_no = int(data.get("chapter_number") or fname[len("chapter_"):-len(".json")])
                translated_nums.append(ch_no)
        except Exception:
            pass

    translated_nums.sort()
    translated = len(translated_nums)
    with _LOCK:
        _STATS_CACHE[novel_id] = (mtime_ns, entry_count, cached, translated, translated_nums)
    return {
        "cached_chapters": cached,
        "translated_chapters": translated,
        "translated_chapter_numbers": translated_nums,
    }


def get_novel_translation_meta(novel_id: str) -> Dict[str, Any]:
    """Lấy thông tin tiến độ dịch trên máy chủ và cờ dịch ngầm ban đêm cho ứng dụng di động."""
    counts = get_chapter_counts(novel_id)
    info = storage_service.get_novel_info(novel_id) or {}
    return {
        "translated_chapters": int(counts.get("translated_chapters") or 0),
        "translated_chapter_numbers": counts.get("translated_chapter_numbers") or [],
        "overnight_enabled": bool(info.get("overnight_enabled")),
    }


def polish_novel_synopsis_vi(description: str) -> str:
    """Chuyển nhanh văn án tiếng Trung sang tiếng Việt dễ đọc khi nhập truyện vào kho 1TB."""
    raw = (description or "").strip()
    if not raw or not has_cjk(raw):
        return raw
    d = title_dict.get()
    if d is None:
        return raw
    simp = to_simplified(raw[:1200])
    parts = _SENTENCE_SPLIT_RE.split(simp)
    out = []
    for p in parts:
        if not p:
            continue
        if _SENTENCE_SPLIT_RE.fullmatch(p):
            punct = (
                p.replace("。", ". ")
                .replace("！", "! ")
                .replace("？", "? ")
                .replace("；", "; ")
            )
            out.append(punct)
        else:
            clause = _convert_phrase_vi(p, d).strip()
            if clause:
                clause = clause[0].upper() + clause[1:]
            out.append(clause)
    return tidy_spaces("".join(out)).replace(" .", ".").replace(" ,", ",")
