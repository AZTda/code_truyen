from typing import List, Optional
from pydantic import BaseModel, Field

class TermItem(BaseModel):
    """Glossary term mapping."""
    source: str
    target: str

class TranslateRequest(BaseModel):
    """Request to translate a single chapter."""
    novel_id: str = Field(min_length=1, max_length=200)
    chapter_number: int = Field(ge=1, le=2_000_000)
    content: Optional[str] = Field(default=None, max_length=500_000)
    glossary: List[TermItem] = Field(default_factory=list, max_length=200)
    # force: dịch lại, bỏ qua cả bản đã lưu lẫn translation_cache
    force: bool = False
    # prefetch: ứng dụng dịch trước chương KẾ TIẾP — chạy ở làn nền (BACKGROUND)
    prefetch: bool = False
    custom_prompt: Optional[str] = Field(default=None, max_length=8_000)
    recrawl: bool = False
    target_language: Optional[str] = "vi" # "vi", "en", "bilingual"
    english_level: Optional[str] = "c1" # "b1", "b2", "c1", "c2"

class TranslateResponse(BaseModel):
    """Response of a translation request."""
    novel_id: str
    chapter_number: int
    translated_content: str
    title_vi: str = ""
    cached: bool
    engine: str = "ANTIGRAVITY"
    translation_engine: str = "ANTIGRAVITY"
    # True khi văn bản KHÔNG do AI dịch (dự phòng Google) — lần mở sau máy chủ thử AI lại
    is_fallback: bool = False

class BatchTranslateRequest(BaseModel):
    """Request to batch translate a range of chapters."""
    novel_id: str = Field(min_length=1, max_length=200)
    from_chapter: int = Field(ge=1, le=2_000_000)
    to_chapter: int = Field(ge=1, le=2_000_000)
    glossary: List[TermItem] = Field(default_factory=list, max_length=200)
    overnight: bool = False
