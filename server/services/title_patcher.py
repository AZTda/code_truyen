"""Apply refined titles to what is already stored (chapters_index.json, chapter files, info.json, search results).

Rule: only replace values this pipeline (or the old Hán-Việt fallback) produced — empty, still Chinese, the
dictionary convert / Hán-Việt rendering, or the old small-map transliteration. Anything else (an older Google or
Antigravity translation, an imported Vietnamese title, a manual fix) has unknown provenance and is never touched.
Writes are atomic (temp file + os.replace) under the storage write lock.
"""

import json
import logging
import os
import time
from typing import Dict, Iterable, List, Optional

from services.hanviet_dict import has_cjk, legacy_transliterate
from services.lru_dict import LruDict
from services.title_dict import title_dict
from services.title_extractor import (
    extract_chapter_title_from_translation,
    format_chapter_title,
    parse_chinese_chapter,
)
from services.title_refiner import PRIORITY_SEARCH_BOOK, title_refiner
from services.title_routing import chapter_dict
from services.title_service import book_title, book_title_replaceable, chapter_subtitle

logger = logging.getLogger("truyencloud.title_patcher")

TOC_SCAN_DEFAULT = 200
_scan_memo: LruDict = LruDict(500)


def _as_int(value, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def chapter_machine_values(num: int, title: str, sub: str) -> set:
    vals = {format_chapter_title(num, title, legacy_transliterate(sub)), format_chapter_title(num, title)}
    d = title_dict.get()
    if d is not None:
        try:
            for vi in (chapter_dict(d, sub)[0], d.hanviet(sub), d.convert(sub)):
                vals.add(format_chapter_title(num, title, vi))
        except Exception:
            pass
    return vals


def chapter_title_replaceable(num: int, title: str, title_vi: Optional[str], sub: str) -> bool:
    current = (title_vi or "").strip()
    if not current or has_cjk(current) or current == f"Chương {num}":
        return True
    return current in chapter_machine_values(num, title, sub)


def toc_scan_limit() -> int:
    try:
        return int(os.getenv("TITLE_REFINE_TOC_SCAN", "") or TOC_SCAN_DEFAULT)
    except ValueError:
        return TOC_SCAN_DEFAULT


def overlay_toc(novel_id: str, chapters: List[dict]) -> bool:
    """Gắn bản đã tinh chỉnh vào TOC đang trả về và xếp hàng các tựa còn là bản từ điển. True nếu có thay đổi.

    Chỉ quét tối đa TITLE_REFINE_TOC_SCAN chương đầu; mỗi truyện quét lại khi có bản tinh chỉnh mới (tối đa mỗi phút
    một lần: `generation` đổi sau MỖI tựa tinh chỉnh xong, quét theo nó thì lượt mở mục lục nào cũng tốn ~50-100 ms
    trên MiniPC khi hàng đợi đang chạy) hoặc sau 10 phút.
    """
    if not chapters or title_dict.get() is None:
        return False
    memo = _scan_memo.get(novel_id)
    now = time.monotonic()
    if memo and memo[1] == len(chapters) and (
            now - memo[2] < 60 or (memo[0] == title_refiner.generation and now - memo[2] < 600)):
        return False
    changed = False
    for idx, ch in enumerate(chapters[:toc_scan_limit()]):
        if not isinstance(ch, dict):
            continue
        num = _as_int(ch.get("chapter_number"), idx + 1)
        title = ch.get("title") or ""
        _, sub = parse_chinese_chapter(title, num)
        if not sub or not has_cjk(sub) or not chapter_title_replaceable(num, title, ch.get("title_vi"), sub):
            continue
        vi, _src = chapter_subtitle(sub, novel_id)
        new = format_chapter_title(num, title, vi)
        if new and new != ch.get("title_vi"):
            ch["title_vi"] = new
            changed = True
    _scan_memo[novel_id] = (title_refiner.generation, len(chapters), now)
    return changed


def patch_toc(novel_id: str, refined: Dict[str, str]) -> int:
    """Thay title_vi trong chapters_index.json (và tệp chương nếu có) cho các tựa vừa được tinh chỉnh."""
    from services.storage_service import storage_service
    path = storage_service.get_chapters_index_path(novel_id)
    changed: List[tuple] = []
    with storage_service._write_lock:
        try:
            with open(path, encoding="utf-8") as f:
                chapters = json.load(f)
        except (OSError, ValueError):
            return 0
        if not isinstance(chapters, list):
            return 0
        for idx, ch in enumerate(chapters):
            if not isinstance(ch, dict):
                continue
            num = _as_int(ch.get("chapter_number"), idx + 1)
            title = ch.get("title") or ""
            _, sub = parse_chinese_chapter(title, num)
            if sub in refined and chapter_title_replaceable(num, title, ch.get("title_vi"), sub):
                ch["title_vi"] = format_chapter_title(num, title, refined[sub])
                changed.append((num, title, sub, ch["title_vi"]))
        if changed:
            storage_service._write_json_atomic(path, chapters)
    for num, title, sub, new in changed:
        try:
            # save_chapter đọc-gộp NGOÀI khoá: giữ khoá (RLock) quanh cả đọc lẫn ghi, kẻo ghi đè mất bản dịch
            # chương mà translation_worker vừa lưu giữa hai bước.
            with storage_service._write_lock:
                stored = storage_service.get_chapter(novel_id, num)
                if stored and chapter_title_replaceable(num, title, stored.get("title_vi"), sub):
                    storage_service.save_chapter(novel_id, num, {"title_vi": new})
        except ValueError:
            continue
    if changed:
        logger.info("Đã cập nhật %d tựa chương đã tinh chỉnh cho truyện %s.", len(changed), novel_id)
    return len(changed)


def patch_book(zh: str, vi: str) -> int:
    """Thay title_vi trong info.json của các truyện trong kho có đúng tựa gốc này (nếu là giá trị máy sinh)."""
    from services.storage_service import storage_service
    from services.title_dict_text import clean_book_source
    n = 0
    for listed in storage_service.list_all_novels():
        if clean_book_source(listed.get("title") or "") != zh or not listed.get("id"):
            continue
        # Đọc lại info.json mới nhất dưới khoá và chỉ ghi title_vi: bản liệt kê ở trên có thể đã cũ (auto-updater
        # vừa cập nhật số chương...), ghi cả dict cũ sẽ lùi các trường đó.
        with storage_service._write_lock:
            info = storage_service.get_novel_info(listed["id"]) or {}
            if not info or not book_title_replaceable(info.get("title") or "", info.get("title_vi") or ""):
                continue
            storage_service.save_novel_info(listed["id"], {"title_vi": vi})
            info = storage_service.get_novel_info(listed["id"]) or {**info, "title_vi": vi}
        try:
            from database import db
            db.table("novels").upsert(info).execute()
        except Exception:
            pass
        n += 1
    if n:
        from services.library_index import library_index
        library_index.invalidate()
    return n


def apply_refined(kind: str, src: str, vi: str, novels: Iterable[str]) -> int:
    try:
        if kind == "chapter":
            from services.title_cache_store import title_cache
            ids = set(novels) | set(title_cache.novels_for("chapter", src))
            return sum(patch_toc(nid, {src: vi}) for nid in sorted(ids))
        if kind == "book":
            return patch_book(src, vi)
    except Exception as e:
        logger.warning("Không áp được tựa đã tinh chỉnh (%s): %s", kind, e)
    return 0


def overlay_novels(novels: Iterable) -> None:
    """Kết quả tìm kiếm/khám phá: tựa còn là bản máy sinh thì lấy bản tốt nhất hiện có (và xếp hàng tinh chỉnh)."""
    for novel in novels:
        title = getattr(novel, "title", "") or ""
        if not has_cjk(title) or not book_title_replaceable(title, getattr(novel, "title_vi", "") or ""):
            continue
        vi, _src = book_title(title, PRIORITY_SEARCH_BOOK)
        if vi and vi != novel.title_vi:
            novel.title_vi = vi
