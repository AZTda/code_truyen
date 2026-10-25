"""zh -> vi algorithms of the convert dictionary (ported 1:1 from the benchmark engine bench/hv_dict.py).

Mixed into TitleDict, which provides the cached lookups: name(), vp(), reading(), prefix_max().
"""

from typing import List, Optional, Tuple

from services.title_dict_text import CJK_RE, NON_CJK_SPLIT_RE, PUNCT, cap_first, split_trailing_punct, tidy_spaces, \
    title_case

NAME_MAX = 8
CONVERT_MAX = 12
# Chữ chức năng: tựa có chúng không nên ép thành Hán-Việt (giống hv_eligible của benchmark).
FUNC_CHARS = set("的了你他她它们被把在吗呢吧啊着过和与跟是有没不都也就又很太这那么从给让对要会能想当")


class ConvertMixin:
    def _longest_name(self, text: str, i: int, skip_whole: bool = False) -> Optional[Tuple[str, int]]:
        name_max = min(self.prefix_max(text[i])[0], NAME_MAX, len(text) - i)
        for length in range(name_max, 1, -1):
            seg = text[i:i + length]
            if skip_whole and seg == text:
                continue
            vi = self.name(seg)
            if vi:
                return vi, length
        return None

    def hanviet(self, text: str) -> str:
        """Hán-Việt viết hoa từng chữ; mục Names nhiều chữ được ưu tiên hơn âm từng chữ."""
        out, i, n = [], 0, len(text)
        while i < n:
            ch = text[i]
            if CJK_RE.match(ch):
                hit = self._longest_name(text, i)
                if hit:
                    out.append(" " + title_case(hit[0]) + " ")
                    i += hit[1]
                    continue
                r = self.reading(ch)
                out.append(" " + (cap_first(r) if r else ch) + " ")
            else:
                out.append(PUNCT.get(ch, ch))
            i += 1
        return tidy_spaces("".join(out))

    def lookup_whole(self, text: str) -> Tuple[Optional[str], Optional[str]]:
        """Trúng nguyên chuỗi trong Names/VietPhrase (dấu câu cuối được giữ riêng)."""
        core, tail = split_trailing_punct(text)
        if not core:
            return None, None
        vi = self.name(core)
        if vi:
            return vi + tail, "names"
        vi = self.vp(core)
        if vi:
            return vi + tail, "vietphrase"
        return None, None

    def convert(self, text: str) -> str:
        """Convert kiểu QuickTranslator (khớp dài nhất Names/VietPhrase, còn lại âm Hán-Việt); giữ trật tự chữ Trung."""
        out, i, n = [], 0, len(text)
        while i < n:
            ch = text[i]
            if not CJK_RE.match(ch):
                out.append(PUNCT.get(ch, ch))
                i += 1
                continue
            name_max, vp_max = self.prefix_max(ch)
            step = 1
            for length in range(min(max(name_max, vp_max, 1), CONVERT_MAX, n - i), 0, -1):
                seg = text[i:i + length]
                vi_name = self.name(seg) if length <= name_max else None
                vi_vp = self.vp(seg) if length <= vp_max else None
                if vi_name and (length > 1 or vi_vp is None):
                    out.append(" " + vi_name + " ")
                    step = length
                    break
                if vi_vp:
                    out.append(" " + vi_vp + " ")
                    step = length
                    break
                if length == 1:
                    out.append(" " + (self.reading(seg) or seg) + " ")
            i += step
        return cap_first(tidy_spaces("".join(out)))

    def segments(self, text: str) -> List[str]:
        """Các mảnh CJK mà convert() dùng (để định tuyến: nhiều mảnh = dễ sai trật tự từ)."""
        segs, i, n = [], 0, len(text)
        while i < n:
            if not CJK_RE.match(text[i]):
                i += 1
                continue
            name_max, vp_max = self.prefix_max(text[i])
            step = 1
            for length in range(min(max(name_max, vp_max, 1), CONVERT_MAX, n - i), 1, -1):
                seg = text[i:i + length]
                if (length <= name_max and self.name(seg)) or (length <= vp_max and self.vp(seg)):
                    step = length
                    break
            segs.append(text[i:i + step])
            i += step
        return segs

    def name_hints(self, text: str, limit: int = 12) -> List[Tuple[str, str]]:
        """Mục Names (>= 2 chữ) tìm được theo khớp dài nhất bên trong chuỗi."""
        hints, i, n = [], 0, len(text)
        while i < n:
            hit = self._longest_name(text, i, skip_whole=True) if CJK_RE.match(text[i]) else None
            if hit:
                hints.append((text[i:i + hit[1]], hit[0]))
                i += hit[1]
            else:
                i += 1
        return hints[:limit]

    def hv_eligible(self, text: str) -> bool:
        segs = [s for s in NON_CJK_SPLIT_RE.split(text) if s]
        return bool(segs) and all(len(s) <= 7 for s in segs) and not any(c in FUNC_CHARS for c in text)
