"""Strict per-item validation of Hy-MT2 outputs for short texts (titles, chapter subtitles, search queries).

A small model in a numbered batch sometimes drifts: lines leak into a neighbour (one item picks up a term that
belongs to another item's hint), numbers go missing, a line keeps Chinese, or it explains itself. Every item is
checked on its own; a rejected item is retried as a single request by the client, never shown.
"""

import re
from typing import Dict, List, Optional, Sequence, Tuple

from services.title_dict_text import cjk_len, fold, has_cjk, title_case
from services.title_pinyin import VnCheck, pinyin_leak

NUM_RE = re.compile(r"^\s*(\d{1,3})\s*[.、．:：)\]]\s*(.*?)\s*$")
_WRAP = "\"'“”‘’「」『』《》【】*`"
_THINK_RE = re.compile(r"<think>.*?</think>", re.S)
_CHATTER_RE = re.compile(
    r"(bản dịch|dịch là|dịch sang|tiếng việt|tiếng trung|translation|translate|lưu ý|giải thích|chú thích|"
    r"xin lỗi|không thể dịch|tạm dịch|\(hoặc|hoặc:|note:)", re.I)
_REFUSAL_ZH = ("不确定", "不知道", "无法", "抱歉", "对不起", "翻译", "以下", "原文", "注意")
_LATIN_RE = re.compile(r"[A-Za-z]")


def strip_think(text: str) -> str:
    return _THINK_RE.sub("", text or "").strip()


def _unwrap(text: str) -> str:
    s = (text or "").strip()
    for _ in range(3):
        if len(s) >= 2 and s[0] in _WRAP and s[-1] in _WRAP:
            s = s[1:-1].strip()
    return s.strip(_WRAP + " ").strip()


def clean_vi(out: str, src: str, vn_check: Optional[VnCheck] = None) -> Optional[str]:
    """Một dòng tiếng Việt sạch cho `src`, hoặc None khi phải bỏ (còn chữ Hán, giải thích, dài bất thường...)."""
    s = strip_think(out or "")
    if not s or "\n" in s.strip():
        return None
    m = NUM_RE.match(s)
    if m and not NUM_RE.match(src or ""):
        s = m.group(2)
    s = _unwrap(s)
    if s.endswith(".") and not s.endswith("...") and not (src or "").rstrip().endswith(("。", ".")):
        s = s[:-1].rstrip()
    if not s or has_cjk(s) or _CHATTER_RE.search(s):
        return None
    n = max(1, cjk_len(src))
    if len(s.split()) > 3 * n + 6 or len(s) > 8 * len(src or "x") + 20:
        return None
    if fold(s) == fold(src) or pinyin_leak(s, src, vn_check):
        return None
    return s


def _ngrams(text: str, n: int = 4) -> set:
    words = fold(text).split()
    return {" ".join(words[i:i + n]) for i in range(len(words) - n + 1)}


def _sources_overlap(a: str, b: str) -> bool:
    """Hai câu nguồn có chung đoạn dài (潜入兰利（上）/（下）) thì bản dịch giống nhau là bình thường."""
    best = 0
    for i in range(len(a)):
        for j in range(len(b)):
            k = 0
            while i + k < len(a) and j + k < len(b) and a[i + k] == b[j + k]:
                k += 1
            best = max(best, k)
    return best >= 2 and best * 2 >= min(len(a), len(b))


def cross_leak(a: str, b: str) -> bool:
    """Hai mục khác nhau trong cùng lô có chung một đoạn dài (>= 4 từ và >= nửa câu ngắn hơn) = dòng bị trộn."""
    shared = _ngrams(a) & _ngrams(b)
    if not shared:
        return False
    shorter = min(len(fold(a).split()), len(fold(b).split()))
    return len(shared) + 3 >= max(4, shorter // 2)


def leaks(out: str, own_src: str, hints: Sequence[Tuple[str, str]]) -> bool:
    """Đầu ra chứa bản dịch gợi ý của một thuật ngữ KHÔNG có trong câu nguồn của chính nó = lọt sang mục khác."""
    folded = " " + fold(out) + " "
    for zh, vi in hints:
        key = fold(vi)
        if zh not in own_src and len(key) >= 4 and (" " + key + " ") in folded:
            return True
    return False


def parse_numbered(raw: str, count: int) -> Dict[int, str]:
    """{số thứ tự: nội dung} của các dòng đánh số hợp lệ; dòng lặp số hoặc ngoài khoảng bị bỏ."""
    got: Dict[int, str] = {}
    dup = set()
    for line in strip_think(raw).splitlines():
        if not line.strip():
            continue
        m = NUM_RE.match(line)
        if not m:
            continue
        n = int(m.group(1))
        if not 1 <= n <= count:
            continue
        if n in got:
            dup.add(n)
        got[n] = m.group(2)
    for n in dup:
        got.pop(n, None)
    return got


def validate_batch(raw: str, sources: Sequence[str], hints: Sequence[Tuple[str, str]],
                   vn_check: Optional[VnCheck] = None) -> List[Optional[str]]:
    """Kết quả hợp lệ theo từng mục (None = phải dịch lại riêng lẻ)."""
    got = parse_numbered(raw, len(sources))
    out: List[Optional[str]] = []
    for i, src in enumerate(sources):
        vi = clean_vi(got.get(i + 1, ""), src, vn_check)
        if vi and leaks(vi, src, hints):
            vi = None
        out.append(vi)
    bad = set()
    for i in range(len(out)):
        for j in range(i + 1, len(out)):
            if out[i] is None or out[j] is None or _sources_overlap(sources[i], sources[j]):
                continue
            same = fold(out[i]) == fold(out[j]) and sources[i] not in sources[j] and sources[j] not in sources[i]
            if same or cross_leak(out[i], out[j]):
                bad.update((i, j))  # hai nguồn khác nhau ra cùng một đoạn: ít nhất một mục đã lấy nhầm dòng
    return [None if i in bad else vi for i, vi in enumerate(out)]


def book_case(vi: str) -> str:
    """Tựa sách hiển thị viết hoa mỗi chữ như quy ước truyện dịch."""
    return title_case(vi)


def clean_zh_query(out: str) -> Optional[str]:
    """Bản dịch Việt -> Trung của một từ khoá tìm kiếm: phải gần như toàn chữ Hán, ngắn, không lời từ chối."""
    s = strip_think(out or "")
    s = s.splitlines()[0] if s else ""
    s = _unwrap(s.strip("。.！!？? ")).strip("。.！!？? ")
    if not s or not has_cjk(s) or len(s) > 30 or any(m in s for m in _REFUSAL_ZH):
        return None
    letters = [c for c in s if not c.isspace() and c not in "，、·：:"]
    if not letters or cjk_len(s) / len(letters) < 0.8 or len(_LATIN_RE.findall(s)) > 2:
        return None
    return s
