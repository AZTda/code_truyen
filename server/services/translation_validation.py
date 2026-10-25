"""Kiểm tra chất lượng bản dịch chương và kiểu kết quả dùng chung.

Một nơi duy nhất quyết định bản dịch nào là "hỏng": bị bộ lọc an toàn từ chối,
còn sót chữ Hán, hoặc bị cắt cụt. Trước đây mỗi file có một danh sách chuỗi từ
chối riêng nên một bản dịch hỏng có thể lọt qua đường này nhưng bị chặn ở đường kia.
"""

import re
import unicodedata
from dataclasses import dataclass
from typing import Optional

ENGINE_AI = "ANTIGRAVITY"
ENGINE_FALLBACK = "GOOGLE_FALLBACK"

# Chuỗi đặc trưng của bộ lọc an toàn Google/Antigravity — xuất hiện ở đâu cũng là hỏng.
_REFUSAL_MARKERS = (
    "The prompt could not be submitted",
    "violate Google",
    "Generative AI Prohibited Use policy",
)

# Câu từ chối chung chung chỉ xét ở ĐẦU văn bản: giữa chương chúng có thể là lời thoại.
# Dò trên đầu văn bản đã CHUẨN HOÁ (xem _refusal_head): dấu nháy cong -> thẳng, bỏ ký hiệu markdown (* _ # > ~ |),
# bỏ lời dẫn "Dưới đây là bản dịch:", chữ thường. KHÔNG bỏ dấu ngoặc kép hay gạch đầu dòng mở đầu: lời thoại
# ("— Xin lỗi, tôi không thể ở lại") mở đầu chương không phải lời từ chối.
_QUOTE_FOLD = str.maketrans({"\u2019": "'", "\u2018": "'", "\u02bc": "'", "\u00b4": "'", "`": "'"})
_MARKDOWN_NOISE_RE = re.compile(r"[*_#>~|]+")
_PREAMBLE_RE = re.compile(
    r"^(?:dưới đây là bản dịch|bản dịch của bạn|here(?:'s| is) the translation)[^:\n]*:\s*"
)

_EN_APOLOGY = (
    r"(?:(?:i'?m|i am|we'?re|we are)\s+(?:so\s+|very\s+|really\s+|terribly\s+|truly\s+)?(?:sorry|afraid)"
    r"|sorry|i\s+apologi[sz]e|my\s+apologies|apologies|unfortunately|regrettably)"
)
_EN_CANNOT = (
    r"i\s*(?:can'?t|cannot|can\s+not|won'?t|will\s+not|must\s+decline|have\s+to\s+decline"
    r"|(?:am|'m)\s+(?:unable|not\s+able|not\s+going|not\s+permitted|not\s+allowed))"
)
_EN_ASSIST_VERB = (
    r"(?:\s+to)?\s+(?:assist|help|translate|fulfil+|comply|provide|process|continue|complete|generate|produce"
    r"|write|support|accommodate|proceed|create|answer|do\s+(?:that|this)|engage|handle|perform)"
)
_VI_APOLOGY = r"(?:tôi\s+)?(?:xin\s+lỗi|rất\s+tiếc|thật\s+tiếc|tiếc\s+là|thành\s+thật\s+xin\s+lỗi|rất\s+xin\s+lỗi)"
_VI_CANNOT = (
    r"(?:tôi|mình|chúng\s+tôi)\s+(?:thực\s+sự\s+|hiện\s+|rất\s+tiếc\s+)?"
    r"(?:không\s+thể|không\s+được\s+phép|không\s+có\s+khả\s+năng|không\s+dịch\s+được|từ\s+chối)"
)
_VI_ASSIST_VERB = (
    r"\s+(?:dịch|hỗ\s+trợ|giúp|đáp\s+ứng|thực\s+hiện|cung\s+cấp|xử\s+lý|tiếp\s+tục|hoàn\s+thành|tạo|viết"
    r"|trả\s+lời|làm\s+theo|tuân\s+thủ|làm\s+điều)"
)

# Mỗi mẫu so khớp (match, không search) từ ĐẦU văn bản chuẩn hoá
_REFUSAL_PATTERNS = tuple(re.compile(p) for p in (
    # "I'm sorry, but I can't ...", "Sorry, I can't ...", "I apologize, but I cannot ...", "Unfortunately, I cannot ..."
    rf"{_EN_APOLOGY}[,.:;!]?\s*(?:but\s+|however,?\s+)?{_EN_CANNOT}",
    # "I can't assist ...", "I cannot translate ...", "I am unable to help ..."
    rf"{_EN_CANNOT}{_EN_ASSIST_VERB}",
    r"as an? (?:ai|artificial intelligence|(?:large )?language model)\b.{0,80}?\b(?:cannot|can't|unable|not able|do not|don't)",
    # "Xin lỗi, nhưng tôi không thể ...", "Rất tiếc, tôi không thể ...", "Tôi xin lỗi, tôi không thể ..."
    rf"{_VI_APOLOGY}[,.:;!]?\s*(?:nhưng\s+|tuy\s+nhiên,?\s+)?{_VI_CANNOT}",
    # "Tôi không thể dịch ...", "Tôi không thể hỗ trợ ..."
    rf"{_VI_CANNOT}{_VI_ASSIST_VERB}",
    r"(?:là\s+)?một\s+(?:mô\s+hình\s+ngôn\s+ngữ|trí\s+tuệ\s+nhân\s+tạo).{0,60}?(?:không\s+thể|không\s+được)",
    # Từ chối bằng tiếng Trung: "抱歉，我无法...", "对不起，我不能..."
    r"(?:很?抱歉|对不起|非常抱歉)[，,]?\s*(?:但是?|不过)?\s*我?(?:无法|不能|不可以)",
))

_CJK_RE = re.compile(r"[㐀-䶿一-鿿豈-﫿]")


@dataclass
class ChapterTranslation:
    """Kết quả dịch một chương kèm nguồn gốc (AI thật hay dự phòng Google)."""
    text: str
    engine: str = ENGINE_AI
    is_fallback: bool = False
    from_cache: bool = False


def _refusal_head(text: str) -> str:
    """Đầu văn bản đã chuẩn hoá để dò câu từ chối (xem chú thích ở _REFUSAL_PATTERNS)."""
    head = unicodedata.normalize("NFC", text.strip())[:300].translate(_QUOTE_FOLD)
    head = _MARKDOWN_NOISE_RE.sub("", head).lower()
    head = re.sub(r"\s+", " ", head).strip()
    return _PREAMBLE_RE.sub("", head)


def is_refusal(text: Optional[str]) -> bool:
    """True nếu đầu ra là lời từ chối của AI thay vì bản dịch."""
    if not text:
        return False
    if any(marker in text for marker in _REFUSAL_MARKERS):
        return True
    head = _refusal_head(text)
    return any(pattern.match(head) for pattern in _REFUSAL_PATTERNS)


def cjk_count(text: Optional[str]) -> int:
    return len(_CJK_RE.findall(text or ""))


def has_excess_cjk(text: Optional[str]) -> bool:
    """Còn sót chữ Hán quá ngưỡng. Vài ký tự lẻ (tên riêng trích dẫn) thì bỏ qua."""
    if not text:
        return False
    count = cjk_count(text)
    return count > max(3, int(len(text) * 0.01))


def _mode(target_language: Optional[str]) -> str:
    lang = (target_language or "vi").lower()
    if lang in ("bilingual", "song ngữ", "song ngu"):
        return "bilingual"
    return "en" if lang in ("en", "english") else "vi"


def paragraph_count(text: Optional[str], target_language: str = "vi") -> int:
    lines = [ln.strip() for ln in (text or "").splitlines() if ln.strip()]
    if _mode(target_language) == "bilingual":
        vi_lines = [ln for ln in lines if ln.upper().startswith("[VI]")]
        return len(vi_lines) if vi_lines else len(lines) // 2
    return len(lines)


# Số ký tự bản dịch trên MỖI chữ Hán. Đo trên các chương đã dịch thật: tiếng Việt
# 4.18-4.68, tiếng Anh 4.5-4.65 (song ngữ = EN + VI nên gấp đôi). Dưới ngưỡng CỨNG
# là chắc chắn thiếu/tóm tắt; dưới ngưỡng MỀM chỉ đáng ngờ khi số đoạn cũng rụng.
_HARD_FLOOR = {"vi": 2.5, "en": 2.5, "bilingual": 5.0}
_SOFT_CEIL = {"vi": 3.4, "en": 3.4, "bilingual": 6.8}
_MIN_PARAGRAPH_SHARE = 0.6
# Nguồn tiếng Trung NGẮN (< 200 chữ Hán, vd chương ghi chú của tác giả): quá ít để đo tỉ lệ ký tự/chữ Hán, nhưng bản
# dịch thật luôn dài gấp nhiều lần bản gốc (~4.3 ký tự/chữ Hán), còn lời từ chối ngắn hơn cả chính bản gốc.
_SHORT_SOURCE_MIN_RATIO = 0.5
_VI_MARKS_RE = re.compile(
    r"[àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ]", re.IGNORECASE
)
_LATIN_WORD_RE = re.compile(r"[A-Za-z]{3,}")


def is_truncated(source: str, output: str, target_language: str = "vi") -> bool:
    """Bản dịch thiếu hẳn một phần chương so với bản gốc.

    Hai phép đo: độ dài (ký tự/chữ Hán) và số đoạn (< 60% số đoạn gốc). Số đoạn
    KHÔNG đứng một mình: nguồn ngắt dòng cứng (TXT nhập từ máy) bị AI gộp lại
    thành đoạn văn thật sự làm số đoạn rụng mà bản dịch vẫn đầy đủ.
    """
    src = (source or "").strip()
    out = (output or "").strip()
    if not src:
        return False
    if not out:
        return True

    hanzi = cjk_count(src)
    non_space = len(re.sub(r"\s", "", src))
    mostly_chinese = hanzi > 0 and hanzi >= 0.3 * non_space
    if hanzi < 200 or not mostly_chinese:
        # Nguồn không phải tiếng Trung (đường dịch chồng VI -> EN cũ) hoặc quá ngắn
        if non_space >= 600 and len(out) / len(src) < 0.35:
            return True
        return mostly_chinese and len(out) < _SHORT_SOURCE_MIN_RATIO * len(src)

    mode = _mode(target_language)
    ratio = len(out) / hanzi
    if ratio < _HARD_FLOOR[mode]:
        return True
    src_paras = paragraph_count(src, "vi")
    if src_paras >= 5 and ratio < _SOFT_CEIL[mode]:
        return paragraph_count(out, mode) < _MIN_PARAGRAPH_SHARE * src_paras
    return False


def is_untranslated_reply(source: str, output: str, target_language: str = "vi") -> bool:
    """Nguồn tiếng Trung ngắn, đích tiếng Việt mà đầu ra chỉ có chữ La-tinh không một dấu tiếng Việt nào.

    Đó là AI trả lời bằng tiếng Anh (lời từ chối, lời giải thích) chứ không phải bản dịch: bản dịch tiếng Việt thật
    luôn có dấu. Chỉ xét nguồn ngắn: nguồn dài đã bị is_truncated/is_refusal chặn và đầu ra dài có thể chứa tên riêng La-tinh.
    """
    if _mode(target_language) != "vi":
        return False
    hanzi = cjk_count(source)
    non_space = len(re.sub(r"\s", "", source or ""))
    if hanzi == 0 or hanzi >= 200 or hanzi < 0.3 * non_space:
        return False
    return bool(_LATIN_WORD_RE.search(output)) and not _VI_MARKS_RE.search(output)


def validate_ai_translation(source: str, output: Optional[str], target_language: str = "vi") -> Optional[str]:
    """Trả lý do hỏng (chuỗi ngắn) hoặc None nếu bản dịch dùng được."""
    if not output or not output.strip():
        return "empty"
    if is_refusal(output):
        return "refusal"
    if has_excess_cjk(output):
        return "cjk_leftover"
    if is_truncated(source, output, target_language):
        return "truncated"
    if is_untranslated_reply(source, output, target_language):
        return "not_vietnamese"
    return None


def is_usable_translation(text: Optional[str]) -> bool:
    """Bản dịch đã lưu có đủ tốt để trả thẳng cho độc giả không."""
    return bool(text and text.strip()) and not is_refusal(text) and not has_excess_cjk(text)
