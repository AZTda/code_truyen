"""Pronunciation Evaluation Service (Feature #8).

Scores what platform speech-to-text actually recognized against the target
word/phrase (see services/pronunciation_scoring.py):
- letter-level alignment for single words, word-level alignment for phrases
- no score at all when nothing was heard (never a fixed placeholder score)
- curated IPA breakdowns and Vietnamese ESL tips for display only
"""

import asyncio
import logging
from typing import Dict, Any, Optional, List

from services.pronunciation_scoring import normalize_text, score_transcript

logger = logging.getLogger("truyencloud.pronunciation")

# Từ/cụm luyện phát âm và bản chép lời chỉ vài từ; cắt sớm để chuỗi khổng lồ không tốn CPU ở bước chuẩn hoá.
_MAX_INPUT_CHARS = 1_000

NO_SPEECH_MESSAGE = "Chưa nghe được giọng nói"
NO_TARGET_MESSAGE = "Chưa có từ cần luyện phát âm"

GENERIC_MISTAKES = [
    "Kiểm tra khẩu hình miệng và bật hơi rõ ở các âm vô thanh (/p/, /t/, /k/, /s/)",
    "Giữ hơi thở đều đặn và không ngắt âm đột ngột",
]
CURATED_MISTAKES = [
    "Lỗi nuốt âm đuôi phụ âm (final consonant drop) thường gặp ở người Việt",
    "Đặt trọng âm sai vị trí làm giảm khả năng nhận diện của người bản ngữ",
]

# Curated phoneme breakdowns for high-frequency literature & GRE/IELTS words
CURATED_PHONEMES: Dict[str, Dict[str, Any]] = {
    "ephemeral": {
        "ipa": "/ɪˈfem.ər.əl/",
        "stress_index": 1,
        "phonemes": [
            {"symbol": "ɪ", "label": "i (ngắn)", "difficulty": "easy"},
            {"symbol": "ˈf", "label": "f (trọng âm)", "difficulty": "easy"},
            {"symbol": "e", "label": "e", "difficulty": "easy"},
            {"symbol": "m", "label": "m", "difficulty": "easy"},
            {"symbol": "ər", "label": "ơ uốn lưỡi", "difficulty": "medium"},
            {"symbol": "əl", "label": "ơl (âm đuôi)", "difficulty": "hard"}
        ],
        "tip_vi": "Nhấn mạnh vào âm tiết thứ 2 'PHEM'. Đừng quên nâng đầu lưỡi ở âm đuôi /l/."
    },
    "serendipity": {
        "ipa": "/ˌser.ənˈdɪp.ə.ti/",
        "stress_index": 2,
        "phonemes": [
            {"symbol": "ˌs", "label": "s", "difficulty": "easy"},
            {"symbol": "er", "label": "e", "difficulty": "easy"},
            {"symbol": "ən", "label": "ơn", "difficulty": "easy"},
            {"symbol": "ˈd", "label": "d (trọng âm chính)", "difficulty": "easy"},
            {"symbol": "ɪ", "label": "i", "difficulty": "easy"},
            {"symbol": "p", "label": "p", "difficulty": "medium"},
            {"symbol": "ə", "label": "ơ", "difficulty": "easy"},
            {"symbol": "ti", "label": "ti", "difficulty": "easy"}
        ],
        "tip_vi": "Trọng âm chính rơi vào 'DIP'. Âm 'p' bật nhẹ trước khi sang 'ə.ti'."
    },
    "resilience": {
        "ipa": "/rɪˈzɪl.jəns/",
        "stress_index": 1,
        "phonemes": [
            {"symbol": "r", "label": "r", "difficulty": "easy"},
            {"symbol": "ɪ", "label": "i", "difficulty": "easy"},
            {"symbol": "ˈz", "label": "z (trọng âm)", "difficulty": "medium"},
            {"symbol": "ɪ", "label": "i", "difficulty": "easy"},
            {"symbol": "l", "label": "l", "difficulty": "easy"},
            {"symbol": "jə", "label": "jơ", "difficulty": "medium"},
            {"symbol": "ns", "label": "ns (âm đuôi gió)", "difficulty": "hard"}
        ],
        "tip_vi": "Âm /z/ cần rung dây thanh quản. Đừng bỏ quên âm gió đuôi /ns/."
    },
    "equanimity": {
        "ipa": "/ˌek.wəˈnɪm.ə.ti/",
        "stress_index": 2,
        "phonemes": [
            {"symbol": "ˌek", "label": "ek", "difficulty": "medium"},
            {"symbol": "wə", "label": "wơ", "difficulty": "easy"},
            {"symbol": "ˈn", "label": "n (trọng âm)", "difficulty": "easy"},
            {"symbol": "ɪ", "label": "i", "difficulty": "easy"},
            {"symbol": "m", "label": "m", "difficulty": "easy"},
            {"symbol": "ə", "label": "ơ", "difficulty": "easy"},
            {"symbol": "ti", "label": "ti", "difficulty": "easy"}
        ],
        "tip_vi": "Trọng âm rơi vào 'NIM'. Âm /kw/ phát tròn môi."
    }
}


class PronunciationService:
    """Scores a recognized transcript against the target word or phrase."""

    async def evaluate(
        self,
        word: str,
        target_ipa: Optional[str] = "",
        spoken_text: Optional[str] = "",
    ) -> Dict[str, Any]:
        """`spoken_text` must be the speech-to-text transcript, not the target itself."""
        clean_word = (word or "")[:_MAX_INPUT_CHARS].strip().lower()
        transcript = (spoken_text or "")[:_MAX_INPUT_CHARS].strip()
        curated = CURATED_PHONEMES.get(clean_word)
        ipa = (curated or {}).get("ipa") or target_ipa or f"/{clean_word}/"

        if not normalize_text(clean_word):
            return self._fallback(clean_word, ipa, transcript, NO_TARGET_MESSAGE)
        if not normalize_text(transcript):
            return self._fallback(clean_word, ipa, transcript, NO_SPEECH_MESSAGE)

        # Căn chỉnh là CPU thuần Python: chạy ở thread riêng để không đóng băng event loop của cả máy chủ.
        result = await asyncio.to_thread(score_transcript, clean_word, transcript)
        score = result["score"]
        if score > 95:
            accuracy_level = "perfect"
        elif score >= 75:
            accuracy_level = "good"
        else:
            accuracy_level = "needs_work"

        return {
            "word": clean_word,
            "target_ipa": ipa,
            "transcript": transcript,
            "heard": result["heard"],
            "score": score,
            "overall_score": score,
            "is_fallback": False,
            "message": "",
            "accuracy_level": accuracy_level,
            "alignment_unit": result["unit"],
            "phonemes": result["segments"],
            "reference_phonemes": [dict(ph) for ph in (curated or {}).get("phonemes", [])],
            "feedback_vi": self._feedback(clean_word, result, accuracy_level, curated),
            "common_mistakes": CURATED_MISTAKES if curated else GENERIC_MISTAKES,
        }

    @staticmethod
    def _fallback(word: str, ipa: str, transcript: str, message: str) -> Dict[str, Any]:
        # score = None (không phải điểm cố định): app cũ đọc overall_score null
        # thành 0 nhờ coerceInputValues, app mới dựa vào is_fallback.
        return {
            "word": word,
            "target_ipa": ipa,
            "transcript": transcript,
            "heard": "",
            "score": None,
            "overall_score": None,
            "is_fallback": True,
            "message": message,
            "accuracy_level": "no_speech",
            "alignment_unit": "",
            "phonemes": [],
            "reference_phonemes": [],
            "feedback_vi": message,
            "common_mistakes": [],
        }

    @staticmethod
    def _feedback(word: str, result: Dict[str, Any], level: str,
                  curated: Optional[Dict[str, Any]]) -> str:
        heard = result["heard"]
        if level == "perfect":
            return f"Tuyệt vời! Máy nhận diện đúng '{word}'."
        weak: List[str] = [seg["symbol"] for seg in result["segments"] if seg["status"] == "improve"]
        focus = f" Chú ý: {', '.join(dict.fromkeys(weak))}." if weak else ""
        tip = curated["tip_vi"] if curated else "Hãy nói chậm, rõ từng âm tiết và phụ âm cuối."
        if level == "good":
            return f"Gần đúng — máy nghe thành '{heard}'.{focus} {tip}"
        return f"Cần chú ý hơn: máy nghe thành '{heard}', chưa khớp '{word}'.{focus} {tip}"


pronunciation_service = PronunciationService()
