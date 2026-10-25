"""Title and Chapter Name Extractor / Sanitizer Algorithm.

Parses, sanitizes, and validates outputs from AI models (Antigravity, etc.)
and translation services, stripping conversational preambles, meta-commentary, quotes,
and eliminating repetitive chapter naming (e.g., 'Chương 1: Chương 1' -> 'Chương 1').
"""

import re
from typing import Optional, Tuple
from services.hanviet_dict import convert_chapter_subtitle, transliterate_to_hanviet, has_cjk

# Preambles produced by LLMs when translating titles
CONVERSATIONAL_PREFIX_PATTERNS = [
    r"^(?:dĩ nhiên|tất nhiên|chắc chắn|vâng|dạ|xin chào|chào bạn)[^:\n]*[:\n]*",
    r"^(?:dưới đây là|đây là|sau đây là)[^:\n]*(?:tiếng việt|bản dịch|tiêu đề|câu)[^:\n]*[:\n]*",
    r"^(?:tôi sẽ dịch|tôi đã dịch|tôi xin dịch|tôi dịch)[^:\n]*[:\n]*",
    r"^(?:bản dịch|tiêu đề|tên truyện|tựa đề|kết quả|nghĩa là|dịch sang tiếng việt|dịch là)[^:\n]*[:\n]*",
    r"^(?:here is|translation|translated title|the vietnamese title is)[^:\n]*[:\n]*",
    r"^(?:tiếng việt|vietnamese)[:\s\-–—>]+",
]

SUSPICIOUS_FILLER_PHRASES = [
    "dĩ nhiên",
    "tất nhiên",
    "dưới đây là",
    "tiếng việt:",
    "tôi sẽ dịch",
    "một câu tiếng việt",
    "không dịch thành",
    "bản dịch này",
    "không thể dịch",
    "tôi là mô hình",
    "trợ lý ai",
    "ai assistant",
]

# Chinese numerals for chapter parsing
CJK_NUMS = "01234567890一二两三四五六七八九十百千万零"


def is_conversational_filler(text: str) -> bool:
    """Returns True if the text is pure conversational chatter or LLM refusal."""
    if not text:
        return True
    clean = text.strip().lower()
    if clean.endswith(":") or clean.endswith("："):
        return True
    for phrase in SUSPICIOUS_FILLER_PHRASES:
        if phrase in clean:
            return True
    return False


def extract_clean_ai_title(raw_output: str, original_cjk: str = "") -> Optional[str]:
    """Algorithmically extracts and cleans a book or chapter title from raw AI output.

    Handles conversational prefaces, markdown, quotation marks, and filters
    out hallucinations or conversational filler.
    """
    if not raw_output or not raw_output.strip():
        return None

    text = raw_output.strip()

    # 1. Strip markdown code fences
    text = re.sub(r"```[a-zA-Z]*\n?|```", "", text).strip()

    # 2. Split into non-empty lines
    lines = [ln.strip() for ln in text.split("\n") if ln.strip()]
    if not lines:
        return None

    # 3. Filter lines to eliminate conversational chatter
    candidate_lines = []
    for line in lines:
        cleaned = line
        for pat in CONVERSATIONAL_PREFIX_PATTERNS:
            cleaned = re.sub(pat, "", cleaned, flags=re.IGNORECASE).strip()

        lower = cleaned.lower()
        if any(lower.startswith(p) for p in [
            "dĩ nhiên", "tất nhiên", "dưới đây", "đây là", "sau đây", "tôi sẽ",
            "chắc chắn", "bản dịch", "hy vọng", "lưu ý", "chú ý", "nếu bạn", "xin chào"
        ]):
            continue
        if lower in ["tiêu đề:", "bản dịch:", "tiếng việt:", "dịch:", "kết quả:"]:
            continue
        if cleaned:
            candidate_lines.append(cleaned)

    if not candidate_lines:
        return None

    # 4. Take first candidate line and strip surrounding decoration
    cand = candidate_lines[0]
    cand = re.sub(r"^[\*\"\'`«“「《【\[\(\s\->—–:]+|[\*\"\'`»”」》】\]\)\s\->—–:]+$", "", cand).strip()
    cand = re.sub(r"^\*{1,3}|\*{1,3}$", "", cand).strip()
    cand = re.sub(r"^[->—–\s]+|[-–—>\s]+$", "", cand).strip()

    # 5. Remove trailing parenthetical remarks like '(Bản dịch theo ngữ cảnh...)'
    cand = re.sub(r"[\(（\[【].*?[）\)\]】]$", "", cand).strip()

    # 6. Quality validation checks
    if not cand or is_conversational_filler(cand):
        return None

    # Length sanity check: title shouldn't be an essay if original is short
    if original_cjk and len(original_cjk) <= 15 and len(cand) > 70:
        return None

    # If any Chinese characters remain, transliterate them to Sino-Vietnamese
    if has_cjk(cand):
        cand = transliterate_to_hanviet(cand)

    return cand.strip() or None


def parse_chinese_chapter(orig_title: str, fallback_idx: int) -> Tuple[int, Optional[str]]:
    """Extracts chapter number and subtitle from a Chinese chapter title string.

    Returns (chapter_number, subtitle_or_None).
    """
    cleaned = (orig_title or "").strip()
    cleaned = re.sub(r"^[【\[].*?[】\]]\s*", "", cleaned)

    ch_num = fallback_idx
    # Try match chapter number (digits or CJK)
    m_num = re.search(r"第\s*([0-9" + CJK_NUMS + r"]+)\s*[章节回卷]", cleaned)
    if m_num:
        num_str = m_num.group(1).strip()
        if num_str.isdigit():
            ch_num = int(num_str)

    # Strip prefix to get subtitle
    sub = re.sub(r"^第\s*[0-9" + CJK_NUMS + r"]+\s*[章节回卷][:：\s\-]*", "", cleaned).strip()

    # Check if subtitle is just redundant number repetition (e.g. 第一章, 1, 01)
    if not sub:
        return ch_num, None

    redundant_pattern = r"^第?\s*[0-9" + CJK_NUMS + r"]+\s*[章节回卷]?$"
    if re.match(redundant_pattern, sub):
        return ch_num, None

    return ch_num, sub


def format_chapter_title(
    ch_num: int,
    orig_title: str,
    translated_sub: Optional[str] = None
) -> str:
    """Formats chapter title into clean Vietnamese, preventing 'Chương 1: Chương 1'."""
    parsed_num, orig_sub = parse_chinese_chapter(orig_title, ch_num)
    actual_num = parsed_num if parsed_num > 0 else ch_num

    # Handle special chapters (Mở đầu, Ngoại truyện, Lời kết)
    orig_clean = orig_title.strip()
    if any(k in orig_clean for k in ["序章", "楔子", "引子"]):
        return f"Mở đầu: {translated_sub}" if translated_sub and translated_sub != "Mở đầu" else "Chương mở đầu"
    if any(k in orig_clean for k in ["尾声", "后记", "结语"]):
        return f"Lời kết: {translated_sub}" if translated_sub and translated_sub != "Lời kết" else "Chương kết"
    if "番外" in orig_clean:
        return f"Ngoại truyện: {translated_sub}" if translated_sub and translated_sub != "Ngoại truyện" else "Ngoại truyện"

    # If no subtitle or subtitle was redundant
    if not orig_sub:
        return f"Chương {actual_num}"

    sub = (translated_sub or "").strip()
    if not sub:
        sub = convert_chapter_subtitle(orig_sub)

    # Sanitize subtitle
    sub = re.sub(r"^[\*\"\'`«“「《【\[\(\s\->—–:]+|[\*\"\'`»”」》】\]\)\s\->—–:]+$", "", sub).strip()
    if sub.count("(") > sub.count(")"):
        sub += ")"  # ngoặc đóng của ghi chú cuối "(Cầu vé tháng)" bị bộ lọc ký tự rìa cắt mất

    # Check if translated subtitle redundantly repeats chapter number (e.g. "Chương 1", "Hồi 1")
    lower_sub = sub.lower()
    redundant_vi = [
        f"chương {actual_num}",
        f"hồi {actual_num}",
        f"tập {actual_num}",
        f"tiết {actual_num}",
        str(actual_num),
    ]
    if lower_sub in redundant_vi or re.match(r"^(?:chương|hồi|tiết|tập)\s*\d+$", lower_sub):
        return f"Chương {actual_num}"

    return f"Chương {actual_num}: {sub}"


def clean_ai_description(raw_desc: str) -> str:
    """Strips conversational preambles and meta-talk from AI-generated novel descriptions."""
    if not raw_desc:
        return ""
    text = raw_desc.strip()
    text = re.sub(
        r"^(?:dưới đây là|dĩ nhiên|tất nhiên|bản dịch|sau đây là|đây là|tôi sẽ dịch|vâng)[^\n:]*[:：]\s*\n*",
        "",
        text,
        flags=re.IGNORECASE
    ).strip()
    return text


def clean_chapter_title_vi(ch_num: int, title_vi: Optional[str], orig_title: str) -> str:
    """Chuẩn hóa tựa chương tiếng Việt: rỗng/filler/lặp 'Chương N: Chương N' đều
    quay về format_chapter_title. Dùng chung cho routes chapters/novels/scrape."""
    clean = (title_vi or "").strip()
    if not clean or is_conversational_filler(clean) or clean.startswith(f"Chương {ch_num}: Chương {ch_num}"):
        return format_chapter_title(ch_num, orig_title)
    return clean


_TRANSLATED_CH_HEADING_RE = re.compile(
    r"^(?:\*{1,3}|#{1,4}\s*)?(?:chương|hồi|tiết|quyển)\s*(?:thứ\s*)?(\d+)\s*[:：.\-–—]\s*(.+?)(?:\*{1,3})?$",
    re.IGNORECASE,
)


def extract_chapter_title_from_translation(
    ch_num: int, orig_title: str, translated_text: Optional[str]
) -> Optional[str]:
    """Trích xuất tiêu đề chương tiếng Việt trực tiếp từ phần mở đầu bản dịch AI của chương.

    Khi Gemini dịch toàn chương, dòng tiêu đề 'Chương N: ...' nằm trong 6 dòng đầu tiên
    phản ánh chính xác ngữ cảnh của chương dịch. Trả về tiêu đề chuẩn hoá 'Chương N: ...'
    để đồng bộ ngược ra mục lục (chapters_index.json) và CSDL.
    """
    if not translated_text:
        return None
    non_empty = [ln.strip() for ln in translated_text.splitlines() if ln.strip()][:6]
    for line in non_empty:
        m = _TRANSLATED_CH_HEADING_RE.match(line)
        if not m:
            continue
        sub = m.group(2).strip()
        sub = re.sub(r"^[\*\"\'`«“「《【\[\(\s\->—–:]+|[\*\"\'`»”」》】\]\)\s\->—–:]+$", "", sub).strip()
        if not sub or len(sub) > 100 or has_cjk(sub) or is_conversational_filler(sub):
            continue
        return format_chapter_title(ch_num, orig_title or f"第{ch_num}章 {sub}", sub)
    return None
