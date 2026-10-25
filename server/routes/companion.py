from typing import Optional, List, Dict
from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
from services.companion_chat_service import companion_chat_service
from services.auth_token import current_user

router = APIRouter(prefix="/companion", tags=["AI Companion Chat"])

class CompanionChatRequest(BaseModel):
    novel_title: str = Field(min_length=1, max_length=300)
    chapter_title: str = Field(default="", max_length=300)
    # Chương độc giả đang đọc: mốc chống tiết lộ. Có thể bỏ trống (ứng dụng cũ).
    chapter_number: Optional[int] = Field(default=None, ge=0, le=2_000_000)
    user_message: str = Field(min_length=1, max_length=8_000)
    context_text: Optional[str] = Field(default=None, max_length=20_000)
    character_name: Optional[str] = Field(default=None, max_length=200)
    chat_history: Optional[List[Dict[str, str]]] = Field(default=None, max_length=50)
    model: Optional[str] = Field(default=None, max_length=120)

@router.post("/chat")
async def chat_with_companion(req: CompanionChatRequest, auth: dict = Depends(current_user)):
    """Trò chuyện cùng AI Companion / Nhập vai nhân vật dựa trên ngữ cảnh chương đang đọc."""
    return await companion_chat_service.chat_with_companion(
        novel_title=req.novel_title,
        chapter_title=req.chapter_title,
        chapter_number=req.chapter_number,
        user_message=req.user_message,
        context_text=req.context_text,
        character_name=req.character_name,
        chat_history=req.chat_history,
        model=req.model,
    )

@router.post("/chat/stream")
async def chat_with_companion_stream(req: CompanionChatRequest, auth: dict = Depends(current_user)):
    """Truyền trực tiếp từng token câu trả lời của AI Companion qua SSE."""
    return StreamingResponse(
        companion_chat_service.stream_companion_chat(
            novel_title=req.novel_title,
            chapter_title=req.chapter_title,
            chapter_number=req.chapter_number,
            user_message=req.user_message,
            context_text=req.context_text,
            character_name=req.character_name,
            chat_history=req.chat_history,
            model=req.model,
        ),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        }
    )
