"""Prompt cho trợ lý đọc sách / nhập vai nhân vật (dùng chung cho JSON và SSE)."""

from typing import Dict, List, Optional

DEFAULT_ASSISTANT = "Trợ Lý Bách Khoa"
ASSISTANT_ROLE = "Trợ Lý Đọc Sách Thông Thái"


def chapter_label(chapter_number: Optional[int]) -> str:
    return f"chương {chapter_number}" if chapter_number else "chương hiện tại"


def resolve_role(character_name: Optional[str]) -> Optional[str]:
    """Tên nhân vật đang nhập vai, hoặc None nếu là trợ lý đọc sách."""
    name = (character_name or "").strip()
    return name if name and name != DEFAULT_ASSISTANT else None


def format_history(chat_history: Optional[List[Dict[str, str]]], role_label: str) -> str:
    lines = []
    for item in (chat_history or [])[-6:]:
        speaker = "Độc giả" if item.get("role") == "user" else role_label
        content = (item.get("content") or "").strip()
        if content:
            lines.append(f"{speaker}: {content}")
    return "\n".join(lines) if lines else "Chưa có hội thoại trước đó."


def anti_spoiler_rule(chapter_number: Optional[int]) -> str:
    label = chapter_label(chapter_number)
    return (
        f"QUY TẮC CHỐNG TIẾT LỘ (BẮT BUỘC): Độc giả mới đọc tới {label}. "
        f"Chỉ trả lời dựa trên nội dung được cung cấp ở trên và những gì đã xảy ra tới {label}. "
        "TUYỆT ĐỐI không tiết lộ, gợi ý hay úp mở diễn biến của các chương sau; không dùng hiểu biết "
        "bên ngoài về tác phẩm (kể cả khi đây là truyện nổi tiếng) để nói trước tình tiết. "
        "Nếu câu hỏi cần thông tin truyện chưa tiết lộ tới chương này, hãy nói rõ là truyện chưa hé lộ điều đó."
    )


def build_prompt(
    novel_title: str,
    chapter_title: str,
    chapter_number: Optional[int],
    user_message: str,
    context_text: Optional[str],
    character_name: Optional[str],
    chat_history: Optional[List[Dict[str, str]]],
    json_mode: bool,
) -> str:
    role = resolve_role(character_name)
    label = chapter_label(chapter_number)
    if role:
        system_role = (
            f"Bạn đang NHẬP VAI nhân vật \"{role}\" trong tiểu thuyết \"{novel_title}\".\n"
            f"Hãy xưng hô và trả lời bằng giọng điệu, thần thái, tâm lý của nhân vật tại thời điểm {label}: \"{chapter_title}\".\n"
            f"Nhân vật chỉ biết những gì đã xảy ra tới lúc này. Tuyệt đối không phá vỡ nhân vật (stay in character)."
        )
    else:
        system_role = (
            f"Bạn là \"{ASSISTANT_ROLE}\" của tiểu thuyết \"{novel_title}\", "
            f"giúp độc giả hiểu nhân vật, động cơ và diễn biến đã đọc."
        )

    context = (context_text or "")[-2000:].strip() or \
        f"Đang đọc tác phẩm '{novel_title}', {label}: '{chapter_title}'."
    head = (
        f"{system_role}\n\n"
        f"BỐI CẢNH ĐANG ĐỌC ({label}: \"{chapter_title}\"):\n---\n{context}\n---\n\n"
        f"LỊCH SỬ TRÒ CHUYỆN GẦN ĐÂY:\n{format_history(chat_history, role or ASSISTANT_ROLE)}\n\n"
        f"CÂU HỎI MỚI CỦA ĐỘC GIẢ:\n\"{user_message}\"\n\n"
        f"{anti_spoiler_rule(chapter_number)}\n\n"
    )
    if not json_mode:
        return head + (
            "NHIỆM VỤ: Trả lời trực tiếp độc giả thật sâu sắc, lôi cuốn, súc tích (100-250 từ). "
            "QUY TẮC: Chỉ viết nội dung trả lời thuần tuý, không bọc JSON, không thêm lời dẫn."
        )
    return head + (
        "NHIỆM VỤ: Trả lời độc giả thật sâu sắc, cuốn hút, súc tích (100-250 từ). Sau đó gợi ý 3 câu hỏi "
        "ngắn nảy sinh TRỰC TIẾP từ câu trả lời của bạn (không hỏi về chương sau).\n"
        "Trả về kết quả chuẩn theo JSON sau:\n"
        "{\n"
        "  \"reply\": \"Nội dung câu trả lời\",\n"
        f"  \"character_name\": \"{role or ASSISTANT_ROLE}\",\n"
        "  \"suggested_questions\": [\"Câu hỏi 1\", \"Câu hỏi 2\", \"Câu hỏi 3\"]\n"
        "}\n"
        "QUY TẮC: Chỉ trả về chuỗi JSON thuần túy hợp lệ, không bọc markdown codeblock hay thêm lời dẫn."
    )
