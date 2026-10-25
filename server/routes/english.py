"""FastAPI Router for English Vocabulary, Definitions & Practice Studio."""

import asyncio
import logging
from typing import Optional
from fastapi import APIRouter, Depends

from services.translation_worker import translation_worker
from services.dictionary_cache import dictionary_cache
from services import flashcard_art_payload
from services.flashcard_art_service import flashcard_art_service
from services.auth_token import current_user
from routes.admin import verify_admin_access
from models.english_dtos import (
    WordExplainRequest,
    WordsExplainBatchRequest,
    SentenceTranslateRequest,
    AdaptChapterRequest,
    LifeScenariosRequest,
    SentenceEvaluateRequest,
    ScenarioQuizRequest,
    FlashcardArtRequest,
    ContextStoryRequest
)

logger = logging.getLogger("truyencloud.english")

router = APIRouter(prefix="/english", tags=["English Immersion & Vocabulary"])


async def _explain_word_cached(word: str, context: Optional[str], book_title: Optional[str]) -> dict:
    """Tra một từ, ưu tiên bộ nhớ đệm bền vững trước khi gọi Antigravity."""
    key = dictionary_cache.make_key("explain_word", word, context, book_title)
    cached = dictionary_cache.get(key)
    if cached:
        cached["cached"] = True
        return cached

    result = await translation_worker.explain_english_word(
        word=word, context=context, book_title=book_title
    )
    dictionary_cache.put(key, "explain_word", word, result)
    result["cached"] = False
    return result


@router.post("/explain-word")
async def explain_word(req: WordExplainRequest, auth: dict = Depends(current_user)):
    """Phân tích chuyên sâu từ vựng tiếng Anh (IPA, nghĩa ngữ cảnh, CEFR, từ nguyên)."""
    return await _explain_word_cached(req.word, req.context, req.book_title)


@router.post("/explain-words-batch")
async def explain_words_batch(req: WordsExplainBatchRequest, auth: dict = Depends(current_user)):
    """Tra nhiều từ một lượt (tối đa 20). Dùng để nạp trước từ khó của chương."""
    words = [w.strip() for w in req.words if w and w.strip()][:20]
    if not words:
        return {"results": [], "total": 0, "from_cache": 0}

    results = await asyncio.gather(
        *(_explain_word_cached(w, req.context, req.book_title) for w in words),
        return_exceptions=True,
    )

    payload, from_cache = [], 0
    for word, res in zip(words, results):
        if isinstance(res, BaseException):
            logger.warning("Lỗi tra từ '%s': %s", word, res)
            payload.append({"word": word, "is_fallback": True, "error": str(res)})
            continue
        if res.get("cached"):
            from_cache += 1
        payload.append(res)

    return {"results": payload, "total": len(payload), "from_cache": from_cache}


@router.post("/translate-sentence")
async def translate_sentence(req: SentenceTranslateRequest, auth: dict = Depends(current_user)):
    """Dịch câu văn tiếng Anh và trích xuất cấu trúc ngữ pháp học thuật."""
    sentence = req.sentence.strip()
    key = dictionary_cache.make_key("translate_sentence", sentence)
    cached = dictionary_cache.get(key)
    if cached:
        cached["cached"] = True
        return cached

    result = await translation_worker.translate_english_sentence(sentence=sentence)
    dictionary_cache.put(key, "translate_sentence", sentence[:120], result)
    result["cached"] = False
    return result


@router.post("/adapt-chapter")
async def adapt_chapter(req: AdaptChapterRequest, auth: dict = Depends(current_user)):
    """Chuyển thể TOÀN BỘ chương sang tiếng Anh (B1, B2, C1, C2) — chia lô, không cắt bớt."""
    return await translation_worker.adapt_to_english_chapter(
        title=req.title,
        paragraphs=req.paragraphs,
        level=req.level,
    )


@router.post("/life-scenarios")
async def get_life_scenarios(req: LifeScenariosRequest, auth: dict = Depends(current_user)):
    """Sinh kịch bản đời sống thực tế (Công sở, Email, Giao tiếp hàng ngày, Mẹo bản xứ)."""
    # life_scenarios_v2: bỏ qua các mục cũ, từng có cả bản dựng sẵn (không gắn is_fallback) bị giữ 180 ngày
    key = dictionary_cache.make_key(
        "life_scenarios_v2", req.word, req.context_meaning_vi, req.part_of_speech, req.cefr_level
    )
    cached = dictionary_cache.get(key)
    if cached and not cached.get("is_fallback"):
        cached["cached"] = True
        return cached

    result = await translation_worker.generate_life_scenarios(
        word=req.word,
        context_meaning_vi=req.context_meaning_vi,
        part_of_speech=req.part_of_speech,
        cefr_level=req.cefr_level,
    )
    # Bản dựng sẵn khi AI hỏng mang is_fallback=True: dictionary_cache.put bỏ qua nó, lượt sau AI được thử lại
    dictionary_cache.put(key, "life_scenarios", req.word, result)
    result["cached"] = False
    return result


@router.post("/evaluate-sentence")
async def evaluate_sentence(req: SentenceEvaluateRequest, auth: dict = Depends(current_user)):
    """Chấm điểm câu người học tự đặt. KHÔNG cache — mỗi câu là duy nhất."""
    return await translation_worker.evaluate_user_sentence(
        word=req.word,
        user_sentence=req.sentence,
        context_type=req.context_type,
    )


@router.post("/practice-quiz")
async def practice_quiz(req: ScenarioQuizRequest, auth: dict = Depends(current_user)):
    """Sinh trắc nghiệm tình huống đời sống kiểm tra phản xạ vận dụng từ."""
    return await translation_worker.generate_scenario_quiz(words=req.words)


@router.post("/generate-flashcard-art")
async def generate_flashcard_art(req: FlashcardArtRequest, auth: dict = Depends(current_user)):
    """Phân tích bản chất từ vựng và vẽ poster Flashcard Art."""
    key = flashcard_art_payload.cache_key(req)
    cached = flashcard_art_payload.cached_response(key)
    if cached:
        return cached

    meta = await translation_worker.generate_visual_mnemonic(
        word=req.word, meaning=req.meaning, context=req.context
    )
    result = flashcard_art_payload.build_response(req, meta, flashcard_art_service.get_or_render_flashcard)
    flashcard_art_payload.remember(key, req.word, result)
    result["cached"] = False
    return result


@router.post("/weave-context-story")
async def weave_context_story_endpoint(req: ContextStoryRequest, auth: dict = Depends(current_user)):
    """Dệt vi truyện song ngữ kết nối từ vựng theo cụm ngữ cảnh tiểu thuyết hoặc miền chủ đề."""
    return await translation_worker.weave_context_story(
        words=req.words,
        book_title=req.book_title,
        domain=req.domain,
    )


@router.get("/cache/stats")
async def cache_stats():
    """Thống kê bộ nhớ đệm từ điển — dùng để theo dõi hiệu quả tiết kiệm."""
    return dictionary_cache.stats()


@router.delete("/cache")
async def purge_cache(kind: Optional[str] = None, _admin: bool = Depends(verify_admin_access)):
    """Xóa bộ nhớ đệm (toàn bộ hoặc theo loại) khi đổi model dịch.

    Chỉ quản trị viên: cache dùng chung cho mọi người học, trước đây bất kỳ tài
    khoản nào đăng nhập cũng xoá được toàn bộ.
    """
    removed = dictionary_cache.purge(kind)
    return {"status": "ok", "removed": removed, "kind": kind or "all"}
