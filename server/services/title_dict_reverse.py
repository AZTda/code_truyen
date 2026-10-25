"""Reverse lookup vi -> zh over the convert dictionary (accent-insensitive), ranked for source-site search.

One Hán-Việt syllable maps to many characters (kiếm: 6, lai: 20), so candidates come from (1) Names values that
equal the whole query, then (2) a small beam search over syllables that prefers real VietPhrase/Names words
(2..6 chars, bigram-and-up keys) and frequent characters, with accents used when the user typed them.
"""

import math
import re
from typing import List, NamedTuple, Tuple

from services.title_dict import TitleDict
from services.title_dict_text import norm_vi, strip_acc

MAX_SYLLABLES = 12
WORD_SPAN = 6
BEAM = 8
# Từ khoá tiếng Việt dài hơn ngần này âm tiết gần như luôn là câu dịch nghĩa, không phải âm Hán-Việt.
HV_MAX_SYLLABLES = 6


class QueryProfile(NamedTuple):
    syllables: int
    hv_syllables: int

    @property
    def looks_hanviet(self) -> bool:
        return 0 < self.syllables <= HV_MAX_SYLLABLES and self.hv_syllables == self.syllables


def _normalize(query: str) -> Tuple[str, str]:
    q = norm_vi(re.sub(r"[^\w\s]", " ", query or ""))
    return q, strip_acc(q)


def _acc_ok(d: TitleDict, zh: str, syl_acc: List[str]) -> bool:
    return " ".join(d.reading(c) or "" for c in zh) == " ".join(syl_acc)


def profile(d: TitleDict, query: str) -> QueryProfile:
    """Bao nhiêu âm tiết là âm Hán-Việt hợp lệ (có dấu thì phải đúng dấu)."""
    q, qa = _normalize(query)
    syl, syl_acc = qa.split(), q.split()
    has_acc = q != qa
    hv = 0
    for s, s_acc in zip(syl, syl_acc):
        opts = d.char_read(s)
        if opts and (not has_acc or s == s_acc or any(r == s_acc for _, r in opts) or d.has_reading(s, s_acc)):
            hv += 1
    return QueryProfile(len(syl), hv)


def query_candidates(d: TitleDict, query: str, k: int = 4) -> List[str]:
    """Tối đa k chuỗi chữ Hán cho một từ khoá tiếng Việt / Hán-Việt, tốt nhất trước."""
    q, qa = _normalize(query)
    if not qa:
        return []
    has_acc = strip_acc(q) != q
    out: List[str] = sorted(d.name_val(qa), key=lambda z: (norm_vi(d.name(z) or "") != q, -len(z)))
    syl, syl_acc = qa.split(), q.split()
    n = len(syl)
    if n == 0 or n > MAX_SYLLABLES:
        return out[:k]
    best: List[List[Tuple[float, str]]] = [[] for _ in range(n + 1)]
    best[0] = [(0.0, "")]
    for i in range(n):
        if not best[i]:
            continue
        for j in range(i + 1, min(n, i + WORD_SPAN) + 1):
            opts = []
            if j - i >= 2:
                for qual, zh in d.word_read(" ".join(syl[i:j])):
                    acc = (not has_acc) or _acc_ok(d, zh, syl_acc[i:j])
                    opts.append((1.5 * (j - i) + qual + (1.0 if acc else -2.0) * (j - i), zh))
            else:
                for ch, r in d.char_read(syl[i]):
                    acc = (not has_acc) or r == syl_acc[i]
                    opts.append((math.log1p(d.freq(ch)) / 4 + (1.0 if acc else -2.0), ch))
            opts.sort(reverse=True)
            for s, zh in opts[:4]:
                for s0, t0 in best[i]:
                    best[j].append((s0 + s, t0 + zh))
        for j in range(i + 1, n + 1):
            best[j] = sorted(set(best[j]), reverse=True)[:BEAM]
    for _, t in best[n]:
        if t not in out:
            out.append(t)
    return out[:k]
