"""Làm mượt tức thì (0ms) + Dịch gộp ưu tiên Top 5 tên truyện kết quả tìm kiếm bằng Hy-MT2.

Giải quyết triệt để hiện tượng kết quả tìm kiếm lần đầu hiển thị âm Hán-Việt khô cứng
('Hogwarts Đích Lộ Nhân Giáo Thụ', 'Gia Du A, Vũ Y Sinh') trong khi chờ hàng đợi nền.
"""

import re
from typing import Dict, List, Sequence

from models.novel import Novel
from services.hanviet_dict import has_cjk
from services.text_similarity import to_simplified
from services.title_cache_store import title_cache
from services.title_dict import title_dict
from services.title_dict_text import clean_book_source, tidy_spaces, title_case
from services.title_mt_client import title_mt
from services.title_routing import is_modern_title, term_hints

# Các cụm từ hiện đại thường bị từ điển Hán-Việt giữ nguyên âm đọc cổ
_MODERN_GLOSSARY = (
    ("麻省理工学院", "Học viện MIT"),
    ("麻省理工", "MIT"),
    ("霍格沃茨", "Hogwarts"),
    ("霍格沃兹", "Hogwarts"),
    ("哈利波特", "Harry Potter"),
    ("漫威世界", "thế giới Marvel"),
    ("漫威", "Marvel"),
    ("美漫", "Mỹ Mạn"),
    ("普林斯顿", "Princeton"),
    ("加州理工", "Caltech"),
    ("哈佛", "Harvard"),
    ("斯坦福", "Stanford"),
    ("牛津", "Oxford"),
    ("剑桥", "Cambridge"),
    ("博士生", "nghiên cứu sinh tiến sĩ"),
    ("研究生", "nghiên cứu sinh"),
    ("读博", "học tiến sĩ"),
    ("女博士", "nữ tiến sĩ"),
    ("博士", "tiến sĩ"),
    ("博导", "giáo sư hướng dẫn tiến sĩ"),
    ("导师", "giáo sư hướng dẫn"),
    ("论文", "luận văn"),
    ("院士", "viện sĩ"),
    ("诺贝尔奖", "giải Nobel"),
    ("诺贝尔", "Nobel"),
    ("实验室", "phòng thí nghiệm"),
    ("科学家", "nhà khoa học"),
    ("黑科技", "công nghệ đen"),
    ("精神病院", "bệnh viện tâm thần"),
    ("科研系统", "hệ thống nghiên cứu khoa học"),
    ("科研", "nghiên cứu khoa học"),
    ("学霸", "học bá"),
    ("教授", "giáo sư"),
    ("医生", "bác sĩ"),
    ("校长", "hiệu trưởng"),
    ("老师", "giáo viên"),
    ("教师", "giáo viên"),
    ("警察", "cảnh sát"),
    ("刑警", "cảnh sát hình sự"),
    ("律师", "luật sư"),
    ("导演", "đạo diễn"),
    ("演员", "diễn viên"),
    ("歌手", "ca sĩ"),
    ("总裁", "tổng tài"),
    ("路人", "qua đường"),
    ("混子", "kẻ lười"),
    ("麻瓜", "Muggle"),
    ("中国式", "kiểu Trung Quốc"),
    ("加油啊", "cố lên nhé"),
    ("加油", "cố lên"),
    ("没必要", "không cần"),
    ("太正常", "quá bình thường"),
    ("正常", "bình thường"),
    ("过于", "quá mức"),
    ("自律", "tự giác"),
    ("开局签到", "bắt đầu đánh dấu"),
    ("开局", "bắt đầu"),
    ("签到", "đánh dấu"),
    ("重生的我", "trùng sinh ta"),
    ("重生之", "trùng sinh "),
    ("重生", "trùng sinh"),
    ("重回", "trở lại "),
    ("穿越", "xuyên không"),
    ("我只想", "ta chỉ muốn "),
    ("只想", "chỉ muốn "),
    ("带着", "mang theo "),
    ("老公", "chồng"),
    ("老婆", "vợ"),
    ("做", "làm "),
    ("当", "làm "),
)

_CLAUSE_SPLIT_RE = re.compile(r"([：:，,！!？?；;])")


def _convert_phrase_vi(zh_seg: str, d) -> str:
    """Chuyển một cụm chữ Hán ngắn sang tiếng Việt tự nhiên bằng bảng hiện đại + VietPhrase."""
    s = to_simplified(zh_seg.strip())
    if not s:
        return ""
    # Khớp mẫu '从 A 开始' -> 'bắt đầu từ A'
    m_cong = re.match(r"^从(.+?)开始(.*)$", s)
    if m_cong:
        a_vi = _convert_phrase_vi(m_cong.group(1), d)
        rest_vi = _convert_phrase_vi(m_cong.group(2), d) if m_cong.group(2) else ""
        return tidy_spaces(f"bắt đầu từ {a_vi} {rest_vi}")

    # Khớp mẫu '我在 A 当/做 B' -> 'ta ở A làm B'
    m_wo = re.match(r"^我在(.+?)[当做](.+)$", s)
    if m_wo:
        place_vi = _convert_phrase_vi(m_wo.group(1), d)
        role_vi = _convert_phrase_vi(m_wo.group(2), d)
        return tidy_spaces(f"ta ở {place_vi} làm {role_vi}")

    # Đảo cấu trúc sở hữu/bổ nghĩa 'A 的 B' -> 'B của A'
    if "的" in s and not s.startswith("的") and not s.endswith("的"):
        left, right = s.rsplit("的", 1)
        if left and right:
            left_vi = _convert_phrase_vi(left, d)
            right_vi = _convert_phrase_vi(right, d)
            return tidy_spaces(f"{right_vi} của {left_vi}")

    # Thay thế các cụm hiện đại ưu tiên trước, phần chữ Hán còn lại dùng d.convert()
    parts: List[str] = []
    i = 0
    n = len(s)
    while i < n:
        matched = False
        for zh_k, vi_v in _MODERN_GLOSSARY:
            if s.startswith(zh_k, i):
                parts.append(" " + vi_v + " ")
                i += len(zh_k)
                matched = True
                break
        if matched:
            continue
        # Tìm đoạn chữ Hán liên tiếp cho tới cụm glossary kế tiếp
        j = i + 1
        while j < n and not any(s.startswith(zh_k, j) for zh_k, _ in _MODERN_GLOSSARY):
            j += 1
        chunk = s[i:j]
        if d is not None:
            parts.append(" " + d.convert(chunk) + " ")
        else:
            parts.append(" " + chunk + " ")
        i = j

    res = tidy_spaces(" ".join(parts))
    # Làm mềm các hư từ Hán-Việt còn sót
    res = re.sub(r"\bđích\b", "của", res, flags=re.IGNORECASE)
    res = re.sub(r"\bngã tại\b", "ta ở", res, flags=re.IGNORECASE)
    res = re.sub(r"\btại\b", "ở", res, flags=re.IGNORECASE)
    return tidy_spaces(res)


def polish_modern_search_title(zh_title: str, current_vi: str) -> str:
    """Làm mượt tức thì tựa đề hiện đại trong kết quả tìm kiếm nếu chưa có bản dịch LLM."""
    clean = clean_book_source(zh_title)
    if not clean or not has_cjk(clean):
        return current_vi
    simp = to_simplified(clean)
    ref = title_cache.refined("book", clean) or title_cache.refined("book", simp)
    if ref:
        return ref
    if not is_modern_title(simp):
        return current_vi

    d = title_dict.get()
    tokens = _CLAUSE_SPLIT_RE.split(simp)
    out: List[str] = []
    for tok in tokens:
        if not tok:
            continue
        if _CLAUSE_SPLIT_RE.fullmatch(tok):
            punct_map: Dict[str, str] = {"：": ": ", ":": ": ", "，": ", ", ",": ", ", "！": "! ", "？": "? ", "；": "; "}
            out.append(punct_map.get(tok, tok))
        else:
            out.append(_convert_phrase_vi(tok, d))
    polished = title_case(tidy_spaces("".join(out)))
    return polished or current_vi


async def refine_top_search_titles_fast(novels: Sequence[Novel], budget_seconds: float = 1.2) -> None:
    """Dịch gộp ưu tiên Top 5 tên truyện hiện đại bằng Hy-MT2 ngay trong lượt tìm kiếm."""
    if not novels:
        return
    # Bước 1 (0ms): Làm mượt quy tắc cho toàn bộ truyện hiện đại chưa có bản dịch LLM
    to_batch: List[Novel] = []
    batch_srcs: List[str] = []
    for novel in novels:
        if novel.source_id == "local" or not novel.title or not has_cjk(novel.title):
            continue
        clean = clean_book_source(novel.title)
        simp = to_simplified(clean)
        refined = title_cache.refined("book", clean) or title_cache.refined("book", simp)
        if refined:
            novel.title_vi = refined
            continue
        if is_modern_title(simp):
            novel.title_vi = polish_modern_search_title(novel.title, novel.title_vi or "")
            if len(to_batch) < 5 and clean not in batch_srcs:
                to_batch.append(novel)
                batch_srcs.append(clean)

    # Bước 2 (nếu còn ngân sách thời gian & Hy-MT2 đang sống): Dịch gộp 1 lần Top 5 tựa
    if not to_batch or budget_seconds < 0.45:
        return
    try:
        if not await title_mt.healthy():
            return
        d = title_dict.get()
        item_hints = [term_hints(d, src) for src in batch_srcs]
        all_hints = []
        seen_h = set()
        for h_list in item_hints:
            for pair in h_list:
                if pair[0] not in seen_h:
                    seen_h.add(pair[0])
                    all_hints.append(pair)
        vn_check = d.is_vn_syllable if d is not None else None
        results = await title_mt.zh_to_vi_batch(
            batch_srcs,
            hints=all_hints[:10],
            item_hints=item_hints,
            timeout=min(1.2, budget_seconds),
            vn_check=vn_check,
        )
        for novel, src, vi in zip(to_batch, batch_srcs, results):
            if vi:
                vi_titled = title_case(vi)
                novel.title_vi = vi_titled
                title_cache.put_llm("book", src, vi_titled)
    except Exception:
        pass
