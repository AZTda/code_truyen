"""Nhận diện ngôn ngữ của một đoạn đọc TTS (vi / en / zh) để chọn đúng giọng.

App hiện tại LUÔN gửi `lang` (TtsTextLanguage ở app quyết định theo TỈ LỆ ký tự) và `lang` luôn thắng. Phần đoán dưới
đây chỉ là dự phòng cho bản app cũ / lời gọi không gửi `lang`, và dùng CÙNG ngưỡng với app để hai phía không lệch nhau.
"""

import re
import unicodedata
from typing import Optional, Tuple

# Chữ Hán chiếm từ tỉ lệ này trên tổng số chữ cái thì cả đoạn là tiếng Trung (vài chữ Hán lẻ trong câu Việt/Anh thì không)
CHINESE_RATIO = 0.3
# Chữ có dấu tiếng Việt chiếm từ tỉ lệ này thì là tiếng Việt; dưới mức đó (tên riêng, 'café') cần bằng chứng tiếng Anh
VIETNAMESE_RATIO = 0.15
MIN_ENGLISH_WORDS_WITH_VIETNAMESE_LETTERS = 2

_VI_LETTERS = frozenset(
    "àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ"
)

EN_COMMON_WORDS = frozenset((
    "a an the i you he she it we they me him her us them my your his its our their mine yours hers ours theirs "
    "myself himself herself itself themselves yourself this that these those which who whom whose what whatever whoever "
    "is am are was were be been being have has had having do does did done doing can could shall should will would may "
    "might must said says say went go goes going gone came come comes coming looked look looks felt feel saw see seen "
    "thought think knew know took take give gave given made make got get and but or nor for yet so as if because "
    "although while in on at to from with without by of about into through after before between under over above below "
    "up down out off not no yes never always often very too also just only even now then there here where when why how "
    "all any some every one two three first last more most other another such than still again back away way man men "
    "time hand eyes face head"
).split())

# Từ tiếng Việt KHÔNG dấu mà tiếng Anh gần như không bao giờ dùng: ít nhất hai từ mới đủ để coi 'Toi khong biet' là Việt
_VI_ASCII_MARKERS = frozenset(
    "khong nguoi duoc nhung cua roi cung toi voi nhu nhieu phai biet muon vua nhin ngay truoc chua cuoi thich".split()
)

_WORD = re.compile(r"[^\W_]+(?:['’-][^\W_]+)*")


def _code(lang: Optional[str]) -> str:
    code = (lang or "").strip().lower()
    for prefix in ("zh", "vi", "en"):
        if code.startswith(prefix):
            return prefix
    return ""


def is_cjk(char: str) -> bool:
    return "一" <= char <= "鿿"


def _counts(text: str) -> Tuple[int, int, int]:
    """(tổng chữ cái, chữ Hán, chữ có dấu tiếng Việt) — chữ Hán cũng tính là chữ cái."""
    letters = cjk = vietnamese = 0
    for c in text:
        if is_cjk(c):
            cjk += 1
            letters += 1
        elif c.isalpha():
            letters += 1
            if c.lower() in _VI_LETTERS:
                vietnamese += 1
    return letters, cjk, vietnamese


def has_cjk(text: str) -> bool:
    return any(is_cjk(c) for c in text)


def is_chinese(text: str, lang: Optional[str] = None) -> bool:
    code = _code(lang)
    if code:
        return code == "zh"
    letters, cjk, _ = _counts(text)
    return cjk > 0 and cjk / letters >= CHINESE_RATIO


def is_english(text: str, lang: Optional[str] = None) -> bool:
    """Xác định văn bản có phải tiếng Anh không (khi không có `lang`)."""
    code = _code(lang)
    if code:
        return code == "en"
    if is_chinese(text):
        return False

    # NFC: chữ Việt ở dạng tách dấu (a + dấu) sẽ bị đếm thành chữ ASCII và đoạn Việt bị đọc bằng giọng Anh
    sample = unicodedata.normalize("NFC", text[:200])
    letters, _, vietnamese = _counts(sample)
    words = _WORD.findall(sample.lower().replace("’", "'"))
    english_hits = sum(1 for w in words if w in EN_COMMON_WORDS)

    if vietnamese:
        if vietnamese / letters >= VIETNAMESE_RATIO:
            return False
        # Vài chữ có dấu (tên riêng 'Nguyễn', từ mượn 'café'): cần bằng chứng tiếng Anh rõ ràng
        return english_hits >= MIN_ENGLISH_WORDS_WITH_VIETNAMESE_LETTERS
    if not letters:
        return False  # chỉ có số / dấu câu: không có bằng chứng tiếng Anh, giữ giọng Việt của app
    vietnamese_hits = sum(1 for w in words if w in _VI_ASCII_MARKERS)
    if vietnamese_hits >= 2 and vietnamese_hits >= english_hits:
        return False  # tiếng Việt không dấu ('Toi khong biet nguoi do'; 'do' trùng từ Anh nhưng không thắng được)
    if english_hits:
        return True
    if len(words) <= 2 and all(w.isascii() and w.isalpha() for w in words):
        return True  # một hai từ thuần ASCII ('ephemeral', 'Lin Feng')
    return all(w.isascii() for w in words)


def is_speakable_char(char: str, allow_cjk: bool) -> bool:
    """Ký tự mà giọng đọc có thể phát âm: chữ cái Latin (kể cả tiếng Việt), chữ số thập phân, và chữ Hán nếu giọng Trung.

    Đã đo với dịch vụ thật: Hy Lạp, Cyrillic, kana, hangul, Thái, Ả Rập và ký hiệu (※ ① %) đều NoAudioReceived; chữ Latin
    toàn góc 'ＯＫ' và chữ ghép 'ﬁ' thì đọc được (qua NFKC).
    """
    if char.isdecimal():
        return True
    if not char.isalpha():
        return False
    for folded in unicodedata.normalize("NFKC", char):
        code = ord(folded)
        if code < 0x250 or 0x1E00 <= code <= 0x1EFF or (allow_cjk and is_cjk(folded)):
            return True
    return False


def has_speakable(text: str, allow_cjk: bool) -> bool:
    return any(is_speakable_char(c, allow_cjk) for c in text)


def detect_language(text: str, lang: Optional[str] = None) -> str:
    """'zh' | 'en' | 'vi'. `lang` tường minh thắng, trừ khi giọng đó không đọc nổi một chữ nào của đoạn."""
    if is_chinese(text, lang):
        return "zh"
    code = "en" if is_english(text, lang) else "vi"
    if has_cjk(text) and not has_speakable(text, allow_cjk=False):
        # Toàn chữ Hán mà bắt giọng Việt/Anh đọc: dịch vụ trả NoAudioReceived (500) và app tắt giọng online
        return "zh"
    return code
