"""Dựng phản hồi của /v1/english/generate-flashcard-art từ siêu dữ liệu AI.

Hai việc mà route trước đây làm sai:
  * Khoá cache 180 ngày thiếu đầu vào: prompt dùng `context`, poster vẽ `ipa`, nhưng khoá chỉ có
    (từ, nghĩa, cấp độ) nên giá trị của người gọi đầu tiên được phát cho mọi người sau.
  * Bản thay thế dựng sẵn khi AI hỏng không có is_fallback, nên bị cache 180 ngày và ứng dụng lưu nó
    thành thẻ thật.
"""

from typing import Any, Callable, Dict, Optional

from services.dictionary_cache import dictionary_cache

# Chỉ số phiên bản trong khoá: bỏ qua các mục cũ (có thể chứa bản dựng sẵn hay giá trị của người khác)
CACHE_KEY_KIND = "flashcard_art_v2"
CACHE_KIND = "flashcard_art"

# art_title của HAI bản thay thế dựng sẵn trong MnemonicStoryMixin.generate_visual_mnemonic (khi không có CLI
# và khi AI không trả JSON hợp lệ). Hàm đó chưa tự gắn is_fallback; khi nó làm vậy thì phần nhận diện này thừa
# nhưng vô hại. Hai mục từ vựng chọn lọc ("ephemeral", "serendipity") có art_title riêng nên không bị nhầm.
CANNED_ART_TITLE = "Luminous Arcana"

ERR_ART = "AI chưa tạo được thẻ ghi nhớ cho từ này. Vui lòng thử lại sau."


def cache_key(req: Any) -> str:
    """Khoá gồm MỌI đầu vào của prompt (từ, nghĩa, ngữ cảnh) và của poster (cấp độ, IPA)."""
    return dictionary_cache.make_key(
        CACHE_KEY_KIND, req.word, req.meaning, req.cefr_level, req.ipa, req.context
    )


def is_fallback_meta(meta: Dict[str, Any]) -> bool:
    return bool(meta.get("is_fallback")) or meta.get("art_title") == CANNED_ART_TITLE


def build_response(req: Any, meta: Dict[str, Any], render: Callable[..., str]) -> Dict[str, Any]:
    """Phản hồi cuối. Bản thay thế: is_fallback + error, không vẽ poster (image_url rỗng để ứng dụng không lưu)."""
    fallback = is_fallback_meta(meta)
    image_url = ""
    if not fallback:
        image_url = render(
            word=req.word,
            ipa=req.ipa or "",
            cefr_level=req.cefr_level or "C1",
            meaning=req.meaning or "",
            mnemonic=meta.get("mnemonic", ""),
            symbol_type=meta.get("symbol_type", "crystal"),
            palette_hex=meta.get("palette_hex", "#8CB4FF"),
        )
    result: Dict[str, Any] = {
        "word": req.word,
        "image_url": image_url,
        "mnemonic": meta.get("mnemonic", ""),
        "palette_hex": meta.get("palette_hex", "#8CB4FF"),
        "art_title": meta.get("art_title", ""),
        "collocations": meta.get("collocations", []),
        "synonyms": meta.get("synonyms", []),
        "antonyms": meta.get("antonyms", []),
        "english_definition": meta.get("english_definition", ""),
        "context_translation_vi": meta.get("context_translation_vi", ""),
        "is_fallback": fallback,
    }
    if fallback:
        result["error"] = meta.get("error") or ERR_ART
    return result


def cached_response(key: str) -> Optional[Dict[str, Any]]:
    cached = dictionary_cache.get(key)
    if not cached or cached.get("is_fallback"):
        return None
    cached["cached"] = True
    return cached


def remember(key: str, word: str, result: Dict[str, Any]) -> None:
    """Chỉ nhớ kết quả do AI sinh thật (dictionary_cache.put cũng tự bỏ qua payload is_fallback)."""
    if not result.get("is_fallback"):
        dictionary_cache.put(key, CACHE_KIND, word, result)
