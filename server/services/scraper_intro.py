"""Làm sạch phần giới thiệu truyện lấy từ trang nguồn, dùng chung cho các scraper.

Thẻ meta description của các trang nguồn chỉ là câu SEO chung cho cả trang ("...免费阅读，最新章节..."),
không phải giới thiệu của từng truyện, nên mỗi scraper phải lấy từ khối giới thiệu đúng của trang đó.
"""

import re

MIN_INTRO_CHARS = 20
# Cụm từ SEO chung của các trang nguồn; giới thiệu thật không chứa những cụm này
SEO_BOILERPLATE = ("免费阅读", "最新章节", "无弹窗", "尽力在第一时间更新")
_LABEL = re.compile(r"^(简介[：:]|内容简介[：:])")


def clean_intro(raw: str) -> str:
    """Giữ các dòng có chữ, bỏ nhãn "简介："; trả "" nếu quá ngắn hoặc chỉ là câu SEO chung."""
    lines = [line.strip() for line in (raw or "").splitlines() if line.strip()]
    text = _LABEL.sub("", "\n".join(lines)).strip()
    if len(text) < MIN_INTRO_CHARS or any(cue in text for cue in SEO_BOILERPLATE):
        return ""
    return text
