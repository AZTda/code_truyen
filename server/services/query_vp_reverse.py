"""Tra ngược cụm từ tiếng Việt / tiếng Anh / thuật ngữ võng văn sang tiếng Trung (VietPhrase & Hán-Việt đảo cụm).

Giải quyết 4 bài toán cốt lõi khi người dùng tìm truyện:
1. Từ khoá dịch nghĩa võng văn ('bắt đầu đánh dấu', 'khủng bố sống lại', 'ta có một tòa nhà ma').
2. Từ viết tắt, tên riêng tiếng Anh & lỗi gõ phím ('MIT', 'harvard', 'havard', 'stanford', 'marvel', 'hogwarts').
3. Tra ngược trực tiếp trên 1,11 triệu mục VietPhrase + 94 nghìn mục Names ('thợ săn', 'phòng thí nghiệm', 'nhà khoa học').
4. Đảo trật tự [Danh từ trung tâm] + [Bổ ngữ] tiếng Việt sang tiếng Trung ('thế giới hoàn mỹ' -> '完美世界', 'giáo sư đại học' -> '大学教授').
"""

import re
from typing import List, Optional, Sequence, Tuple

from services.query_lexicon import (
    WEBNOVEL_VI_TO_ZH_RAW,  # noqa: F401 (re-export)
    _ACCENTED_MAP,
    _FOLDED_MAP,
    _VN_HEAD_NOUNS,
    fuzzy_lookup_latin,
)
from services.text_similarity import to_simplified
from services.title_dict_text import norm_vi, strip_acc

# Các hư từ tiếng Việt được phép bỏ qua khi tính độ phủ câu truy vấn dài (>= 4 âm tiết)
_STOP_WORDS_FOLD = frozenset({"cua", "va", "voi", "trong", "tren", "duoi", "cho", "de", "la", "thi", "ma"})


def _lookup_span(
    syl_acc: Sequence[str], syl_fold: Sequence[str], has_acc: bool, d=None
) -> Optional[Tuple[str, ...]]:
    """Tìm danh sách chữ Hán ứng với cụm âm tiết `syl_acc` / `syl_fold`."""
    span_acc = " ".join(syl_acc)
    span_fold = " ".join(syl_fold)
    span_has_acc = span_acc != span_fold

    # 1. Ưu tiên cao nhất: Từ điển tuyển chọn võng văn & tên riêng quốc tế
    if has_acc and span_acc in _ACCENTED_MAP:
        return _ACCENTED_MAP[span_acc]
    if not has_acc and span_fold in _FOLDED_MAP:
        return _FOLDED_MAP[span_fold]
    if span_acc in _ACCENTED_MAP:
        return _ACCENTED_MAP[span_acc]

    # 2. Từ đơn tiếng Anh / Latin (vd: 'havard', 'snape', 'gandalf', 'pikachu'): sửa lỗi gõ phím + tra Names/VP
    if len(syl_fold) == 1:
        if not span_has_acc and not (d and d.is_vn_syllable(span_fold)):
            fuzzy_hit = fuzzy_lookup_latin(span_fold)
            if fuzzy_hit:
                return fuzzy_hit
            if d is not None:
                out_single: List[str] = []
                for zh in list(d.name_val(span_fold)[:3]) + list(d.vp_val(span_fold)[:2]):
                    simp = to_simplified(zh)
                    if simp and simp not in out_single:
                        out_single.append(simp)
                if out_single:
                    return tuple(out_single[:3])
        return None

    if d is None:
        return _FOLDED_MAP.get(span_fold) if not has_acc else None

    # 3. Cụm từ >= 2 âm tiết: kết hợp Names + Word_read (Hán-Việt) + VietPhrase ngược (1,11 triệu mục)
    out: List[str] = []
    for zh in d.name_val(span_fold):
        if not span_has_acc or norm_vi(d.name(zh) or "") == span_acc:
            simp = to_simplified(zh)
            if simp and simp not in out:
                out.append(simp)
    for _qual, zh in d.word_read(span_fold)[:6]:
        if span_has_acc:
            readings = [d.reading(c) or "" for c in zh]
            if " ".join(readings) != span_acc:
                continue
        simp = to_simplified(zh)
        if simp and simp not in out:
            out.append(simp)
    if span_has_acc or not out:
        for zh in d.vp_val(span_acc if span_has_acc else span_fold):
            if zh not in out:
                out.append(zh)
    if not out and span_fold in _FOLDED_MAP:
        return _FOLDED_MAP[span_fold]
    return tuple(out[:3]) if out else None


def extract_query_segments_and_hints(
    query: str, d=None
) -> Tuple[List[str], List[Tuple[str, str]]]:
    """Phân tách câu truy vấn tiếng Việt / Anh thành các cụm thuật ngữ + Hán-Việt.

    Trả về:
      - `assembled_candidates`: các từ khoá tiếng Trung ghép trực tiếp (kèm đảo trật tự danh-tính từ).
      - `hints`: danh sách `(cụm_tiếng_Việt, chữ_Hán)` để bơm vào prompt dịch của Hy-MT2.
    """
    q_acc = norm_vi(re.sub(r"[^\w\s]", " ", query or ""))
    q_fold = strip_acc(q_acc)
    if not q_fold:
        return [], []

    syl_acc = q_acc.split()
    syl_fold = q_fold.split()
    has_acc = q_acc != q_fold
    n = len(syl_fold)

    spans: List[Tuple[str, Tuple[str, ...], int]] = []
    covered = 0
    stop_skipped = 0
    i = 0
    while i < n:
        matched = None
        matched_len = 0
        for length in range(min(5, n - i), 0, -1):
            cands = _lookup_span(syl_acc[i : i + length], syl_fold[i : i + length], has_acc, d)
            if cands:
                matched = cands
                matched_len = length
                break
        if matched and matched_len > 0:
            vi_phrase = " ".join(syl_acc[i : i + matched_len])
            spans.append((vi_phrase, matched, matched_len))
            covered += matched_len
            i += matched_len
        else:
            if n >= 4 and syl_fold[i] in _STOP_WORDS_FOLD:
                stop_skipped += 1
            i += 1

    hints: List[Tuple[str, str]] = [(vi_p, zhs[0]) for vi_p, zhs, _ in spans if zhs]
    effective_n = max(1, n - stop_skipped)
    if not spans or covered / effective_n < 0.80:
        return [], hints

    # Sinh tổ hợp từ các cụm khớp (cụm đơn lấy tối đa 3 nghĩa, câu ghép lấy 2 nghĩa mỗi cụm)
    per_span = 3 if len(spans) == 1 else 2
    combos: List[List[str]] = [[]]
    for _vi_p, zhs, _len in spans:
        next_combos: List[List[str]] = []
        for prefix in combos:
            for zh in zhs[:per_span]:
                next_combos.append(prefix + [zh])
        combos = next_combos[:6]

    assembled: List[str] = []
    # Kiểm tra đảo trật tự [Danh từ trung tâm] + [Bổ ngữ] của tiếng Việt -> tiếng Trung
    if len(spans) == 2 and combos:
        for parts in combos:
            z1, z2 = parts[0], parts[1]
            direct = z1 + z2
            swapped = z2 + z1
            direct_known = bool(d and (d.vp(direct) or d.name(direct)))
            swapped_known = bool(d and (d.vp(swapped) or d.name(swapped)))
            if (swapped_known and not direct_known) or (z1 in _VN_HEAD_NOUNS and not direct_known):
                for item in (swapped, direct):
                    if item not in assembled:
                        assembled.append(item)
            else:
                if direct not in assembled:
                    assembled.append(direct)
                if z1 in _VN_HEAD_NOUNS and swapped not in assembled:
                    assembled.append(swapped)
    else:
        for parts in combos:
            joined = "".join(parts)
            if joined and joined not in assembled:
                assembled.append(joined)
        if len(spans) == 1 and n >= 3:
            for k in range(n - 1):
                for zh in _ACCENTED_MAP.get(" ".join(syl_acc[k : k + 2]), ()):
                    if zh not in assembled:
                        assembled.append(zh)

    return assembled[:4], hints
