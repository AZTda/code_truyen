"""Text helpers shared by the convert dictionary, the title router and the MT validator (stdlib only)."""

import re
import unicodedata

CJK_RE = re.compile(r"[㐀-䶿一-鿿]")
CJK_RUN_RE = re.compile(r"[㐀-䶿一-鿿]+")
NON_CJK_SPLIT_RE = re.compile(r"[^㐀-䶿一-鿿]+")

# Dấu câu Trung -> Việt khi ghép bản convert (khớp bộ chấm điểm benchmark).
PUNCT = {
    "，": ", ", "、": ", ", "：": ": ", "；": "; ", "！": "!", "？": "?", "。": ". ", "（": " (", "）": ") ",
    "(": " (", ")": ") ", "《": "", "》": "", "“": '"', "”": '"', "·": " ", "—": " - ", "…": "...",
    "～": "~", "-": "-", "　": " ",
}
TRAILING_PUNCT = "！？!?。…"


def has_cjk(text: str) -> bool:
    return bool(text) and bool(CJK_RE.search(text))


def strip_acc(text: str) -> str:
    text = (text or "").replace("đ", "d").replace("Đ", "D")
    return "".join(c for c in unicodedata.normalize("NFD", text) if unicodedata.category(c) != "Mn")


def norm_vi(text: str) -> str:
    return re.sub(r"\s+", " ", unicodedata.normalize("NFC", (text or "").lower())).strip()


def fold(text: str) -> str:
    """Khoá so khớp không dấu, chữ thường, một khoảng trắng."""
    return strip_acc(norm_vi(text))


def title_case(text: str) -> str:
    return " ".join(w[:1].upper() + w[1:] for w in (text or "").split(" ") if w)


def cap_first(text: str) -> str:
    return text[:1].upper() + text[1:] if text else text


def tidy_spaces(text: str) -> str:
    """Gộp khoảng trắng và bỏ khoảng trắng trước dấu câu sau khi ghép các mảnh."""
    s = re.sub(r"\s+", " ", text or "").strip()
    return re.sub(r"\s+([,!?:;.)])", r"\1", s).replace("( ", "(")


def cjk_len(text: str) -> int:
    return len(CJK_RE.findall(text or ""))


def split_trailing_punct(text: str):
    """('核心', '!?') — dấu câu cuối được giữ riêng khi tra nguyên chuỗi."""
    clean = (text or "").strip()
    core = clean.rstrip(TRAILING_PUNCT)
    tail = "".join(PUNCT.get(c, c).strip() for c in clean[len(core):])
    return core, tail


def clean_book_source(title: str) -> str:
    """Tựa sách gốc như dịch vụ cũ vẫn làm: bỏ 《》 và các hậu tố trang web."""
    clean = re.sub(r"[《》]", "", title or "")
    clean = re.sub(r"(最新章节列表|最新章节|章节列表|全文阅读|全文免费阅读)$", "", clean.strip())
    return clean.strip()
