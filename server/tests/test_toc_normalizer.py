"""Tests for Table of Contents (TOC) Normalization Engine."""

import pytest
from services.toc_normalizer import (
    parse_chinese_number,
    extract_chapter_info,
    detect_is_descending,
    strip_duplicate_previews,
    normalize_chapters,
    is_junk_link,
)


def test_chinese_number_parser():
    """Test comprehensive Chinese numeral conversion."""
    assert parse_chinese_number("一") == 1
    assert parse_chinese_number("二") == 2
    assert parse_chinese_number("两") == 2
    assert parse_chinese_number("十") == 10
    assert parse_chinese_number("十一") == 11
    assert parse_chinese_number("十二") == 12
    assert parse_chinese_number("二十") == 20
    assert parse_chinese_number("二十五") == 25
    assert parse_chinese_number("一百") == 100
    assert parse_chinese_number("一百零五") == 105
    assert parse_chinese_number("一百一十二") == 112
    assert parse_chinese_number("九百九十九") == 999
    assert parse_chinese_number("一千二百三十四") == 1234
    assert parse_chinese_number("两千五百") == 2500
    assert parse_chinese_number("123") == 123


def test_extract_chapter_info():
    """Test extracting chapter numbers and clean subtitles from varied formats."""
    # Standard Arabic
    num, sub = extract_chapter_info("第1章 表演个，徒手劈榴莲吧")
    assert num == 1
    assert sub == "表演个，徒手劈榴莲吧"

    # Prefixed numbers (69shuba pattern)
    num, sub = extract_chapter_info("105.第105章 心动答卷")
    assert num == 105
    assert sub == "心动答卷"

    # Chinese numerals
    num, sub = extract_chapter_info("第一百二十章 仙界巨变")
    assert num == 120
    assert sub == "仙界巨变"

    # Large Chinese numerals
    num, sub = extract_chapter_info("第两千三百四十五章 最终决战")
    assert num == 2345
    assert sub == "最终决战"

    # English format
    num, sub = extract_chapter_info("Chapter 42: The Meaning")
    assert num == 42
    assert sub == "The Meaning"

    # Leading number format
    num, sub = extract_chapter_info("001 初出茅庐")
    assert num == 1
    assert sub == "初出茅庐"

    # Prologue
    num, sub = extract_chapter_info("楔子 天地初开")
    assert num == 0


def test_junk_link_filter():
    """Test rejection of non-chapter navigation and ad links."""
    assert is_junk_link("最新章节", "https://example.com/latest") is True
    assert is_junk_link("加入书架", "https://example.com/bookshelf") is True
    assert is_junk_link("TXT下载", "https://example.com/download.txt") is True
    assert is_junk_link("目录", "https://example.com/catalog") is True
    assert is_junk_link("", "https://example.com/book/#") is True
    assert is_junk_link("正文", "https://example.com/book/#") is True
    assert is_junk_link("第1章 启程", "https://example.com/txt/1.htm") is False


def test_detect_is_descending():
    """Test detection of descending (reversed) chapter lists."""
    reversed_items = [
        {"parsed_number": 105, "url": "https://example.com/105.htm"},
        {"parsed_number": 104, "url": "https://example.com/104.htm"},
        {"parsed_number": 103, "url": "https://example.com/103.htm"},
        {"parsed_number": 2, "url": "https://example.com/2.htm"},
        {"parsed_number": 1, "url": "https://example.com/1.htm"},
    ]
    assert detect_is_descending(reversed_items) is True

    normal_items = [
        {"parsed_number": 1, "url": "https://example.com/1.htm"},
        {"parsed_number": 2, "url": "https://example.com/2.htm"},
        {"parsed_number": 3, "url": "https://example.com/3.htm"},
    ]
    assert detect_is_descending(normal_items) is False


def test_strip_duplicate_previews():
    """Test stripping duplicate 'Latest Chapters' blocks at top."""
    # 3 preview items at top that repeat at the end of the 15-item list
    items = [
        {"title": "第10章", "url": "https://example.com/10.htm"},
        {"title": "第11章", "url": "https://example.com/11.htm"},
        {"title": "第12章", "url": "https://example.com/12.htm"},
    ]
    for i in range(1, 13):
        items.append({"title": f"第{i}章", "url": f"https://example.com/{i}.htm"})

    stripped = strip_duplicate_previews(items)
    assert len(stripped) == 12
    assert stripped[0]["title"] == "第1章"
    assert stripped[-1]["title"] == "第12章"


def test_normalize_chapters_full_pipeline():
    """Test full TOC normalization pipeline on reversed list with junk links."""
    raw = [
        {"title": "", "url": "https://example.com/book/#"},  # Empty / anchor junk
        {"title": "最新章节", "url": "https://example.com/latest"},  # Junk
        {"title": "105.第105章 心动答卷", "url": "https://example.com/txt/105.htm"},
        {"title": "104.第104章 评论", "url": "https://example.com/txt/104.htm"},
        {"title": "第2章 账目", "url": "https://example.com/txt/2.htm"},
        {"title": "第1章 劈榴莲", "url": "https://example.com/txt/1.htm"},
        {"title": "加入书架", "url": "https://example.com/fav"},  # Junk
    ]

    normalized = normalize_chapters(raw)
    assert len(normalized) == 4
    # Check proper order: chapter 1 first, chapter 105 last
    assert normalized[0].chapter_number == 1
    assert "第1章" in normalized[0].title
    assert normalized[0].title_vi.startswith("Chương 1:")

    assert normalized[1].chapter_number == 2
    assert "第2章" in normalized[1].title

    assert normalized[2].chapter_number == 3
    assert "第104章" in normalized[2].title

    assert normalized[3].chapter_number == 4
    assert "第105章" in normalized[3].title
    assert normalized[3].title_vi.startswith("Chương 105:")
