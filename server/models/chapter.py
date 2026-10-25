from typing import Optional
from pydantic import BaseModel

class ChapterScraped(BaseModel):
    """Raw scraped chapter from web source."""
    chapter_number: int
    title: str
    original_content: str
    url: str

class ChapterContent(BaseModel):
    """Chapter payload returned to reader."""
    novel_id: str
    chapter_number: int
    title: str
    title_vi: Optional[str] = ""
    original_content: str
    translated_content: Optional[str] = None
    translation_engine: Optional[str] = None
    # Bản dịch đang là dự phòng Google (tạm) — ứng dụng nên gọi /translate để thử AI lại
    is_fallback: bool = False
    cached: bool = False
