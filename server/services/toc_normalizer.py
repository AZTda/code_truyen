"""Table of Contents (TOC) Normalization Engine for TruyenCloud.

Normalizes chapter lists across diverse web novel sources (69shuba, Piaotia,
Uukanshu, Biquge, and generic novel sites):
- Parses Arabic & Chinese numerals (第一章 -> 1, 105.第105章 -> 105)
- Detects and corrects descending/reversed catalogs (e.g. 69shuba)
- Strips duplicate 'Latest Chapters' (最新章节) preview blocks
- Filters out non-chapter navigation links (bookmarks, ads, comments)
- Guarantees sequential, monotonic 1..N chapter numbering
- Provides Sino-Vietnamese (Hán-Việt) title fallback so users never see raw Chinese
"""

import re
import urllib.parse
from typing import List, Dict, Any, Optional, Tuple
from models.novel import ChapterSummary
from services.hanviet_dict import convert_chapter_subtitle, has_cjk

# Non-chapter junk links common on web novel platforms (navigation, ads, pagination)
JUNK_LINK_PATTERNS = [
    re.compile(r"^(?:最新章节|最近更新|目录|全文阅读|加入书架|投推荐票|返回书页|首页|TXT下载|txt下载|书友群|章节错误|点此举报)", re.I),
    re.compile(r"^(?:加入书签|返回目录|上一页|下一页|上一章|下一章|倒序|正序)", re.I),
]

CHINESE_DIGITS = {
    '零': 0, '〇': 0, '一': 1, '二': 2, '两': 2, '三': 3, '四': 4,
    '五': 5, '六': 6, '七': 7, '八': 8, '九': 9
}
CHINESE_UNITS = {'十': 10, '百': 100, '千': 1000, '万': 10000}


def parse_chinese_number(cn: str) -> int:
    """Parses Chinese numerals (e.g. 第一百二十三 -> 123) or Arabic string to integer."""
    if not cn:
        return 0
    clean = cn.strip()
    if clean.isdigit():
        return int(clean)

    total = 0
    current_section = 0
    current_number = 0

    for char in clean:
        if char in CHINESE_DIGITS:
            current_number = CHINESE_DIGITS[char]
        elif char in CHINESE_UNITS:
            unit_val = CHINESE_UNITS[char]
            if unit_val == 10000:
                current_section = (current_section + current_number) * unit_val
                total += current_section
                current_section = 0
                current_number = 0
            else:
                if current_number == 0 and char == '十' and current_section == 0:
                    current_number = 1
                current_section += current_number * unit_val
                current_number = 0
        elif char.isdigit():
            current_number = int(char)

    return total + current_section + current_number


def is_junk_link(title: str, url: str) -> bool:
    """Checks if a link is an ad, navigation link, or non-chapter junk."""
    clean_title = title.strip()
    if not clean_title or len(clean_title) < 2:
        return True
    if url in ["#", "javascript:;"] or url.endswith("#") or url.endswith("javascript:;"):
        return True
    for pat in JUNK_LINK_PATTERNS:
        if pat.search(clean_title):
            return True
    return False


def extract_chapter_info(title: str) -> Tuple[Optional[int], str]:
    """Extracts chapter number and cleaned subtitle from any chapter title."""
    clean = title.strip()
    if not clean:
        return None, ""

    # Strip web prefix artifacts: e.g. "105.第105章" -> "第105章"
    clean = re.sub(r"^\d+[\.、\s\-]+(?=第)", "", clean)

    # Pattern 1: 第X章 / 第X回 / 第X节 / 第X集 (Arabic or Chinese numerals)
    m = re.search(r"第\s*([0-9一二两三四五六七八九十百千万零〇]+)\s*[章节回节集]", clean)
    if m:
        num_str = m.group(1)
        num = int(num_str) if num_str.isdigit() else parse_chinese_number(num_str)
        sub = re.sub(r"^第\s*[0-9一二两三四五六七八九十百千万零〇]+\s*[章节回节集][\s:：\-_]*", "", clean).strip()
        return num, sub or clean

    # Pattern 2: English "Chapter 123" or "Ch. 123"
    m_en = re.search(r"^(?:Chapter|Ch\.)\s*(\d+)", clean, re.I)
    if m_en:
        num = int(m_en.group(1))
        sub = re.sub(r"^(?:Chapter|Ch\.)\s*\d+[\s:：\-_]*", "", clean, flags=re.I).strip()
        return num, sub or clean

    # Pattern 3: Leading numbers: "001 初出茅庐" or "1. 序章"
    m_lead = re.match(r"^(\d+)[\.、\s\-_]+(.*)$", clean)
    if m_lead and int(m_lead.group(1)) < 50000:
        return int(m_lead.group(1)), m_lead.group(2).strip() or clean

    # Special prologue / extras
    if any(k in clean for k in ['楔子', '引子', '序章', '序言']):
        return 0, clean

    return None, clean


def detect_is_descending(items: List[Dict[str, Any]]) -> bool:
    """Detects if chapter items are listed in descending order (e.g. 69shuba)."""
    if len(items) < 3:
        return False

    numbered = []
    for item in items:
        num = item.get("parsed_number")
        if num is not None and num > 0:
            numbered.append(num)

    if len(numbered) < 3:
        # Fallback to checking URL IDs if URLs end in sequential numbers
        url_nums = []
        for item in items:
            m = re.search(r"/(\d+)(?:\.html?)?$", item.get("url", ""))
            if m:
                url_nums.append(int(m.group(1)))
        if len(url_nums) >= 3:
            decreases = sum(1 for i in range(len(url_nums) - 1) if url_nums[i] > url_nums[i + 1])
            if decreases / (len(url_nums) - 1) > 0.65:
                return True
        return False

    # Check parsed number progression
    first_sample = numbered[:min(10, len(numbered))]
    last_sample = numbered[-min(10, len(numbered)):]
    first_avg = sum(first_sample) / len(first_sample)
    last_avg = sum(last_sample) / len(last_sample)

    if first_avg > last_avg + 5:
        return True

    decreases = sum(1 for i in range(len(numbered) - 1) if numbered[i] > numbered[i + 1])
    return (decreases / (len(numbered) - 1)) > 0.60


def strip_duplicate_previews(items: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Strips 'Latest Chapters' preview blocks at the top if they duplicate full list."""
    if len(items) < 10:
        return items

    cutoff = 0
    max_check = min(15, len(items) // 2)
    for idx in range(max_check):
        target_url = items[idx].get("url")
        if not target_url:
            continue
        if any(items[j].get("url") == target_url for j in range(idx + 1, len(items))):
            cutoff = idx + 1
        else:
            break

    if cutoff > 0:
        return items[cutoff:]

    return items


def normalize_chapters(
    raw_chapters: List[Dict[str, Any]],
    base_url: str = ""
) -> List[ChapterSummary]:
    """Normalizes, deduplicates, and re-orders raw chapter links into pristine TOC.

    Args:
        raw_chapters: List of dicts with 'title' and 'url' (and optional 'id', 'title_vi').
        base_url: Optional base URL to resolve relative URLs.

    Returns:
        List[ChapterSummary] with sequential chapter_number (1..N).
    """
    if not raw_chapters:
        return []

    # 1. Clean URLs, resolve duplicates, and filter navigation junk
    cleaned_items: List[Dict[str, Any]] = []
    seen_keys: Dict[str, Dict[str, Any]] = {}

    for item in raw_chapters:
        raw_title = (item.get("title") or "").strip()
        raw_url = (item.get("url") or "").strip()
        if not raw_title:
            continue

        full_url = urllib.parse.urljoin(base_url, raw_url) if (base_url and raw_url) else raw_url
        if is_junk_link(raw_title, full_url):
            continue

        ch_id = str(item.get("id") or "").strip()
        if not ch_id and full_url:
            ch_id = full_url.rstrip("/").split("/")[-1].replace(".html", "").replace(".htm", "")

        dedup_key = full_url if full_url else f"id:{ch_id or raw_title}"

        # If seen before, merge richer data (e.g. translated title_vi) rather than dropping
        if dedup_key in seen_keys:
            existing = seen_keys[dedup_key]
            if not existing.get("title_vi") and item.get("title_vi"):
                existing["title_vi"] = item.get("title_vi").strip()
            if not existing.get("id") and ch_id:
                existing["id"] = ch_id
            continue

        parsed_num, sub_title = extract_chapter_info(raw_title)
        entry = {
            "title": raw_title,
            "title_vi": (item.get("title_vi") or "").strip(),
            "url": full_url,
            "id": ch_id,
            "parsed_number": parsed_num,
            "sub_title": sub_title
        }
        seen_keys[dedup_key] = entry
        cleaned_items.append(entry)

    if not cleaned_items:
        return []

    # 2. Strip duplicate preview blocks
    cleaned_items = strip_duplicate_previews(cleaned_items)

    # 3. Detect and correct descending (reversed) order
    if detect_is_descending(cleaned_items):
        cleaned_items.reverse()

    # 4. Build normalized ChapterSummary list with guaranteed monotonic numbering
    result: List[ChapterSummary] = []
    for idx, item in enumerate(cleaned_items):
        seq_num = idx + 1
        clean_title = item["title"]

        # Ensure title looks clean (strip messy 105. prefixes)
        if item.get("sub_title") and item.get("parsed_number") is not None:
            p_num = item["parsed_number"]
            clean_title = f"第{p_num}章 {item['sub_title']}" if p_num > 0 else clean_title

        # Retain existing Vietnamese title if present; otherwise use Hán-Việt fallback
        title_vi = item.get("title_vi", "")
        if not title_vi and has_cjk(clean_title):
            p_num = item.get("parsed_number") or seq_num
            sub = item.get("sub_title") or clean_title
            trans_sub = convert_chapter_subtitle(sub)
            title_vi = f"Chương {p_num}: {trans_sub}".strip()

        result.append(ChapterSummary(
            chapter_number=seq_num,
            id=item["id"] or str(seq_num),
            title=clean_title,
            title_vi=title_vi,
            url=item["url"]
        ))

    return result
