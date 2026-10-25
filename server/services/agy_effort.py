"""Mức suy luận (--effort) hợp lệ cho một mô hình Antigravity CLI.

Tên mô hình trong danh mục đã mang sẵn bậc suy luận ("gemini-3.8-flash-medium"), và CLI từ chối lệnh mà
`--effort` khác bậc đó ("--model gemini-3.8-flash-medium conflicts with --effort=low"). Cài đặt mức suy luận
riêng ("Thấp" khi mô hình là ...-medium) làm MỌI lượt dịch thất bại (503), nên bậc nằm trong tên mô hình luôn thắng.
"""

import re
from typing import Optional

_TIER_SUFFIX = re.compile(r"-(low|medium|high)$", re.IGNORECASE)
_VALID = ("low", "medium", "high")


def effort_for_model(model: Optional[str], requested: Optional[str] = None) -> str:
    """Bậc suy luận để truyền cho `--effort`: bậc trong tên mô hình nếu có, ngược lại bậc được yêu cầu (mặc định low)."""
    match = _TIER_SUFFIX.search((model or "").strip())
    if match:
        return match.group(1).lower()
    wanted = (requested or "").strip().lower()
    return wanted if wanted in _VALID else "low"
