"""TTS Voice Service: chuẩn hoá văn bản đọc truyện và quyết định MỘT đoạn văn sẽ được đọc ra sao.

Giọng đọc do VieNeu-TTS chạy trên máy chủ (tts_local.py); không còn dịch vụ giọng nào khác. Với mỗi đoạn, `resolve_tts_plan`
trả về kế hoạch thuần (không I/O ngoài tính đường dẫn): giọng thật, văn bản đã chuẩn hoá, khoá/tệp cache, và loại việc:
  render  : cần engine dựng (hoặc đã có trong cache)
  silent  : không có chữ nào đọc được ('***', '......', '◆◆◆') -> MP3 im lặng dùng chung, không gọi engine
  device  : tiếng Trung - VieNeu không đọc được, giọng máy của điện thoại đọc (máy chủ trả 422 unsupported_language)
Dựng âm thanh + hàng đợi nằm ở tts_prerender.py / tts_synthesis.py; danh mục giọng ở tts_voice_profiles.py.

Chuẩn hoá: bộ chuẩn hoá của chính VieNeu (dùng chung cho Nano và Turbo) đã tự đọc số, %, giờ (12:30), tiền, ngày, viết
tắt, dấu nháy, gạch ngang, "…", "Chương 12:"... (đo trên mô hình thật, cả Nano) nên KHÔNG viết lại những thứ đó. Ở mức
âm vị Nano không bỏ ký tự nào trong các mẫu đã thử; thứ bị mất là ở bước chuẩn hoá chữ của VieNeu, nên ở đây chỉ làm việc
nó KHÔNG làm hoặc làm sai: gấp chữ/số toàn góc 'ＯＫ １２３', chữ ghép 'ﬁ' và số La Mã một ký tự 'Ⅸ' về dạng thường (VieNeu
XOÁ chúng: 'ﬁnal' thành 'nal'); đọc '‰', '№' (VieNeu bỏ qua); bỏ ký hiệu nó đọc thành tiếng ('*' -> "sao", '#' ->
"thăng", '~' -> "khoảng", '@' -> "a còng"); gộp '!!!' (VieNeu biến thành dấu chấm, mất ngữ điệu); giải mã &quot; &amp;;
đổi [ ] thành ( ); thêm nhịp cho thán từ tiếng Việt.
"""

import html
import os
import re
import unicodedata
from dataclasses import dataclass
from typing import Optional, Tuple

from services import tts_cache
from services.tts_language import (  # noqa: F401  (tên cũ vẫn import được từ đây và từ routes.tts)
    EN_COMMON_WORDS,
    detect_language,
    has_speakable,
    is_chinese,
    is_english,
)
from services.tts_voice_profiles import PROFILE_VOICE_CONFIG, identify_voice  # noqa: F401

CACHE_DIR = tts_cache.CACHE_DIR
os.makedirs(CACHE_DIR, exist_ok=True)

KIND_RENDER, KIND_SILENT, KIND_DEVICE = "render", "silent", "device"

_JUNK = re.compile(r"[◆■▲★☆◎▼※▶►✓✔*#~^_|\\@`{}\x00-\x08\x0b\x0c\x0e-\x1f]")
# Toàn góc (U+FF01-FF5E), khoảng trắng chữ Hán (U+3000), chữ ghép Latin (U+FB00-FB06), số La Mã một ký tự (U+2160-2188)
_COMPAT = re.compile("[！-～　ﬀ-ﬆⅠ-ↈ]")
_SYMBOL_WORDS = {True: (("‰", " phần nghìn"), ("№", "số ")), False: (("‰", " per mille"), ("№", "number "))}
_OPEN_BRACKETS = re.compile(r"[\[【〔〖]")
_CLOSE_BRACKETS = re.compile(r"[\]】〕〗]")
_INTERJECTIONS = re.compile(
    r"\b(Haizz|Haizzz|Haiz|Hừm|Hừ|Hừm hừm|Ôi|Chao ôi|Ủa|Hả|Hở|Chậc|Chậc chậc|"
    r"Khụ khụ|Khụ|Ai da|Aida|Á|Ối|Trời ơi|Trời đất ơi|Chết tiệt|Suỵt)\b(?!\s*[,!\?\.…])",
    re.IGNORECASE,
)


def normalize_storyteller_text(text: str, is_vi: bool = True) -> str:
    """Tiền xử lý đọc truyện: ngắt nghỉ, thoại nhân vật, thán từ và dấu câu cho giọng đọc truyền cảm (xem docstring module)."""
    if not text:
        return ""

    t = unicodedata.normalize("NFC", html.unescape(text)).strip()
    t = _COMPAT.sub(lambda m: unicodedata.normalize("NFKC", m.group(0)), t)
    for symbol, words in _SYMBOL_WORDS[bool(is_vi)]:
        t = t.replace(symbol, words)
    t = _JUNK.sub(" ", t)
    t = _OPEN_BRACKETS.sub("(", t)
    t = _CLOSE_BRACKETS.sub(")", t)

    t = re.sub(r'[「『]', '“', t)
    t = re.sub(r'[」』]', '”', t)
    t = re.sub(r'(^|[\s\(\[\{])["\']', r'\1“', t)
    t = re.sub(r'["\'](?=[\s\.,!\?:;\)\]\}]|$)', r'”', t)
    t = t.replace('"', '”')

    t = re.sub(r'“\s+', '“', t)
    t = re.sub(r'\s+”', '”', t)
    t = re.sub(r'([^\s\(\[\{])“', r'\1 “', t)
    t = re.sub(r'”\s*([a-zà-ỹ])', r'”, \1', t)
    t = re.sub(r'”([^\s\.,!\?:;\)\]\}])', r'” \1', t)
    t = re.sub(r'(^|[\.\?!]\s*)[—–\-]\s*', r'\1— ', t)
    t = re.sub(r'\.{2,}|…+', '... ', t)
    t = re.sub(r'\.\.\.([^\s\.,!\?])', r'... \1', t)
    t = re.sub(r'!\?+|\?!+', '?! ', t)
    t = re.sub(r'!{2,}', '! ', t)
    t = re.sub(r'\?{2,}', '? ', t)

    if is_vi:
        t = _INTERJECTIONS.sub(r"\1, ", t)

    t = re.sub(r'[ \t]+', ' ', t)
    t = re.sub(r'\s+([,\.!?;:])', r'\1', t)
    return t.strip()


@dataclass(frozen=True)
class TtsPlan:
    """Kế hoạch đọc một đoạn: không có tác dụng phụ."""

    text: str
    norm_text: str
    lang: str
    voice_id: str
    preset_name: str
    key: str
    md5: str
    cache_file: str
    kind: str
    mode: str = ""


def resolve_tts_plan(clean_text: str, voice: Optional[str], lang: Optional[str] = None, cache_dir: Optional[str] = None) -> TtsPlan:
    """Kế hoạch cho một đoạn. `lang` (vi/en/zh) chỉ quyết định tiếng Trung; khoá cache = mô hình + văn bản + giọng.

    Chuẩn hoá lấy tiếng Việt/Anh từ CHÍNH văn bản (không theo `lang`) để cùng một đoạn luôn ra cùng một khoá dù app
    đoán `lang` khác máy chủ (đoạn quá ngắn / chỉ có số).
    """
    target_dir = cache_dir or tts_cache.CACHE_DIR
    detected = detect_language(clean_text, lang)
    who = identify_voice(voice)
    norm = normalize_storyteller_text(clean_text, is_vi=not is_english(clean_text))

    if detected == "zh":
        kind, key = KIND_DEVICE, tts_cache.cache_key(norm, who.preset_name, who.mode)
    elif not has_speakable(norm, allow_cjk=False):
        kind, key = KIND_SILENT, tts_cache.SILENCE_KEY
    else:
        kind, key = KIND_RENDER, tts_cache.cache_key(norm, who.preset_name, who.mode)
    md5 = tts_cache.md5_of(key)
    mp3_path, _ = tts_cache.paths(target_dir, md5)
    return TtsPlan(clean_text, norm, detected, who.voice_id, who.preset_name, key, md5, mp3_path, kind, who.mode)


def resolve_voice_params(
    clean_text: str, voice: str, speed: float = 1.0, pitch: Optional[str] = None, lang: Optional[str] = None,
    cache_dir: Optional[str] = None,
) -> Tuple[str, str, str, str, str, str]:
    """Tên cũ: (giọng thật, "+0%", "+0Hz", tệp cache, md5, văn bản đã chuẩn hoá). `speed`/`pitch` được nhận và BỎ QUA."""
    plan = resolve_tts_plan(clean_text, voice, lang, cache_dir)
    return plan.preset_name, "+0%", "+0Hz", plan.cache_file, plan.md5, plan.norm_text


def ensure_silence_file(plan: TtsPlan) -> Optional[str]:
    """Đảm bảo tệp im lặng dùng chung có trong cache (không qua hàng đợi/engine)."""
    from services.tts_audio import silence_mp3

    if tts_cache.is_cached(plan.cache_file):
        return plan.cache_file
    try:
        data, ms = silence_mp3()
        return tts_cache.write_audio(os.path.dirname(plan.cache_file), plan.md5, data, ms)
    except OSError:
        return None
