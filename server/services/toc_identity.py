"""Nhận diện chương giữa mục lục đã lưu (chapters_index.json) và mục lục vừa cào.

`chapters_index.json` do POST /v1/scrape ghi có `id = str(số chương)` ("1", "2", ...) còn
scraper trả `id` của nguồn (tên tệp trong URL, chapterid...). So id với id thì không bao giờ
khớp, nên bản cũ của auto-updater coi MỌI chương nguồn là mới: nhân đôi mục lục và dịch trước
chương 1-2 cũ thay vì chương mới thật.

Quy tắc: một chương nguồn là ĐÃ BIẾT khi URL chuẩn hoá HOẶC id thật của nó khớp một mục đã
lưu. Id chỉ là chuỗi số đúng bằng số chương của chính mục đó là id tổng hợp, không dùng để
so khớp (nó sẽ đụng id thật của một chương khác).
"""

from dataclasses import dataclass, field
from typing import Any, Iterable, List, Set
from urllib.parse import parse_qsl, unquote, urlsplit

# Từ chừng này chương đã lưu trở lên mới có đủ cơ sở kết luận "không khớp chương nào" là lỗi định danh
MISMATCH_MIN_STORED = 5


def _get(entry: Any, name: str) -> Any:
    if isinstance(entry, dict):
        return entry.get(name)
    return getattr(entry, name, None)


def normalize_url(url: Any) -> str:
    """Dạng so sánh của URL chương: bỏ giao thức/`www.`/dấu `/` cuối/fragment, sắp xếp tham số truy vấn."""
    raw = str(url or "").strip()
    if not raw:
        return ""
    try:
        parts = urlsplit(raw)
        port = parts.port
    except ValueError:
        return raw.lower()
    host = (parts.hostname or "").lower()
    if host.startswith("www."):
        host = host[4:]
    if port and port not in (80, 443):
        host = f"{host}:{port}"
    path = unquote(parts.path or "").rstrip("/")
    query = "&".join(f"{k}={v}" for k, v in sorted(parse_qsl(parts.query, keep_blank_values=True)))
    return f"{host}{path}?{query}" if query else f"{host}{path}"


def real_id(entry: Any) -> str:
    """Id thật của chương, hoặc "" nếu không có / chỉ là số chương viết thành chuỗi."""
    ident = str(_get(entry, "id") or "").strip()
    if not ident:
        return ""
    number = str(_get(entry, "chapter_number") if _get(entry, "chapter_number") is not None else "").strip()
    if ident.isdigit() and number.isdigit() and int(ident) == int(number):
        return ""
    return ident


@dataclass
class TocDiff:
    new: List[Any] = field(default_factory=list)
    matched: int = 0

    def identity_mismatch(self, stored_count: int, remote_count: int) -> bool:
        """Nguồn dài ít nhất bằng bản đã lưu mà không khớp nổi một chương: đổi cách định danh, không phải chương mới.

        Thêm cả loạt vào mục lục lúc này sẽ nhân đôi nó. Nguồn chỉ liệt kê vài chương mới nhất
        (ngắn hơn bản lưu) thì không bị coi là lỗi.
        """
        return stored_count >= MISMATCH_MIN_STORED and remote_count >= stored_count and self.matched == 0


def _known(stored: Iterable[Any]) -> tuple[Set[str], Set[str]]:
    urls: Set[str] = set()
    ids: Set[str] = set()
    for entry in stored:
        url, ident = normalize_url(_get(entry, "url")), real_id(entry)
        if url:
            urls.add(url)
        if ident:
            ids.add(ident)
    return urls, ids


def diff_toc(stored: Iterable[Any], remote: Iterable[Any]) -> TocDiff:
    """Các chương nguồn chưa có trong mục lục đã lưu, giữ thứ tự nguồn, không trùng nhau."""
    known_urls, known_ids = _known(stored)
    diff = TocDiff()
    seen_urls: Set[str] = set()
    seen_ids: Set[str] = set()
    for ch in remote:
        url, ident = normalize_url(_get(ch, "url")), real_id(ch)
        if (url and url in known_urls) or (ident and ident in known_ids):
            diff.matched += 1
            continue
        # Chương không có URL không cào được nội dung nên không thể là "chương mới" dùng được
        if not url or url in seen_urls or (ident and ident in seen_ids):
            continue
        seen_urls.add(url)
        if ident:
            seen_ids.add(ident)
        diff.new.append(ch)
    return diff
