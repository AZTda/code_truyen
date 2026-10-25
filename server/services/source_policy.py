"""Chính sách nguồn truyện: nguồn không miễn phí đã được gỡ khỏi máy chủ.

Tấn Giang (jjwxc) có chương VIP khóa, không đọc miễn phí trọn bộ được, nên không còn tìm kiếm,
không cào chi tiết hay chương, không nhận link jjwxc và không tải ảnh bìa từ đó nữa.
"""

import re

# Mã nguồn đã gỡ: kết quả còn trong cache với các mã này bị lọc khi trả về
REMOVED_SOURCE_IDS = frozenset({"jjwxc"})

BLOCKED_MESSAGE = "Nguồn này đã được gỡ khỏi TruyenCloud vì không miễn phí."
_BLOCKED_HOST = re.compile(r"(?<![a-z0-9-])jjwxc\.(net|com)(?![a-z0-9-])", re.IGNORECASE)


def is_blocked_url(url: str) -> bool:
    """True nếu URL thuộc nguồn đã gỡ, kể cả máy chủ ảnh (i9-static.jjwxc.net)."""
    return bool(_BLOCKED_HOST.search(url or ""))
