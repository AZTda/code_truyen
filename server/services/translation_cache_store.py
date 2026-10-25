"""Bảng `translation_cache`: bản dịch AI theo hash nội dung + ngôn ngữ + prompt + thuật ngữ.

CHỈ chứa bản dịch AI đã qua kiểm tra (không từ chối, không cụt). Bản dự phòng
Google không bao giờ vào đây — nếu không, lần AI hồi phục sau cũng không thay được.
"""

import hashlib
import logging
from typing import List, Optional

from models.translate import TermItem
from services.translation_validation import validate_ai_translation

logger = logging.getLogger("truyencloud.translation_cache")

# Đổi tiền tố để bỏ qua các mục cũ: bản trước từng cache cả kết quả dự phòng
# (Google/Hán-Việt) lẫn lời từ chối như thể là bản dịch AI.
_KEY_PREFIX = "trans2_"


def compute_key(
    content: str,
    target_language: Optional[str],
    english_level: Optional[str],
    custom_prompt: Optional[str],
    glossary: Optional[List[TermItem]],
) -> str:
    h = hashlib.sha256()
    h.update(content.encode("utf-8"))
    h.update((target_language or "vi").encode("utf-8"))
    h.update((english_level or "c1").encode("utf-8"))
    if custom_prompt:
        h.update(custom_prompt.encode("utf-8"))
    if glossary:
        gloss_str = "|".join(f"{g.source}:{g.target}" for g in glossary if getattr(g, "source", None))
        h.update(gloss_str.encode("utf-8"))
    return f"{_KEY_PREFIX}{h.hexdigest()[:32]}"


def read(cache_key: str, source: str, target_language: Optional[str]) -> Optional[str]:
    from database import db
    try:
        cached = db.table("translation_cache").select("*").eq("id", cache_key).single().execute()
        value = cached.data.get("result") if cached.data else None
        if value and validate_ai_translation(source, value, target_language or "vi") is None:
            return value
    except Exception as e:
        logger.debug(f"Lỗi kiểm tra translation_cache: {e}")
    return None


def write(cache_key: str, result: str, target_language: Optional[str], english_level: Optional[str]) -> None:
    from database import db
    try:
        db.table("translation_cache").upsert({
            "id": cache_key,
            "result": result,
            "target_language": target_language or "vi",
            "english_level": english_level or "c1",
        }).execute()
    except Exception as e:
        logger.debug(f"Lỗi lưu translation_cache: {e}")
