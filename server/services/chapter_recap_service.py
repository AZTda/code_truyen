"""Hồi tưởng "Previously On" dựng từ tóm tắt CỦA TỪNG CHƯƠNG, có cache.

Bản cũ ghép chương N-2 và N-1 rồi chỉ giữ 2500 ký tự CUỐI (N-2 gần như bị vứt),
không cache nên mỗi lần mở chương lại chạy AI. Giờ mỗi chương được tóm tắt một
lần, khoá theo hash nội dung (đổi bản dịch thì tóm tắt lại), rồi recap ghép từ
tóm tắt của N-2 và N-1 — không tốn thêm lượt AI nào khi cả hai đã có trong cache.
"""

import asyncio
import hashlib
import logging
from dataclasses import dataclass
from typing import Any, Awaitable, Callable, Dict, List, Optional

from services.dictionary_cache import dictionary_cache
from services.storage_service import storage_service
from services.translation_validation import ENGINE_FALLBACK, is_usable_translation

logger = logging.getLogger("truyencloud.chapter_recap")

SUMMARY_KIND = "chapter_summary"
_HEAD_CHARS = 3000
_TAIL_CHARS = 9000
# Recap dựng từ tối đa bấy nhiêu chương liền trước chương đang mở
RECAP_WINDOW = 2
# Chờ tối đa bấy lâu để cào một chương trước còn thiếu trong kho
FETCH_TIMEOUT_SECONDS = 20.0
_ERR_NO_PRIOR = "Chưa có nội dung các chương trước để tóm tắt. Vui lòng mở các chương trước hoặc thử lại sau."


@dataclass
class PriorChapter:
    number: int
    # Văn bản mô hình đọc (bản dịch nếu dùng được, không thì bản gốc)
    text: str
    # Danh tính ổn định của chương để làm khoá cache: bản gốc nếu có, để dịch lại
    # hay đổi bản tạm Google -> AI không làm mất tóm tắt đã có.
    fingerprint: str = ""


def _sample(content: str) -> str:
    """Giữ đầu + cuối chương: phần cuối quyết định "tình thế hiện tại"."""
    if len(content) <= _HEAD_CHARS + _TAIL_CHARS:
        return content
    return f"{content[:_HEAD_CHARS]}\n...\n{content[-_TAIL_CHARS:]}"


def _clean_list(value: Any, limit: int) -> List[str]:
    if not isinstance(value, list):
        return []
    return [str(v).strip() for v in value if isinstance(v, (str, int, float)) and str(v).strip()][:limit]


def _build_prompt(novel_title: str, chapter_number: int, content: str) -> str:
    return (
        f"Bạn là trợ lý đọc sách cho tiểu thuyết \"{novel_title}\".\n"
        f"Tóm tắt CHƯƠNG {chapter_number} dưới đây bằng tiếng Việt, chỉ dựa trên nội dung được cung cấp.\n"
        f"---\n{_sample(content)}\n---\n"
        "Trả về JSON thuần (không markdown):\n"
        "{\n"
        "  \"bullets\": [\"2-3 sự kiện then chốt theo thứ tự xảy ra\"],\n"
        "  \"characters\": [\"Tên nhân vật (vai trò ngắn)\"],\n"
        "  \"context\": \"1-2 câu về tình thế của nhân vật ở CUỐI chương.\"\n"
        "}"
    )


def _prior_from_chapter(number: int, data: Dict[str, Any]) -> Optional[PriorChapter]:
    """Nguyên liệu recap của một chương: bản dịch dùng được (không phải bản tạm Google), không thì bản gốc."""
    translated = data.get("translated_content")
    if not is_usable_translation(translated) or data.get("translation_engine") == ENGINE_FALLBACK:
        translated = None
    original = data.get("original_content") or ""
    content = translated or original
    return PriorChapter(number, content, fingerprint=original or content) if content else None


async def _fetch_prior(fetch: Callable[[str, int], Awaitable[Any]], novel_id: str, number: int) -> Dict[str, Any]:
    try:
        chapter = await asyncio.wait_for(fetch(novel_id, number), timeout=FETCH_TIMEOUT_SECONDS)
        return chapter.model_dump() if hasattr(chapter, "model_dump") else dict(chapter)
    except Exception as e:
        logger.info(f"Recap: không lấy được chương {number} của {novel_id}: {e!r}")
        return {}


async def collect_prior_chapters(
    novel_id: str, chapter_number: int, fetch: Callable[[str, int], Awaitable[Any]]
) -> List[PriorChapter]:
    """Các chương liền trước (tăng dần) làm nguyên liệu cho recap.

    Chương đã có trong kho thì dùng ngay. Chương còn thiếu (độc giả nhảy tới chương 50 từ mục lục hay từ
    máy khác nên máy chủ chưa lưu 48/49) được lấy bằng `fetch` (đường đọc chương: cào từ nguồn rồi lưu bản gốc);
    không lấy được thì bỏ qua chương đó. Chỉ lấy chương NHỎ HƠN chương hiện tại nên không lộ tình tiết.
    """
    numbers = list(range(max(1, chapter_number - RECAP_WINDOW), chapter_number))
    found: Dict[int, PriorChapter] = {}
    for n in numbers:
        prior = _prior_from_chapter(n, storage_service.get_chapter(novel_id, n) or {})
        if prior:
            found[n] = prior
    missing = [n for n in numbers if n not in found]
    if missing:
        fetched = await asyncio.gather(*(_fetch_prior(fetch, novel_id, n) for n in missing))
        for n, data in zip(missing, fetched):
            prior = _prior_from_chapter(n, data)
            if prior:
                found[n] = prior
    return [found[n] for n in numbers if n in found]


class ChapterRecapService:
    @staticmethod
    def _key(identity: str) -> tuple:
        digest = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:32]
        return dictionary_cache.make_key(SUMMARY_KIND, digest), digest

    async def summarize_chapter(
        self, novel_id: str, novel_title: str, chapter_number: int, content: str,
        fingerprint: str = "",
    ) -> Optional[Dict[str, Any]]:
        """Tóm tắt một chương (cache theo hash nội dung). None nếu AI không trả hợp lệ."""
        if not content or not content.strip():
            return None
        key, digest = self._key(fingerprint or content)
        cached = dictionary_cache.get(key)
        if cached and cached.get("bullets"):
            return cached

        from services.translation_worker import translation_worker
        # Làn tương tác (mặc định), KHÔNG phải làn đọc: recap được gọi đúng lúc chương
        # đang mở, đừng để nó tranh hai chỗ của làn đọc với bản dịch chương đó.
        parsed = await translation_worker._run_agy_json(
            _build_prompt(novel_title, chapter_number, content),
            timeout=45,
            task_key=f"chsum:{digest}",
        )
        bullets = _clean_list((parsed or {}).get("bullets"), 3)
        if not bullets:
            logger.warning(f"Không tóm tắt được chương {chapter_number} của truyện {novel_id}.")
            return None
        summary = {
            "chapter_number": chapter_number,
            "bullets": bullets,
            "characters": _clean_list(parsed.get("characters"), 6),
            "context": str(parsed.get("context") or "").strip(),
        }
        dictionary_cache.put(key, SUMMARY_KIND, f"{novel_id}:{chapter_number}", summary)
        return summary

    async def build_recap(
        self,
        novel_id: str,
        novel_title: str,
        current_title: str,
        prior_chapters: List[PriorChapter],
        chapter_number: Optional[int] = None,
    ) -> Dict[str, Any]:
        """prior_chapters: các chương trước theo thứ tự tăng dần (N-2, N-1).

        Câu "chương mở đầu" tĩnh CHỈ dành cho chương 1. Chương khác mà không có chương trước nào để tóm tắt
        (máy chủ chưa lưu và không cào được) thì trả danh sách rỗng kèm error, không bịa là chương mở đầu.
        """
        if not prior_chapters:
            if chapter_number == 1:
                return {
                    "bullets": [
                        f"Khởi đầu hành trình trong tác phẩm {novel_title}.",
                        f"Mở ra bối cảnh mở màn tại {current_title}.",
                    ],
                    "characters": [],
                    "context": f"Chương mở đầu của câu chuyện {novel_title}.",
                    "chapters_covered": [],
                    # Không có chương trước để tóm tắt: nội dung tĩnh, không phải do AI
                    "is_fallback": True,
                    "error": None,
                }
            return {
                "bullets": [],
                "characters": [],
                "context": "",
                "chapters_covered": [],
                "is_fallback": True,
                "error": _ERR_NO_PRIOR,
            }

        summaries = await asyncio.gather(*(
            self.summarize_chapter(novel_id, novel_title, ch.number, ch.text, ch.fingerprint)
            for ch in prior_chapters
        ))
        got = [s for s in summaries if s]
        if not got:
            return {
                "bullets": [],
                "characters": [],
                "context": "",
                "chapters_covered": [],
                "is_fallback": True,
                "error": "AI chưa tóm tắt được các chương trước. Vui lòng thử lại sau.",
            }

        bullets: List[str] = []
        for idx, summary in enumerate(got):
            # Chương gần nhất được nhiều dòng hơn, chương cũ hơn chỉ giữ ý chính
            bullets.extend(summary["bullets"][: 3 if idx == len(got) - 1 else 2])
        characters = list(dict.fromkeys(c for s in got for c in s.get("characters", [])))[:6]
        return {
            "bullets": bullets,
            "characters": characters,
            "context": got[-1].get("context", ""),
            "chapters_covered": [s["chapter_number"] for s in got],
            "is_fallback": False,
            "error": None,
        }


chapter_recap_service = ChapterRecapService()
