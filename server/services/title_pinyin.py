"""Detect Chinese names left in pinyin ("Xu Qixian") in a model translation: readers expect Hán-Việt names."""

import re
from functools import lru_cache
from typing import Callable, Optional

_INITIALS = ["zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x", "r", "z",
             "c", "s", "y", "w", ""]
_FINALS = ["iang", "iong", "uang", "ueng", "ang", "eng", "ing", "ong", "ian", "iao", "uai", "uan", "van", "ai",
           "ei", "ao", "ou", "an", "en", "in", "un", "vn", "ia", "ie", "iu", "ua", "uo", "ui", "ue", "ve", "er",
           "a", "o", "e", "i", "u", "v"]
_SYLLABLES = frozenset(i + f for i in _INITIALS for f in _FINALS if i + f)
_MAX = max(len(s) for s in _SYLLABLES)
_WORD_RE = re.compile(r"\w+")
# Dấu thanh pinyin (gạch ngang / dấu móc ngược): tiếng Việt không bao giờ dùng (ǎ khác ă).
_TONE_MARKS = re.compile("[āēīōūǖǎěǐǒǔǚǘǜĀĒĪŌŪǕǍĚǏǑǓǙǗǛ]")
# Âm tiết pinyin có dạng giống hệt tiếng Việt không dấu (vd. "xu", "ma", "an") không bị coi là pinyin lọt.
VnCheck = Callable[[str], bool]


@lru_cache(maxsize=4096)
def is_pinyin_word(word: str) -> bool:
    """Chuỗi chữ cái tách trọn được thành các âm tiết pinyin (qi+xian, yao, da+ni)."""
    w = word.lower()
    n = len(w)
    ok = [True] + [False] * n
    for i in range(1, n + 1):
        for j in range(max(0, i - _MAX), i):
            if ok[j] and w[j:i] in _SYLLABLES:
                ok[i] = True
                break
    return n > 0 and ok[n]


def pinyin_leak(out: str, src: str, is_vn_syllable: Optional[VnCheck]) -> bool:
    """Có dấu thanh pinyin, hoặc từ viết hoa trông như pinyin mà KHÔNG phải âm tiết tiếng Việt (nguồn không có Latin)."""
    if _TONE_MARKS.search(out or ""):
        return True
    if is_vn_syllable is None or re.search(r"[A-Za-z]", src or ""):
        return False
    for word in _WORD_RE.findall(out or ""):
        # Chỉ xét từ toàn chữ cái ASCII: từ tiếng Việt có dấu không bao giờ là pinyin.
        if not (word.isascii() and word.isalpha()) or not word[:1].isupper() or len(word) < 2:
            continue
        if is_pinyin_word(word) and not is_vn_syllable(word.lower()):
            return True
    return False
