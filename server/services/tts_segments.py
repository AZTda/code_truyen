"""Cắt văn bản chương thành các mảnh TTS GIỐNG HỆT app (ParagraphSplitter.kt: splitDisplayParagraphs + buildTtsSegments).

Máy chủ dựng sẵn đúng những mảnh mà app sẽ yêu cầu ở /v1/tts/stream, nên khoá cache khớp từng byte. Các điểm dễ lệch
so với Python thuần, đều đã bám theo Kotlin/JVM:
- độ dài tính theo đơn vị UTF-16 (emoji/chữ Hán hiếm là 2 đơn vị), không theo code point;
- `trim`/`isBlank` của Kotlin dùng Char.isWhitespace (Zs, Zl, Zp + \\t\\n\\v\\f\\r + 0x1C-0x1F), khác str.strip() ở U+0085;
- `\\s` trong Regex của Kotlin chỉ là khoảng trắng ASCII, và `(?i)` chỉ phân biệt hoa/thường ASCII.
Đổi ParagraphSplitter.kt thì phải đổi cả tệp này và tests/test_tts_segments.py.
"""

import re
from dataclasses import dataclass
from typing import List

TTS_SEGMENT_MAX = 250
TTS_SPLIT_THRESHOLD = 300

_KOTLIN_WHITESPACE = (
    "\t\n\x0b\x0c\r\x1c\x1d\x1e\x1f \xa0            "
    "    　"
)
_ASCII_WS = " \t\n\x0b\x0c\r"

_HTML_BR = re.compile(r"<br\s*/?>", re.IGNORECASE | re.ASCII)
_HTML_P_CLOSE = re.compile(r"</p>", re.IGNORECASE | re.ASCII)
_HTML_TAG = re.compile(r"<[^>]+>")
# (?<=[.!?……。！？])\s+  với \s = khoảng trắng ASCII
_SENTENCE_BOUNDARY = re.compile("(?<=[.!?…。！？])[" + re.escape(_ASCII_WS) + "]+")


@dataclass(frozen=True)
class TtsSegment:
    text: str
    paragraph_index: int


def utf16_length(text: str) -> int:
    """String.length của Kotlin/JVM."""
    return len(text.encode("utf-16-le", "surrogatepass")) // 2


def kt_trim(text: str) -> str:
    return text.strip(_KOTLIN_WHITESPACE)


def kt_is_blank(text: str) -> bool:
    return not text.strip(_KOTLIN_WHITESPACE)


def split_display_paragraphs(text: str) -> List[str]:
    """Các đoạn HIỂN THỊ, giữ ranh giới đoạn của tác giả; không bao giờ cắt nhỏ một đoạn."""
    if kt_is_blank(text):
        return []
    cleaned = _HTML_BR.sub("\n", text)
    cleaned = _HTML_P_CLOSE.sub("\n", cleaned)
    cleaned = _HTML_TAG.sub("", cleaned)
    cleaned = cleaned.replace("\r\n", "\n").replace("\r", "\n")
    paragraphs = [p for p in (kt_trim(part) for part in cleaned.split("\n")) if not kt_is_blank(p)]
    return paragraphs or [kt_trim(text)]


def build_tts_segments(display_paragraphs: List[str]) -> List[TtsSegment]:
    """Mảnh cho giọng đọc: đoạn ngắn giữ nguyên, đoạn dài cắt theo ranh giới câu rồi gộp tới ~250 ký tự."""
    segments: List[TtsSegment] = []
    for paragraph_index, paragraph in enumerate(display_paragraphs):
        if utf16_length(paragraph) <= TTS_SPLIT_THRESHOLD:
            segments.append(TtsSegment(paragraph, paragraph_index))
            continue
        sentences = [s for s in (kt_trim(part) for part in _SENTENCE_BOUNDARY.split(paragraph)) if not kt_is_blank(s)]
        if not sentences:
            segments.append(TtsSegment(paragraph, paragraph_index))
            continue
        buffer = ""
        for sentence in sentences:
            if buffer and utf16_length(buffer) + utf16_length(sentence) > TTS_SEGMENT_MAX:
                segments.append(TtsSegment(kt_trim(buffer), paragraph_index))
                buffer = ""
            if buffer:
                buffer += " "
            buffer += sentence
        if buffer:
            segments.append(TtsSegment(kt_trim(buffer), paragraph_index))
    return segments


def tts_texts(text: str) -> List[str]:
    """Chuỗi văn bản của từng mảnh theo thứ tự đọc: đúng danh sách `texts` mà app đưa cho bộ phát online."""
    return [seg.text for seg in build_tts_segments(split_display_paragraphs(text))]
