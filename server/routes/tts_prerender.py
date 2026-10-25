"""POST /v1/tts/prerender: dựng giọng TRƯỚC khi người dùng bấm nghe, để phát liền mạch.

Idempotent: gọi lại chính là hỏi tiến độ (và xếp nốt những mảnh còn thiếu, kể cả sau khi máy chủ khởi động lại).
Người đã đăng nhập: chương đầu của lời gọi mới nhất được dựng trước các chương mở trước đó, và giọng khác của cùng chương
(người dùng vừa đổi giọng) xuống nền, để đổi giọng / mở chương mới không phải chờ dựng xong thứ không còn dùng.
Chỉ dựng văn bản chương ĐÃ LƯU ở máy chủ (không nhận văn bản tuỳ ý), nên mở cho khách như /v1/tts/stream; khách bị hạ
ưu tiên về >= 2 để không chen ngang người đã đăng nhập.
"""

import asyncio
import logging
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from services import tts_cache
from services.auth_token import optional_user
from services.tts_prerender import PRIORITY_BACKGROUND, PRIORITY_CURRENT, PRIORITY_UPCOMING, render_queue
from services.tts_prerender_plan import (
    STATE_NO_TEXT,
    build_chapter_plan,
    enqueue_chapter,
    engine_summary,
    summarize,
    supersede_other_voices,
)

logger = logging.getLogger("truyencloud.tts.prerender")

router = APIRouter()


class PrerenderRequest(BaseModel):
    novel_id: str = Field(min_length=1, max_length=200)
    chapter_number: int = Field(ge=1, le=10_000_000)
    count: int = Field(default=1, ge=1, le=5, description="Chương này và các chương liền sau (tối đa 5)")
    voice: str = Field(default="mai_phuong", max_length=80)
    lang: Optional[str] = Field(default=None, max_length=24, description="Trường văn bản: null/'vi', 'en', 'original'")
    priority: int = Field(default=PRIORITY_CURRENT, ge=1, le=PRIORITY_BACKGROUND,
                          description="1 = chương đang đọc, 2 = các chương sắp tới, 3 = nền")
    from_segment: int = Field(default=0, ge=0, le=1_000_000, description="Dựng từ mảnh này trước (nghe tiếp giữa chương)")


def _priority_for(base: int, offset: int) -> int:
    """Chương đầu theo `priority`, các chương sau >= 2, từ chương thứ tư trở đi là nền."""
    if offset == 0:
        return base
    return PRIORITY_BACKGROUND if offset >= 3 else max(base, PRIORITY_UPCOMING)


def _process(req: PrerenderRequest, cache_dir: str, guest: bool) -> List[Dict[str, Any]]:
    results = []
    base = max(req.priority, PRIORITY_UPCOMING) if guest else req.priority
    for offset in range(req.count):
        number = req.chapter_number + offset
        priority = _priority_for(base, offset)
        chapter = build_chapter_plan(req.novel_id, number, req.voice, req.lang, cache_dir)
        if not chapter.has_text:
            results.append(summarize(chapter, render_queue, 0, priority))
            continue
        start = req.from_segment if offset == 0 else 0
        if not guest:  # khách không được đẩy việc của người khác xuống
            supersede_other_voices(chapter, render_queue)
        enqueue_chapter(chapter, render_queue, priority, start, front=offset == 0 and not guest)
        results.append(summarize(chapter, render_queue, start, priority))
    return results


@router.post("/prerender")
async def prerender_tts(req: PrerenderRequest, auth: Optional[dict] = Depends(optional_user)):
    """Xếp dựng sẵn `count` chương từ `chapter_number` và trả trạng thái từng chương.

    state: ready | rendering | queued | no_text | unavailable (giọng local tắt/lỗi: app dùng giọng máy).
    """
    import routes.tts as tts_routes  # cache_dir theo module routes.tts (test chuyển hướng ở đó)

    cache_dir = getattr(tts_routes, "CACHE_DIR", tts_cache.CACHE_DIR)
    chapters = await asyncio.to_thread(_process, req, cache_dir, auth is None)
    return {
        "status": "ok",
        "engine": engine_summary(render_queue),
        "chapters": chapters,
    }


__all__ = ["router", "PrerenderRequest", "STATE_NO_TEXT"]
