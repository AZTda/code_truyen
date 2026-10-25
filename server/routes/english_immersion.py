"""FastAPI Router for English Immersion & Advanced Study Tools.

Features:
- Grammar Pattern Recognition (/grammar-patterns, /grammar-stats)
- Progressive Diglot Weave Reading (/progressive-chapter)
- Reading Comprehension AI Questions (/comprehension-questions)
- Word Relationship Graph 2D (/word-graph)
- Pronunciation Evaluation & Scoring (/pronunciation/evaluate)
"""

import asyncio
import logging
from typing import Optional
from fastapi import APIRouter, Depends

from services.grammar_extractor import grammar_service
from services.progressive_english import progressive_service
from services.comprehension_generator import comprehension_generator
from services.word_graph_service import word_graph_service
from services.pronunciation_service import pronunciation_service
from models.english_dtos import (
    GrammarPatternExtractRequest,
    ProgressiveChapterRequest,
    ComprehensionQuestionsRequest,
    WordGraphRequest,
    PronunciationEvaluateRequest
)
from services.auth_token import current_user, optional_user, user_id_of

logger = logging.getLogger("truyencloud.english.immersion")

router = APIRouter(prefix="/english", tags=["English Immersion & Advanced Tools"])


# ---------------------------------------------------------------------------
# Grammar Pattern Recognition
# ---------------------------------------------------------------------------

@router.post("/grammar-patterns")
async def extract_grammar_patterns(req: GrammarPatternExtractRequest, auth: dict = Depends(current_user)):
    """Trích xuất và lưu trữ các mẫu cấu trúc ngữ pháp nâng cao từ văn bản / câu văn."""
    # Quét regex ở luồng phụ để vòng lặp sự kiện không đứng hình khi văn bản dài; phần ghi SQLite
    # (tối đa MAX_DETECTIONS_PER_REQUEST mẫu) giữ ở luồng chính vì lớp bảng dùng chung không đảm bảo an toàn luồng.
    detected = await asyncio.to_thread(
        grammar_service.detect_patterns,
        req.text,
        req.novel_id or "",
        req.chapter_number or 0,
        req.novel_title or "",
    )
    if req.auto_record and detected:
        grammar_service.record_patterns(detected, user_id=user_id_of(auth))
    return {
        "detected_count": len(detected),
        "patterns": detected,
    }


@router.get("/grammar-stats")
async def get_grammar_stats(limit: Optional[int] = 15, auth: Optional[dict] = Depends(optional_user)):
    """Lấy bảng thống kê các cấu trúc ngữ pháp xuất hiện nhiều nhất kèm câu ví dụ (của người gọi)."""
    return grammar_service.get_stats(limit=limit or 15, user_id=user_id_of(auth))


# ---------------------------------------------------------------------------
# Progressive English Reading (Diglot Weave)
# ---------------------------------------------------------------------------

@router.post("/progressive-chapter")
async def progressive_chapter(req: ProgressiveChapterRequest, auth: dict = Depends(current_user)):
    """Tạo văn bản đọc hòa trộn (Diglot Weave) thay thế từ vựng đã thuộc vào tiếng Việt."""
    mature_dicts = [
        {"word": w.word, "meaning_vi": w.meaning_vi or ""}
        for w in req.mature_words
    ]
    return progressive_service.weave_chapter(
        paragraphs=req.paragraphs,
        mature_words=mature_dicts,
        # 0.0 là giá trị hợp lệ (không thay từ nào) — `or 0.5` từng biến nó thành 50%
        target_ratio=0.5 if req.target_ratio is None else req.target_ratio,
    )


# ---------------------------------------------------------------------------
# Reading Comprehension Questions
# ---------------------------------------------------------------------------

@router.post("/comprehension-questions")
async def generate_comprehension_questions(req: ComprehensionQuestionsRequest, auth: dict = Depends(current_user)):
    """Tạo bộ câu hỏi trắc nghiệm đọc hiểu (Comprehension Questions) bằng tiếng Anh."""
    return await comprehension_generator.generate_questions(
        title=req.title,
        paragraphs=req.paragraphs,
        novel_id=req.novel_id or "",
        chapter_number=req.chapter_number or 0,
    )


# ---------------------------------------------------------------------------
# Word Relationship Graph
# ---------------------------------------------------------------------------

@router.post("/word-graph")
async def generate_word_graph(req: WordGraphRequest, auth: dict = Depends(current_user)):
    """Xây dựng mạng lưới từ vựng tương quan (Word Relationship Graph) 2D."""
    return word_graph_service.build_graph(words=req.words, limit=req.limit or 60)


@router.get("/word-graph")
async def get_word_graph_demo(limit: int = 50):
    """Lấy dữ liệu mạng lưới từ vựng mẫu cho chế độ trực quan hóa."""
    return word_graph_service.build_graph(limit=limit)


# ---------------------------------------------------------------------------
# Pronunciation Practice & Evaluation
# ---------------------------------------------------------------------------

@router.post("/pronunciation/evaluate")
async def evaluate_pronunciation(req: PronunciationEvaluateRequest, auth: dict = Depends(current_user)):
    """Chấm điểm và phản hồi phân tích ngữ âm (Pronunciation Assessment)."""
    return await pronunciation_service.evaluate(
        word=req.word,
        target_ipa=req.target_ipa,
        spoken_text=req.spoken_text
    )
