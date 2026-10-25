import json
import re
import logging
from typing import Optional

logger = logging.getLogger("truyencloud.json_utils")

def extract_json_object(raw_text: str) -> Optional[dict]:
    r"""Trích xuất object JSON đầu tiên hợp lệ từ đầu ra của LLM.

    An toàn hơn regex \{.*\} (greedy): quét cân bằng ngoặc và bỏ qua ngoặc
    nằm trong chuỗi, nên không vỡ khi model trả kèm văn bản chứa dấu ngoặc.
    """
    if not raw_text:
        return None

    text = raw_text.strip()
    # Gỡ rào markdown nếu có
    text = re.sub(r"^```(?:json)?\s*", "", text, flags=re.IGNORECASE)
    text = re.sub(r"\s*```$", "", text)

    start = text.find("{")
    if start == -1:
        return None

    depth = 0
    in_string = False
    escaped = False
    for idx in range(start, len(text)):
        ch = text[idx]
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                candidate = text[start:idx + 1]
                try:
                    parsed = json.loads(candidate)
                    return parsed if isinstance(parsed, dict) else None
                except Exception:
                    return None
    return None


