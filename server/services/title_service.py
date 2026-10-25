"""Instant answers for short texts, no Google: model cache (llm) > convert dictionary > Hán-Việt transliteration.

Book titles and TOC subtitles the routing rules mark as "model does better" are queued for Hy-MT2 in the
background; the dictionary answer is shown until the refined one lands in the cache (next fetch shows it).
"""

import logging
import re
from typing import List, Optional, Tuple

from services.hanviet_dict import has_cjk, legacy_transliterate, transliterate_to_hanviet
from services.title_cache_store import title_cache
from services.title_dict import title_dict
from services.title_dict_text import clean_book_source, fold
from services.title_refiner import PRIORITY_BOOK, PRIORITY_CHAPTER, PRIORITY_TOC_TAIL, title_refiner
from services.title_routing import book_dict, chapter_dict

logger = logging.getLogger("truyencloud.title_service")
# Bộ lọc ký tự cũ của translate_novel_title (xoá cả chữ 全/新... ở bất kỳ đâu) — chỉ dùng để nhận ra giá trị cũ.
_OLD_TITLE_STRIP = re.compile(r"[《》最新章节列表全文阅读]")


def _curated(zh: str) -> str:
    try:
        from services.cover_service import cover_service
        return cover_service.get_vietnamese_title(zh) or ""
    except Exception:
        return ""


def book_title(zh: str, priority: int = PRIORITY_BOOK, enqueue: bool = True) -> Tuple[str, str]:
    """(tựa tiếng Việt, nguồn). Nguồn: latin | curated | llm | names | vietphrase | hanviet | legacy."""
    clean = clean_book_source(zh)
    if not clean or not has_cjk(clean):
        return clean, "latin"
    curated = _curated(clean)
    if curated:
        return curated, "curated"
    refined = title_cache.refined("book", clean)
    if refined:
        return refined, "llm"
    d = title_dict.get()
    if d is None:
        return transliterate_to_hanviet(clean), "legacy"
    try:
        vi, src, need = book_dict(d, clean)
    except Exception as e:
        logger.debug("Tra tựa sách lỗi: %s", e)
        return legacy_transliterate(clean), "legacy"
    if need and enqueue:
        title_refiner.enqueue("book", clean, vi, priority)
    return vi, src


def chapter_subtitle(sub: str, novel_id: Optional[str] = None, enqueue: bool = True,
                     priority: int = PRIORITY_CHAPTER) -> Tuple[str, str]:
    """(tựa chương tiếng Việt, nguồn) cho phần tựa SAU số chương."""
    if not sub or not has_cjk(sub):
        return sub or "", "latin"
    refined = title_cache.refined("chapter", sub)
    if refined:
        return refined, "llm"
    d = title_dict.get()
    if d is None:
        return transliterate_to_hanviet(sub), "legacy"
    try:
        vi, src, need = chapter_dict(d, sub)
    except Exception as e:
        logger.debug("Convert tựa chương lỗi: %s", e)
        return legacy_transliterate(sub), "legacy"
    if need and enqueue:
        title_refiner.enqueue("chapter", sub, vi, priority, novel_id)
    return vi, src


def translate_toc(titles: List[str], novel_id: Optional[str] = None) -> List[str]:
    """Danh sách 'Chương N: ...' cho tựa chương gốc tiếng Trung (luôn đủ phần tử, không gọi mạng)."""
    from services.title_extractor import format_chapter_title, parse_chinese_chapter
    from services.title_patcher import toc_scan_limit
    head = toc_scan_limit()
    out: List[str] = []
    for idx, orig in enumerate(titles):
        num, sub = parse_chinese_chapter(orig or "", idx + 1)
        if not sub:
            out.append(format_chapter_title(num, orig or ""))
            continue
        vi, _src = chapter_subtitle(sub, novel_id, priority=PRIORITY_CHAPTER if idx < head else PRIORITY_TOC_TAIL)
        out.append(format_chapter_title(num, orig, vi) or f"Chương {num}")
    return out


def author_name(zh: str) -> str:
    """Bút danh tác giả: âm Hán-Việt (Names nhiều chữ trước), không dịch nghĩa."""
    return transliterate_to_hanviet(zh)


def legacy_book_values(zh: str) -> set:
    """Những giá trị tựa sách do đường cũ/từ điển sinh ra — được phép thay bằng bản tinh chỉnh."""
    clean = clean_book_source(zh)
    vals = {legacy_transliterate(clean), legacy_transliterate(_OLD_TITLE_STRIP.sub("", zh or "").strip()), clean}
    d = title_dict.get()
    if d is not None:
        try:
            vals.add(book_dict(d, clean)[0])
            vals.add(d.hanviet(clean))
        except Exception:
            pass
    return {fold(v) for v in vals if v}


def book_title_replaceable(zh: str, current_vi: str) -> bool:
    if not current_vi or has_cjk(current_vi):
        return True
    return fold(current_vi) in legacy_book_values(zh)
