import json
import logging
from typing import Optional, List, Dict, Any
from services.agy_runner import AgyRunnerMixin
from services.companion_prompts import (
    ASSISTANT_ROLE,
    DEFAULT_ASSISTANT,
    build_prompt,
    resolve_role,
)

logger = logging.getLogger("truyencloud.companion_chat")

_GREETING = "Xin chào! Bạn có thắc mắc gì về diễn biến chương này không?"
_GREETING_QUESTIONS = ["Chương này có sự kiện gì quan trọng?", "Nhân vật chính đang gặp nguy hiểm gì?"]
# Thông báo thật khi AI không trả lời được — trước đây là câu "tình thế vô cùng
# phức tạp..." đóng vai câu trả lời thật, độc giả không phân biệt được.
_FALLBACK_REPLY = "Xin lỗi, trợ lý AI tạm thời chưa trả lời được câu này. Bạn thử hỏi lại sau ít phút nhé."
_ERR_NO_CLI = "Antigravity CLI chưa sẵn sàng trên máy chủ."
_ERR_NO_ANSWER = "AI không trả lời hợp lệ."


def _sse(payload: Dict[str, Any]) -> str:
    return f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"


def _clean_questions(value: Any) -> List[str]:
    if not isinstance(value, list):
        return []
    return [str(q).strip() for q in value if isinstance(q, str) and q.strip()][:3]


class CompanionChatService(AgyRunnerMixin):
    """AI Companion Chat Service: Trợ lý đọc sách thông minh và nhập vai nhân vật."""

    async def chat_with_companion(
        self,
        novel_title: str,
        chapter_title: str,
        chapter_number: Optional[int],
        user_message: str,
        context_text: Optional[str] = None,
        character_name: Optional[str] = None,
        chat_history: Optional[List[Dict[str, str]]] = None,
        model: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Tạo phản hồi hội thoại dựa trên diễn biến chương và bối cảnh truyện."""
        clean_msg = user_message.strip()
        role = resolve_role(character_name)
        if not clean_msg:
            return {
                "reply": _GREETING,
                "suggested_questions": list(_GREETING_QUESTIONS),
                "character_name": role or DEFAULT_ASSISTANT,
                "is_fallback": False,
                "error": None,
            }

        if not self._get_agy_path():
            return self._build_fallback(role, _ERR_NO_CLI)

        prompt = build_prompt(
            novel_title, chapter_title, chapter_number, clean_msg,
            context_text, character_name, chat_history, json_mode=True,
        )
        try:
            parsed = await self._run_agy_json(prompt, timeout=30.0)
            if parsed and isinstance(parsed.get("reply"), str) and parsed["reply"].strip():
                return {
                    "reply": parsed["reply"].strip(),
                    "character_name": parsed.get("character_name") or role or ASSISTANT_ROLE,
                    "suggested_questions": _clean_questions(parsed.get("suggested_questions")),
                    "is_fallback": False,
                    "error": None,
                }
        except Exception as e:
            logger.warning(f"Lỗi khi gọi Antigravity cho Companion Chat: {e}")

        return self._build_fallback(role or ASSISTANT_ROLE, _ERR_NO_ANSWER)

    def _build_fallback(self, target_role: Optional[str], error: str) -> Dict[str, Any]:
        return {
            "reply": _FALLBACK_REPLY,
            "character_name": target_role or DEFAULT_ASSISTANT,
            "suggested_questions": [],
            "is_fallback": True,
            "error": error,
        }

    async def stream_companion_chat(
        self,
        novel_title: str,
        chapter_title: str,
        chapter_number: Optional[int],
        user_message: str,
        context_text: Optional[str] = None,
        character_name: Optional[str] = None,
        chat_history: Optional[List[Dict[str, str]]] = None,
        model: Optional[str] = None,
    ):
        """Tạo luồng SSE gửi từng mảnh nội dung phản hồi theo thời gian thực."""
        clean_msg = user_message.strip()
        target_role = resolve_role(character_name) or ASSISTANT_ROLE

        if not clean_msg:
            yield _sse({"type": "chunk", "content": _GREETING})
            yield _sse({"type": "done", "character_name": target_role,
                        "suggested_questions": list(_GREETING_QUESTIONS), "is_fallback": False, "error": None})
            return

        yield _sse({"type": "start", "character_name": target_role})

        error: Optional[str] = None
        has_output = False
        if not self._get_agy_path():
            error = _ERR_NO_CLI
        else:
            prompt = build_prompt(
                novel_title, chapter_title, chapter_number, clean_msg,
                context_text, character_name, chat_history, json_mode=False,
            )
            try:
                async for chunk in self._run_agy_stream(prompt, timeout=45.0, model=model):
                    if chunk:
                        has_output = True
                        yield _sse({"type": "chunk", "content": chunk})
            except Exception as e:
                logger.warning(f"Lỗi khi stream companion chat: {e}")
            if not has_output:
                error = _ERR_NO_ANSWER

        if not has_output:
            # Một mảnh duy nhất, không giả vờ "đang gõ" từng chữ như câu trả lời thật
            yield _sse({"type": "chunk", "content": _FALLBACK_REPLY})

        # Không còn câu hỏi gợi ý cố định: luồng SSE chỉ mang văn bản trả lời.
        yield _sse({"type": "done", "character_name": target_role, "suggested_questions": [],
                    "is_fallback": not has_output, "error": error})


companion_chat_service = CompanionChatService()
