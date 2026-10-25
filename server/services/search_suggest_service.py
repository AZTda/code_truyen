"""Gợi ý tìm kiếm tức thì (< 5ms, không gọi mạng) khi người dùng đang gõ phím.

Kết hợp 3 tầng dữ liệu trong bộ nhớ + chỉ mục SQLite:
1. Truyện có sẵn trong Kho 1TB trên MiniPC (`library_index`).
2. Bảng tên truyện nổi tiếng tuyển chọn (`ZH_TO_VI_TITLES` / `EXTENDED_TITLE_MAPPING`).
3. Từ điển chủ đề, tên riêng quốc tế (kèm sửa lỗi chính tả `havard` -> `哈佛`) & VietPhrase ngược 1,11 triệu mục.
"""

from typing import Any, Dict, List, Set

from services.cover_service import ZH_TO_VI_TITLES
from services.hanviet_dict import has_cjk
from services.library_index import library_index
from services.query_lexicon import WEBNOVEL_VI_TO_ZH_RAW, fuzzy_lookup_latin
from services.query_title_mapping import EXTENDED_TITLE_MAPPING  # noqa: F401 (đảm bảo nạp ZH_TO_VI_TITLES)
from services.query_vp_reverse import extract_query_segments_and_hints
from services.text_similarity import strip_accents
from services.title_dict import title_dict
from services.title_dict_text import norm_vi, strip_acc, title_case

# Chuẩn bị danh sách (vi_display, vi_fold, zh_tuple) để quét prefix trong < 0.5ms
_LEXICON_ITEMS = [
    (vi_raw, strip_acc(norm_vi(vi_raw)), zh_tuple)
    for vi_raw, zh_tuple in WEBNOVEL_VI_TO_ZH_RAW.items()
]
_FAMOUS_TITLES = [
    (vi_title, strip_acc(vi_title.lower()), zh_title)
    for zh_title, vi_title in ZH_TO_VI_TITLES.items()
]


def suggest_queries(raw_query: str, limit: int = 8) -> List[Dict[str, Any]]:
    """Trả về danh sách gợi ý tức thì cho ô tìm kiếm (tối đa `limit` mục)."""
    q = (raw_query or "").strip()
    if not q or len(q) > 80 or q.startswith("http://") or q.startswith("https://"):
        return []

    q_lower = norm_vi(q)
    q_fold = strip_acc(q_lower)
    if not q_fold:
        return []

    out: List[Dict[str, Any]] = []
    seen_labels: Set[str] = set()

    def _add(kind: str, label: str, sub: str, query_val: str, novel_id: str = "") -> None:
        key = label.lower().strip()
        if not key or key in seen_labels or len(out) >= limit:
            return
        seen_labels.add(key)
        item: Dict[str, Any] = {"kind": kind, "label": label, "sub": sub, "query": query_val}
        if novel_id:
            item["novel_id"] = novel_id
        out.append(item)

    # 1. Kiểm tra khớp chính xác hoặc sửa lỗi chính tả tên riêng/từ khoá ngay dòng đầu tiên
    d = title_dict.get()
    vp_cands, _hints = extract_query_segments_and_hints(q, d)
    fuzzy_zh = fuzzy_lookup_latin(q_fold) if not vp_cands else None
    if vp_cands:
        zh_preview = " · ".join(vp_cands[:3])
        _add(
            "keyword",
            q.upper() if (q.isascii() and len(q) <= 4) else title_case(q),
            f"Dịch chuẩn sang tiếng Trung → {zh_preview}",
            q,
        )
    elif fuzzy_zh:
        zh_preview = " · ".join(fuzzy_zh[:3])
        _add(
            "keyword",
            title_case(q),
            f"Tự động hiểu ý → {zh_preview}",
            q,
        )

    # 2. Truyện có sẵn trong Kho 1TB trên MiniPC
    for e in library_index.get_entries():
        if len(out) >= limit:
            break
        t_vi = e.get("title_vi") or e.get("novel", {}).get("title") or ""
        t_zh = e.get("novel", {}).get("title") or ""
        t_vi_no = e.get("title_vi_no_acc", "")
        t_no = e.get("title_no_acc", "")
        matched = (
            q_fold in t_vi_no
            or q_fold in t_no
            or (has_cjk(q) and q in t_zh)
            or any(cjk in t_zh for cjk in (vp_cands or [])[:2] if has_cjk(cjk))
        )
        if matched and t_vi:
            author = e.get("novel", {}).get("author") or "Kho MiniPC"
            _add("local", t_vi, f"Có sẵn trong Kho MiniPC · {t_zh} ({author})", t_vi, e.get("id", ""))

    # 3. Tên bộ truyện nổi tiếng khớp tiền tố / cụm từ
    for vi_title, vi_fold, zh_title in _FAMOUS_TITLES:
        if len(out) >= limit:
            break
        if vi_fold.startswith(q_fold) or f" {q_fold}" in f" {vi_fold}":
            _add("title", vi_title, f"Truyện nổi tiếng → {zh_title}", vi_title)

    # 4. Từ điển chủ đề / đại học / nhân vật / thuật ngữ võng văn
    for vi_raw, vi_fold, zh_tuple in _LEXICON_ITEMS:
        if len(out) >= limit:
            break
        if vi_fold.startswith(q_fold) or f" {q_fold}" in f" {vi_fold}":
            disp = vi_raw.upper() if (vi_raw.isascii() and len(vi_raw) <= 4) else title_case(vi_raw)
            _add("keyword", disp, f"Chủ đề / Tên riêng → {' · '.join(zh_tuple[:3])}", vi_raw)

    return out[:limit]
