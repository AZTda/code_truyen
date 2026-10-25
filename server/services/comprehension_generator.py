"""Reading Comprehension Questions Service (Feature #6).

Generates 3-5 multiple choice questions in English based on chapter text
to test and reinforce active reading comprehension for English learners.
"""

import logging
import hashlib
import random
from typing import List, Dict, Any, Optional

from services.translation_worker import translation_worker
from services.dictionary_cache import dictionary_cache

logger = logging.getLogger("truyencloud.comprehension")

# Đổi phiên bản khoá để bỏ các bộ câu hỏi cũ: bản trước cache cả bộ dự phòng
# (đáp án luôn là A) suốt 180 ngày như thể do AI sinh ra; bản comp2 thì khoá thiếu `title`, thứ nằm trong
# prompt lẫn kết quả nên tựa do người gọi đầu tiên tự đặt bị phát cho mọi người sau.
_CACHE_VERSION = "comp3"


def shuffle_options(question: Dict[str, Any]) -> Dict[str, Any]:
    """Xáo đáp án phía máy chủ và ánh xạ lại correct_index.

    Xáo TẤT ĐỊNH theo nội dung câu hỏi: cùng một câu luôn ra cùng một thứ tự,
    nên cache vẫn ổn định và hai lần tải không đổi đáp án đúng dưới tay người học.
    """
    options = list(question.get("options") or [])
    correct = question.get("correct_index", 0)
    seed_src = str(question.get("question", "")) + "\x1f" + "\x1f".join(sorted(map(str, options)))
    seed = int(hashlib.sha256(seed_src.encode("utf-8")).hexdigest()[:16], 16)
    order = list(range(len(options)))
    random.Random(seed).shuffle(order)
    return {
        **question,
        "options": [options[i] for i in order],
        "correct_index": order.index(correct),
    }


def _valid_question(q: Any) -> Optional[Dict[str, Any]]:
    if not isinstance(q, dict) or not str(q.get("question") or "").strip():
        return None
    options = q.get("options")
    if not isinstance(options, list) or len(options) < 2 or not all(isinstance(o, str) for o in options):
        return None
    correct = q.get("correct_index")
    if isinstance(correct, str) and correct.strip().isdigit():
        correct = int(correct.strip())
    if isinstance(correct, bool) or not isinstance(correct, int) or not 0 <= correct < len(options):
        return None
    return {**q, "correct_index": correct}


class ComprehensionGenerator:
    """Generates reading comprehension questions from chapter content."""

    async def generate_questions(
        self,
        title: str,
        paragraphs: List[str],
        novel_id: str = "",
        chapter_number: int = 0,
    ) -> Dict[str, Any]:
        """Generates 3-4 multiple-choice reading comprehension questions in English."""
        if not paragraphs:
            return {"chapter_title": title, "questions": [], "is_fallback": False, "error": None}

        # Khoá cache gồm MỌI đầu vào của prompt và của kết quả: nội dung và tựa chương
        content_sample = "\n".join(paragraphs[:15])
        content_hash = hashlib.md5(content_sample.encode("utf-8")).hexdigest()[:12]
        title_hash = hashlib.md5((title or "").encode("utf-8")).hexdigest()[:12]
        cache_key = f"{_CACHE_VERSION}_{novel_id}_{chapter_number}_{content_hash}_{title_hash}"

        cached = dictionary_cache.get(cache_key)
        if cached and isinstance(cached, dict):
            return cached

        # Try LLM generation via Antigravity CLI
        questions = [q for q in (_valid_question(q) for q in await self._generate_with_ai(title, paragraphs)) if q]
        if len(questions) >= 2:
            result = {
                "chapter_title": title,
                "questions": [shuffle_options(q) for q in questions],
                "is_fallback": False,
                "error": None,
            }
            dictionary_cache.put(cache_key, "comprehension", novel_id or "chapter", result)
            return result

        # Bộ câu hỏi heuristic: gắn cờ dự phòng và KHÔNG cache (dictionary_cache.put
        # cũng tự bỏ qua is_fallback) để lần sau AI còn được thử lại.
        return {
            "chapter_title": title,
            "questions": [shuffle_options(q) for q in self._generate_fallback(title, paragraphs)],
            "is_fallback": True,
            "error": "AI chưa tạo được câu hỏi cho chương này; đây là bộ câu hỏi mẫu.",
        }

    async def _generate_with_ai(self, title: str, paragraphs: List[str]) -> List[Dict[str, Any]]:
        """Invokes Gemini via Antigravity CLI to create 3-4 insightful questions."""
        sample_text = "\n".join(paragraphs[:12])
        if len(sample_text) > 2500:
            sample_text = sample_text[:2500]

        prompt = f"""You are an expert English literature tutor.
Based on the following chapter excerpt:
Title: {title}
Text:
{sample_text}

Generate 3-4 multiple-choice reading comprehension questions in English.
Each question has exactly 4 options and exactly one correct answer; place the correct
answer at a DIFFERENT position in different questions (not always the first option).
Return ONLY a valid JSON object with the following schema:
{{
  "questions": [
    {{
      "id": "q1",
      "question": "Question text in English?",
      "options": ["Option A", "Option B", "Option C", "Option D"],
      "correct_index": 2,
      "explanation": "Detailed explanation in Vietnamese and English explaining why this option is correct.",
      "focus_type": "plot"
    }}
  ]
}}
"""
        try:
            data = await translation_worker._run_agy_json(prompt, timeout=35)
            if isinstance(data, list) and len(data) >= 2:
                return data
            if isinstance(data, dict) and "questions" in data and isinstance(data["questions"], list):
                return data["questions"]
        except Exception as e:
            logger.debug(f"AI generation fallback triggered: {e}")
        return []

    def _generate_fallback(self, title: str, paragraphs: List[str]) -> List[Dict[str, Any]]:
        """Reliable heuristic-based questions generator."""
        questions = []
        full_text = " ".join(paragraphs)

        # Q1: Main theme / opening situation
        questions.append({
            "id": "q1",
            "question": f"What is the central focus or setting of chapter '{title}'?",
            "options": [
                f"The events and conflicts unfolding in chapter '{title}'",
                "A peaceful gathering without any obstacles or danger",
                "A flashback from ancient times unrelated to the protagonists",
                "A discussion concerning everyday market commerce"
            ],
            "correct_index": 0,
            "explanation": f"Toàn bộ chương '{title}' xoay quanh bối cảnh và diễn biến các sự kiện chính của nhân vật.",
            "focus_type": "plot"
        })

        # Q2: Vocabulary / Action in context
        action_words = [
            ("sword", "blade", "martial", "weaponry"),
            ("cultivate", "meditate", "energy", "spiritual growth"),
            ("valley", "mountain", "ruins", "geographic location"),
            ("battle", "clash", "strike", "combat confrontation")
        ]
        matched_pair = None
        for pair in action_words:
            if any(w in full_text.lower() for w in pair[:2]):
                matched_pair = pair
                break

        if matched_pair:
            questions.append({
                "id": "q2",
                "question": f"Which theme is strongly emphasized through the imagery of {matched_pair[0]} in the narrative?",
                "options": [
                    f"The significance of {matched_pair[3]} and protagonist progression",
                    "A desire to abandon cultivation and return to ordinary farming",
                    "A comical misunderstanding between secondary characters",
                    "A purely administrative trade agreement"
                ],
                "correct_index": 0,
                "explanation": f"Hình ảnh '{matched_pair[0]}' nhấn mạnh yếu tố '{matched_pair[3]}' xuyên suốt mạch truyện.",
                "focus_type": "vocabulary"
            })
        else:
            questions.append({
                "id": "q2",
                "question": "What tone best characterizes the narrative atmosphere in this chapter?",
                "options": [
                    "Intense, dramatic, and purposeful",
                    "Lighthearted, carefree, and playful",
                    "Gloomy, hopeless, and resigned to defeat",
                    "Boring and devoid of any movement"
                ],
                "correct_index": 0,
                "explanation": "Văn phong của tác phẩm mang sắc thái hào hùng, quyết liệt và có chủ đích rõ ràng.",
                "focus_type": "inference"
            })

        # Q3: Character mindset / Outcome
        questions.append({
            "id": "q3",
            "question": "How does the protagonist primarily approach unexpected challenges in this excerpt?",
            "options": [
                "With decisive determination and careful calculation",
                "By immediately fleeing in fear without attempting any solution",
                "By passively waiting for divine luck to intervene",
                "By surrendering all belongings to adversaries"
            ],
            "correct_index": 0,
            "explanation": "Nhân vật chính luôn thể hiện sự kiên định, bình tĩnh và tính toán chiến lược khi đối diện biến cố.",
            "focus_type": "character"
        })

        return questions


comprehension_generator = ComprehensionGenerator()
