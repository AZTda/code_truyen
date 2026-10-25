"""Pydantic DTOs for English Immersion, Grammar, SRS & Vocabulary Studio."""

from typing import Annotated, Optional, List, Dict, Any
from pydantic import BaseModel, Field, StringConstraints, field_validator

# Trần kích cỡ cho bộ đoạn văn một chương: đủ rộng cho chương dài nhất, nhưng không cho nhồi MB vào prompt/khoá cache
MAX_PARAGRAPHS = 3_000
MAX_PARAGRAPH_CHARS = 100_000
MAX_TOTAL_PARAGRAPH_CHARS = 500_000
MAX_GRAMMAR_TEXT_CHARS = 20_000


class WordExplainRequest(BaseModel):
    word: str = Field(min_length=1, max_length=120)
    context: Optional[str] = Field(default=None, max_length=8_000)
    book_title: Optional[str] = Field(default=None, max_length=300)


class WordsExplainBatchRequest(BaseModel):
    words: List[str] = Field(min_length=1, max_length=100)
    context: Optional[str] = Field(default=None, max_length=8_000)
    book_title: Optional[str] = Field(default=None, max_length=300)


class SentenceTranslateRequest(BaseModel):
    sentence: str = Field(min_length=1, max_length=8_000)


class AdaptChapterRequest(BaseModel):
    title: str = Field(min_length=1, max_length=300)
    paragraphs: List[str] = Field(min_length=1, max_length=500)
    level: Optional[str] = Field(default="c1", max_length=4)


class LifeScenariosRequest(BaseModel):
    word: str = Field(min_length=1, max_length=120)
    context_meaning_vi: Optional[str] = Field(default=None, max_length=500)
    part_of_speech: Optional[str] = Field(default=None, max_length=80)
    cefr_level: Optional[str] = Field(default="B2", max_length=4)


class SentenceEvaluateRequest(BaseModel):
    word: str = Field(min_length=1, max_length=120)
    sentence: str = Field(min_length=1, max_length=8_000)
    context_type: Optional[str] = Field(default="daily", max_length=80)


class ScenarioQuizRequest(BaseModel):
    words: List[str] = Field(min_length=1, max_length=100)


class FlashcardArtRequest(BaseModel):
    word: str = Field(min_length=1, max_length=120)
    ipa: Optional[str] = Field(default="", max_length=200)
    cefr_level: Optional[str] = Field(default="C1", max_length=4)
    meaning: Optional[str] = Field(default="", max_length=500)
    context: Optional[str] = Field(default="", max_length=8_000)


class ContextStoryRequest(BaseModel):
    words: List[str] = Field(min_length=1, max_length=100)
    book_title: Optional[str] = Field(default="", max_length=300)
    domain: Optional[str] = Field(default="", max_length=200)


class GrammarPatternExtractRequest(BaseModel):
    text: str = Field(max_length=MAX_GRAMMAR_TEXT_CHARS)
    novel_id: Optional[str] = Field(default="", max_length=200)
    chapter_number: Optional[int] = Field(default=0, ge=0, le=2_000_000)
    novel_title: Optional[str] = Field(default="", max_length=300)
    auto_record: Optional[bool] = True


class MatureWordItem(BaseModel):
    word: str
    meaning_vi: Optional[str] = ""
    stability: Optional[float] = 0.0


class ProgressiveChapterRequest(BaseModel):
    paragraphs: List[str]
    mature_words: List[MatureWordItem]
    target_ratio: Optional[float] = 0.5


class ComprehensionQuestionsRequest(BaseModel):
    # title nằm trong prompt, khoá cache và kết quả trả về: phải có trần như mọi trường văn bản khác
    title: str = Field(max_length=300)
    paragraphs: List[Annotated[str, StringConstraints(max_length=MAX_PARAGRAPH_CHARS)]] = Field(
        max_length=MAX_PARAGRAPHS
    )
    novel_id: Optional[str] = Field(default="", max_length=200)
    chapter_number: Optional[int] = Field(default=0, ge=0, le=2_000_000)

    @field_validator("paragraphs")
    @classmethod
    def _total_size(cls, value: List[str]) -> List[str]:
        if sum(len(p) for p in value) > MAX_TOTAL_PARAGRAPH_CHARS:
            raise ValueError(f"Tổng độ dài các đoạn tối đa {MAX_TOTAL_PARAGRAPH_CHARS} ký tự.")
        return value


class WordGraphRequest(BaseModel):
    words: Optional[List[Dict[str, Any]]] = None
    limit: Optional[int] = 60


class PronunciationEvaluateRequest(BaseModel):
    word: str = Field(max_length=200)
    target_ipa: Optional[str] = Field(default="", max_length=200)
    spoken_text: Optional[str] = Field(default="", max_length=2000)
