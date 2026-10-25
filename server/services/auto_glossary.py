"""Tự trích tên riêng sau mỗi chương dịch AI xong để giữ tên nhất quán giữa các chương.

Bảng thuật ngữ thủ công gần như không ai điền, nên tên nhân vật/địa danh trôi
cách viết giữa các chương. Sau khi một chương được AI dịch sang tiếng Việt, một
tác vụ NỀN hỏi AI liệt kê tối đa 25 tên riêng kèm cách dịch đã dùng. Mỗi cặp chỉ
được nhận khi nguồn xuất hiện NGUYÊN VĂN trong bản gốc và bản dịch xuất hiện
nguyên văn trong bản dịch — chặn AI bịa tên. Mục auto không đè mục người dùng.
"""

import asyncio
import json
import logging
import re
from typing import Any, List, Optional, Set

from models.translate import TermItem
from services.agy_runner import TaskPriority
from services.glossary_store import (
    glossary_store,
    MAX_SOURCE_CHARS,
    MAX_TARGET_CHARS,
    MAX_TERMS_PER_NOVEL,
    MIN_SOURCE_CJK,
    MIN_TARGET_CHARS,
)
from services.lru_dict import LruDict
from services.translation_validation import cjk_count, has_excess_cjk

logger = logging.getLogger("truyencloud.auto_glossary")

MAX_TERMS_PER_CHAPTER = 25
# Bảng gần đầy (90% trần mỗi truyện) thì thôi trích thêm: mỗi lượt là một lời gọi AI
# ở làn nền mà glossary_store.save chỉ việc đẩy mục cũ ra.
_NEAR_FULL = int(MAX_TERMS_PER_NOVEL * 0.9)
_SOURCE_SAMPLE_CHARS = 8000
_TRANSLATION_SAMPLE_CHARS = 24000


def _build_prompt(source: str, translation: str) -> str:
    return (
        "Bạn là biên tập viên truyện dịch Trung -> Việt. Từ bản gốc và bản dịch dưới đây, "
        f"liệt kê tối đa {MAX_TERMS_PER_CHAPTER} TÊN RIÊNG quan trọng (nhân vật, địa danh, "
        "tông môn/môn phái, công pháp/chiêu thức, bảo vật).\n"
        "QUY TẮC:\n"
        "- \"source\": chép NGUYÊN VĂN chữ Hán như trong bản gốc.\n"
        "- \"target\": chép NGUYÊN VĂN cách dịch tiếng Việt đã dùng trong bản dịch.\n"
        "- Không đưa từ thông thường, đại từ, danh xưng chung (sư huynh, tiền bối...).\n"
        "Chỉ trả về JSON thuần, không markdown, dạng mảng các cặp:\n"
        "[{\"source\": \"林动\", \"target\": \"Lâm Động\"}]\n\n"
        f"[BẢN GỐC]\n{source[:_SOURCE_SAMPLE_CHARS]}\n\n"
        f"[BẢN DỊCH]\n{translation[:_TRANSLATION_SAMPLE_CHARS]}"
    )


def parse_terms_payload(raw_text: Optional[str]) -> Any:
    """JSON đầu tiên dùng được trong đầu ra của AI: {"terms": [...]} hoặc mảng thuần [...].

    Chấp nhận cả hai dạng, kèm lời dẫn hoặc rào markdown quanh JSON.
    """
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", (raw_text or "").strip(), flags=re.IGNORECASE)
    decoder = json.JSONDecoder()
    for match in re.finditer(r"[\[{]", text):
        try:
            value, _ = decoder.raw_decode(text[match.start():])
        except ValueError:
            continue
        if isinstance(value, (dict, list)):
            return value
    return None


def validate_terms(raw: Any, source: str, translation: str) -> List[TermItem]:
    """Giữ tối đa MAX_TERMS_PER_CHAPTER cặp có nguồn/đích xuất hiện nguyên văn trong bản gốc/bản dịch (nguồn >= 2 chữ Hán, đích >= 2 ký tự)."""
    items = raw.get("terms") if isinstance(raw, dict) else raw
    if not isinstance(items, list):
        return []
    seen: Set[str] = set()
    terms: List[TermItem] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        src = str(item.get("source") or "").strip()
        tgt = str(item.get("target") or "").strip()
        if not src or not tgt or src in seen:
            continue
        if len(src) > MAX_SOURCE_CHARS or len(tgt) > MAX_TARGET_CHARS:
            continue
        # Nguồn phải là chữ Hán thật và đủ dài, đích không được còn chữ Hán. Mục được áp bằng str.replace
        # mù lên chữ Hán của mọi chương sau: nguồn 1 chữ ("林", "的") làm hỏng cả từ ghép ("森林" -> "森Lâm"),
        # đích 1 ký tự thì chỉ gieo rác vào bản dịch.
        if cjk_count(src) < MIN_SOURCE_CJK or len(tgt) < MIN_TARGET_CHARS:
            continue
        if cjk_count(tgt) > 0 or has_excess_cjk(tgt):
            continue
        if src not in source or tgt not in translation:
            continue
        seen.add(src)
        terms.append(TermItem(source=src, target=tgt))
        if len(terms) >= MAX_TERMS_PER_CHAPTER:
            break
    return terms


class AutoGlossaryService:
    def __init__(self):
        # Khoá (truyện, chương) đã lên lịch — gộp các lần gọi trùng, chặn trên bộ nhớ.
        self._scheduled: LruDict = LruDict(5000)
        self._tasks: Set[asyncio.Task] = set()

    def schedule(self, novel_id: str, chapter_number: int, source: str, translation: str) -> bool:
        """Lên lịch trích tên riêng chạy nền. Trả True nếu vừa tạo tác vụ mới."""
        if not novel_id or not source or not translation or cjk_count(source) < 20:
            return False
        if glossary_store.count(novel_id) >= _NEAR_FULL and (int(chapter_number or 0) % 10) != 0:
            return False
        key = f"{novel_id}:{chapter_number}"
        if key in self._scheduled:
            return False
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            return False
        self._scheduled[key] = True
        task = loop.create_task(self._run(novel_id, chapter_number, source, translation))
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return True

    async def _run(self, novel_id: str, chapter_number: int, source: str, translation: str) -> None:
        try:
            added = await self.extract_and_merge(novel_id, chapter_number, source, translation)
            logger.info(f"Auto-glossary truyện {novel_id} chương {chapter_number}: nhận {added} mục.")
        except Exception as e:
            # Cho phép lần dịch lại sau lên lịch lại chương này
            self._scheduled.pop(f"{novel_id}:{chapter_number}", None)
            logger.warning(f"Auto-glossary lỗi ở truyện {novel_id} chương {chapter_number}: {e}")

    async def extract_and_merge(
        self, novel_id: str, chapter_number: int, source: str, translation: str
    ) -> int:
        from services.translation_worker import translation_worker
        raw_out: Optional[str] = await translation_worker._run_agy(
            _build_prompt(source, translation),
            timeout=90.0,
            priority=TaskPriority.BACKGROUND,
            task_key=f"autogloss:{novel_id}:{chapter_number}",
        )
        terms = validate_terms(parse_terms_payload(raw_out), source, translation)
        if terms:
            glossary_store.save(novel_id, terms, auto=True)
        return len(terms)


auto_glossary = AutoGlossaryService()
