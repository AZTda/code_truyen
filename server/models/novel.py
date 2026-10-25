from typing import List, Optional
from pydantic import BaseModel, Field

class ChapterSummary(BaseModel):
    """Basic metadata for a chapter in the table of contents."""
    chapter_number: int
    id: str
    title: str
    title_vi: Optional[str] = ""
    url: str

class Novel(BaseModel):
    """Basic novel entity returned from search or listing."""
    id: Optional[str] = None
    title: str
    title_vi: Optional[str] = ""
    author: Optional[str] = ""
    url: str
    cover_url: Optional[str] = ""
    source_id: str
    total_chapters: int = 0

class NovelDetail(BaseModel):
    """Comprehensive novel detail including chapter list."""
    id: str
    title: str
    title_vi: Optional[str] = ""
    author: Optional[str] = ""
    source_url: str
    source_id: str
    cover_url: Optional[str] = ""
    description: Optional[str] = ""
    status: str = "ongoing"
    total_chapters: int = 0
    translated_chapters: int = 0
    overnight_enabled: bool = False
    translated_chapter_numbers: List[int] = Field(default_factory=list)
    chapters: List[ChapterSummary] = Field(default_factory=list)

class SearchResult(BaseModel):
    """Search query response."""
    query: str
    total: int
    results: List[Novel] = Field(default_factory=list)
