"""Routing rules: which short texts the dictionary answers for good, and which ones Hy-MT2 should refine.

Benchmark finding: the dictionary is near-perfect for Hán-Việt style titles (95.8% exact) but forces modern,
semantic titles into Hán-Việt ("Boston Ái Tình Cố Sự", "Bác Sĩ Sinh Luyến Ái Quan Sát"), and convert keeps Chinese
word order in chapter subtitles ("Quốc sư đích đề nghị"). The model is the opposite (Hán-Việt titles 10%).
"""

import re
from typing import Optional, Tuple

from services.title_dict import FUNC_CHARS, TitleDict
from services.title_dict_text import CJK_RUN_RE, cap_first, title_case

# Hư từ / đại từ hiện đại: gần như không xuất hiện trong tựa kiểu Hán-Việt.
STRONG_FUNC = set("的了吗呢吧啊着被把么们她你")
WEAK_FUNC = set("我他它在和跟是有没很太这那从给让对要会能想当都也就又")
# Từ vựng hiện đại (đô thị, học đường, mạng, tình cảm...) — dịch nghĩa mới đúng quy ước truyện dịch.
MODERN_WORDS = (
    "爱情", "恋爱", "故事", "观察", "大学", "校长", "饶命", "老婆", "老公", "医院", "精神病", "学霸", "黑科技",
    "科普", "全网", "美剧", "菜鸟", "警探", "校园", "总裁", "明星", "直播", "网游", "游戏", "手机", "电脑", "网络",
    "网红", "公司", "老板", "同学", "老师", "学生", "博士生", "研究生", "男友", "女友", "男朋友", "女朋友",
    "结婚", "离婚", "前夫", "前妻", "贴贴", "偷偷", "藏不住", "回来", "一起", "喜欢", "宝宝", "哥哥", "姐姐",
    "先生", "小姐", "同桌", "学长", "学姐", "室友", "邻居", "豪门", "娱乐圈", "综艺", "热搜", "穿书", "氧气",
    "警察", "侦探", "医生", "护士", "律师", "电影", "球队", "篮球", "足球", "教练", "高考", "高中", "初中",
    "漫威", "霍格沃茨", "霍格沃兹", "哈佛", "麻省", "斯坦福", "牛津", "剑桥", "教授", "开局", "签到",
    "模拟器", "面板", "反派", "退婚", "神豪", "科研", "留学", "导演", "演员", "歌手", "文豪",
    "海贼", "火影", "柯南", "宝可梦", "赛博", "克苏鲁", "基金会",
)
MODERN_CHARS = {"哄": 2, "撩": 2, "骂": 2, "萌": 1, "宠": 1, "甜": 1, "娃": 1}
MODERN_THRESHOLD = 2
LONG_SEGMENT = 9
_LATIN_WORD = re.compile(r"[A-Za-z]{2,}")
_NOTE_RE = re.compile(r"\s*[（(]([^（）()]*)[）)]\s*$")
_CJK_DIGITS = {"零": 0, "〇": 0, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8,
               "九": 9}
_PART_MARKERS = {"上": "thượng", "中": "trung", "下": "hạ", "完": "hết", "终": "hết"}


def modern_score(zh: str) -> int:
    text = zh or ""
    score = sum(2 for c in text if c in STRONG_FUNC) + sum(1 for c in text if c in WEAK_FUNC)
    score += sum(2 for w in MODERN_WORDS if w in text)
    score += sum(w for c, w in MODERN_CHARS.items() if c in text)
    if any(len(run) >= LONG_SEGMENT for run in CJK_RUN_RE.findall(text)):
        score += 1
    if _LATIN_WORD.search(text):
        score += 1
    return score


def is_modern_title(zh: str) -> bool:
    return modern_score(zh) >= MODERN_THRESHOLD


def book_dict(d: TitleDict, zh: str) -> Tuple[str, str, bool]:
    """(bản tức thì, nguồn, có nên nhờ mô hình tinh chỉnh). Trúng nguyên tựa trong Names/VietPhrase là chốt."""
    vi, src = d.lookup_whole(zh)
    if vi:
        return title_case(vi), src or "dict", False
    return d.hanviet(zh), "hanviet", is_modern_title(zh)


def _cjk_number(text: str) -> Optional[int]:
    if text.isdigit():
        return int(text)
    if not text or any(c not in _CJK_DIGITS and c != "十" for c in text):
        return None
    if "十" not in text:
        return int("".join(str(_CJK_DIGITS[c]) for c in text)) if len(text) <= 3 else None
    tens, _, ones = text.partition("十")
    if len(tens) > 1 or len(ones) > 1:
        return None
    return (_CJK_DIGITS[tens] if tens else 1) * 10 + (_CJK_DIGITS[ones] if ones else 0)


def split_note(sub: str) -> Tuple[str, str]:
    """('潜入兰利', '上'): ghi chú cuối trong ngoặc (phần, xin phiếu...) được dịch riêng bằng từ điển."""
    m = _NOTE_RE.search(sub or "")
    if not m or not sub[:m.start()].strip():
        return sub, ""
    return sub[:m.start()].strip(), m.group(1).strip()


def note_vi(d: TitleDict, note: str) -> str:
    num = _cjk_number(note)
    if num is not None:
        return str(num)
    return _PART_MARKERS.get(note) or d.convert(note)


def chapter_dict(d: TitleDict, sub: str) -> Tuple[str, str, bool]:
    """Tựa chương: trúng nguyên chuỗi thì chốt; còn lại convert ngay, mô hình sửa nền khi convert dễ sai trật tự."""
    vi, src = d.lookup_whole(sub)
    if vi:
        return cap_first(vi), src or "dict", False
    main, note = split_note(sub)
    if note:
        main_vi, _ = d.lookup_whole(main)
        body = cap_first(main_vi) if main_vi else d.convert(main)
        return f"{body} ({note_vi(d, note)})", "convert", not main_vi and chapter_needs_llm(d, main)
    return d.convert(sub), "convert", chapter_needs_llm(d, sub)


def chapter_needs_llm(d: TitleDict, sub: str) -> bool:
    """Convert giữ trật tự chữ Trung: ổn khi tựa chỉ gồm <= 2 mảnh từ điển và không có hư từ; còn lại nhờ mô hình."""
    if any(c in FUNC_CHARS for c in sub) or modern_score(sub) >= MODERN_THRESHOLD:
        return True
    segs = d.segments(sub)
    if len(segs) >= 3:
        return True
    single_unknown = [s for s in segs if len(s) == 1 and d.vp(s) is None and d.name(s) is None]
    return bool(single_unknown) and len(sub) >= 3


def term_hints(d: Optional[TitleDict], zh: str, limit: int = 8):
    """Gợi ý thuật ngữ cho mô hình: tên riêng trong Names, rồi thuật ngữ giữ âm Hán-Việt (VietPhrase).

    Đo trên bộ test: thêm thuật ngữ Hán-Việt làm chrF tựa chương được mô hình sửa tăng 45,6 -> 49,7.
    """
    if d is None:
        return []
    try:
        hints = d.name_hints(zh, limit)
        seen = {z for z, _ in hints}
        for seg in d.segments(zh):
            if len(hints) >= limit:
                break
            if len(seg) >= 2 and seg not in seen and seg != zh:
                vi = d.hv_term(seg)
                if vi:
                    hints.append((seg, vi))
                    seen.add(seg)
        return hints[:limit]
    except Exception:
        return []
