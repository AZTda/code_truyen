"""Search keywords vi -> zh from the convert dictionary (instant), and helpers to merge keyword variants.

search_service tries the first CJK keyword on every source and the next two only when results look weak, so the
list puts distinct dictionary candidates first and punctuation / '的'-less variants after them.
"""

from typing import List, Tuple

from services.hanviet_dict import generate_cjk_variants, has_cjk

MAX_VARIANTS = 6


def dictionary_candidates(query: str, k: int = 4) -> Tuple[List[str], bool]:
    """(ứng viên chữ Hán tốt nhất trước, từ khoá có khớp trực tiếp từ điển không). ([], False) khi chưa có từ điển."""
    try:
        from services.query_lexicon import _ACCENTED_MAP
        from services.query_vp_reverse import extract_query_segments_and_hints
        from services.title_dict import title_dict
        from services.title_dict_reverse import profile, query_candidates
        from services.title_dict_text import norm_vi
        d = title_dict.get()
        if d is None:
            return [], False
        vp_cands, _hints = extract_query_segments_and_hints(query, d)
        prof = profile(d, query)
        cands = [c for c in query_candidates(d, query, k) if len(c) >= 2]
        if prof.looks_hanviet:
            # Ưu tiên `vp_cands` lên đầu khi từ khoá nằm trong từ điển tuyển chọn (vd 'bác sĩ' -> '医生',
            # 'giáo sư' -> '教授') hoặc bị ngược trật tự danh-tính từ ('thế giới hoàn mỹ' -> '完美世界').
            prefer_vp = bool(
                vp_cands and cands and vp_cands[0] != cands[0]
                and (norm_vi(query) in _ACCENTED_MAP or not (d.vp(cands[0]) or d.name(cands[0])))
            )
            merged = merge_variants(vp_cands, cands, limit=k) if prefer_vp else merge_variants(cands, vp_cands, limit=k)
            return cjk_variants(merged), True
        # Từ khoá có từ thuần Việt / tên riêng tiếng Anh / thuật ngữ võng văn: kết hợp cụm ghép VietPhrase ngược và Names.
        name_cands = [c for c in cands if c in _names_hits(d, query)]
        combined = merge_variants(vp_cands, name_cands, limit=k)
        return cjk_variants(combined), bool(vp_cands or name_cands)
    except Exception:
        return [], False


def _names_hits(d, query: str) -> List[str]:
    from services.title_dict_reverse import _normalize
    return list(d.name_val(_normalize(query)[1]))


def cjk_variants(candidates: List[str], limit: int = MAX_VARIANTS) -> List[str]:
    """Các ứng viên khác nhau trước, biến thể bỏ dấu câu / bỏ '的' sau."""
    out: List[str] = []
    for c in candidates:
        if c and c not in out:
            out.append(c)
    for c in list(out):
        for v in generate_cjk_variants(c) + [c.replace("的", "")]:
            if v and has_cjk(v) and v not in out:
                out.append(v)
    return out[:limit]


def merge_variants(first: List[str], second: List[str], limit: int = MAX_VARIANTS) -> List[str]:
    out: List[str] = []
    for v in list(first or []) + list(second or []):
        if v and v not in out:
            out.append(v)
    return out[:limit]
