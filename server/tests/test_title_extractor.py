import pytest
from services.title_extractor import (
    extract_clean_ai_title,
    parse_chinese_chapter,
    format_chapter_title,
    is_conversational_filler
)


def test_extract_clean_ai_title_handles_filler():
    # Exact failure case from user's screenshot
    garbage = "Dĩ nhiên, tôi sẽ dịch thành tiếng Việt. Dưới đây là một câu tiếng Việt:"
    assert extract_clean_ai_title(garbage) is None
    assert is_conversational_filler(garbage) is True


def test_extract_clean_ai_title_extracts_from_multiline_preamble():
    raw = (
        "Dĩ nhiên, tôi sẽ dịch thành tiếng Việt. Dưới đây là một câu tiếng Việt:\n\n"
        "\"Thật xa, thật gần\"\n\n"
        "Dĩ nhiên, tôi sẽ không dịch thành tiếng Việt."
    )
    res = extract_clean_ai_title(raw, "那么远，那么近")
    assert res == "Thật xa, thật gần"


def test_extract_clean_ai_title_various_preambles():
    assert extract_clean_ai_title("Tất nhiên! Dưới đây là tiêu đề: Đấu La Đại Lục") == "Đấu La Đại Lục"
    assert extract_clean_ai_title("Tiêu đề tiếng Việt: Thật xa, thật gần") == "Thật xa, thật gần"
    assert extract_clean_ai_title("Bản dịch: **Già Thiên**") == "Già Thiên"
    assert extract_clean_ai_title("Còn xa, còn gần ->") == "Còn xa, còn gần"
    assert extract_clean_ai_title("《Thật Xa Thật Gần》") == "Thật Xa Thật Gần"


def test_format_chapter_title_prevents_duplicate_number():
    # Format "第1章 第一章" -> "Chương 1"
    res1 = format_chapter_title(1, "第1章 第一章", "Chương 1")
    assert res1 == "Chương 1"

    # Format "第11章 第十一章" -> "Chương 11"
    res11 = format_chapter_title(11, "第11章 第十一章", "Chương 11")
    assert res11 == "Chương 11"

    # Format "第1章 再次相见" with translated subtitle "Gặp lại nhau"
    res_sub = format_chapter_title(1, "第1章 再次相见", "Gặp lại nhau")
    assert res_sub == "Chương 1: Gặp lại nhau"


def test_parse_chinese_chapter_special():
    assert format_chapter_title(0, "序章", None) == "Chương mở đầu"
    assert format_chapter_title(99, "番外 幸福的日常", "Cuộc sống hạnh phúc thường ngày") == "Ngoại truyện: Cuộc sống hạnh phúc thường ngày"
