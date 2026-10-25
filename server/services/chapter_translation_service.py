"""Phần lưu trữ của đường dịch chương: trường lưu theo ngôn ngữ, đọc bản đã có, ghi kho chung.

Kho chung (server/data/novels + bảng `chapters`) là thứ MỌI độc giả cùng đọc, nên
chỉ bản dịch từ bản gốc đã lưu, không kèm prompt riêng của ai, mới được ghi vào.
Bản dự phòng Google được ghi với engine GOOGLE_FALLBACK = bản TẠM: lần mở sau
máy chủ thử AI lại, AI vẫn lỗi thì trả ngay bản tạm này.
"""

import json
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Dict, Optional, Tuple

from database import db
from models.translate import TranslateResponse
from services.auto_glossary import auto_glossary
from services.hanviet_dict import has_cjk
from services.lru_dict import LruDict
from services.storage_service import storage_service
from services.title_extractor import (
    extract_chapter_title_from_translation,
    parse_chinese_chapter,
)
from services.translation_validation import (
    ChapterTranslation,
    ENGINE_AI,
    ENGINE_FALLBACK,
    is_usable_translation,
)

VI_FIELD = "translated_content"
_LEVELS = ("b1", "b2", "c1", "c2")
_ENGLISH = ("en", "english", "tiếng anh")
_BILINGUAL = ("bilingual", "song ngữ", "song ngu")

# Sau một lần AI thất bại, chương đang giữ bản tạm được trả ngay trong khoảng này
# thay vì bắt độc giả chờ AI timeout lại ở mỗi lần mở.
FALLBACK_RETRY_COOLDOWN_SECONDS = 600
_ai_retry_after: LruDict = LruDict(5000)


@dataclass
class SourceText:
    content: str
    title: str = ""
    title_vi: str = ""
    url: str = ""
    # content là bản gốc tiếng Trung (không phải bản dịch cũ dùng tạm)
    is_original: bool = True
    # được phép ghi kết quả vào kho dùng chung
    shareable: bool = True


def normalize_target(target_language: Optional[str], english_level: Optional[str]) -> Tuple[str, str]:
    target = (target_language or "vi").lower().strip()
    lvl = (english_level or "c1").lower().strip()
    for prefix, name in (("en_", "en"), ("bilingual_", "bilingual")):
        if target.startswith(prefix):
            target, lvl = name, target.split("_", 1)[1]
    if lvl not in _LEVELS:
        lvl = "c1"
    if target in _ENGLISH:
        return "en", lvl
    if target in _BILINGUAL:
        return "bilingual", lvl
    return "vi", lvl


def cache_field_for(target: str, lvl: str) -> str:
    if target == "en":
        return f"translated_content_en_{lvl}"
    if target == "bilingual":
        return f"translated_content_bilingual_{lvl}"
    return VI_FIELD


def engine_field_for(field: str) -> str:
    # Tiếng Việt giữ tên trường cũ (routes/chapters.py và ứng dụng đang đọc nó)
    return "translation_engine" if field == VI_FIELD else f"{field}_engine"


def _legacy_field_for(target: str, lvl: str) -> Optional[str]:
    if lvl != "c1":
        return None
    return {"en": "translated_content_en", "bilingual": "translated_content_bilingual"}.get(target)


def read_stored(stored: Optional[Dict[str, Any]], target: str, lvl: str) -> Tuple[Optional[str], str]:
    """(bản dịch đã lưu, engine của nó) cho ngôn ngữ đích."""
    if not stored:
        return None, ENGINE_AI
    field = cache_field_for(target, lvl)
    text = stored.get(field)
    legacy = _legacy_field_for(target, lvl)
    if not text and legacy:
        text = stored.get(legacy)
    return text, stored.get(engine_field_for(field)) or ENGINE_AI


def sync_vi_from_db(novel_id: str, chapter_number: int) -> Tuple[Optional[str], str]:
    """Bản dịch tiếng Việt chỉ có trong DB: chép xuống đĩa rồi trả về."""
    row = db.table("chapters").select("*").eq("novel_id", novel_id) \
        .eq("chapter_number", chapter_number).single().execute().data
    text = row.get(VI_FIELD) if row else None
    if not is_usable_translation(text):
        return None, ENGINE_AI
    engine = row.get("translation_engine") or ENGINE_AI
    # Chỉ chép trường CÓ giá trị: save_chapter gộp đè, nên original_content rỗng từ
    # DB sẽ xoá mất bản gốc đang nằm trên đĩa.
    payload = {k: row[k] for k in ("title", "title_vi", "original_content") if row.get(k)}
    payload.update({VI_FIELD: text, "translation_engine": engine})
    storage_service.save_chapter(novel_id, chapter_number, payload)
    return text, engine


def novel_preferred_model(novel_id: str) -> Optional[str]:
    """Trả về model AI ưu tiên riêng của bộ truyện (nếu được ghim trong info.json)."""
    info = storage_service.get_novel_info(novel_id) or {}
    model = (info.get("preferred_model") or "").strip()
    return model or None


def with_chapter_heading(content: str, title: str, chapter_number: int) -> str:
    """Đảm bảo văn bản gửi cho Gemini có dòng tiêu đề chương ở đầu để Gemini dịch luôn tiêu đề chuẩn theo ngữ cảnh."""
    if not content or not title or not has_cjk(title):
        return content
    _, sub = parse_chinese_chapter(title, chapter_number)
    head = content[:260]
    if f"第{chapter_number}章" in head or (sub and sub in head):
        return content
    heading = f"第{chapter_number}章 {sub}" if sub else title.strip()
    return f"{heading}\n\n{content}"


def sync_chapter_title_to_toc(
    novel_id: str, chapter_number: int, orig_title: str, new_title_vi: str
) -> None:
    """Đồng bộ tiêu đề chương vừa được AI dịch vào chapters_index.json và title_cache."""
    if not new_title_vi:
        return
    try:
        _, orig_sub = parse_chinese_chapter(orig_title or "", chapter_number)
        if orig_sub and ":" in new_title_vi:
            sub_vi = new_title_vi.split(":", 1)[1].strip()
            if sub_vi and not has_cjk(sub_vi):
                from services.title_cache_store import title_cache
                title_cache.put_llm("chapter", orig_sub, sub_vi)
    except Exception:
        pass

    path = storage_service.get_chapters_index_path(novel_id)
    with storage_service._write_lock:
        try:
            with open(path, encoding="utf-8") as f:
                chapters = json.load(f)
        except (OSError, ValueError):
            return
        if not isinstance(chapters, list):
            return
        changed = False
        for idx, ch in enumerate(chapters):
            if not isinstance(ch, dict):
                continue
            c_num = ch.get("chapter_number", idx + 1)
            if int(c_num or 0) == int(chapter_number):
                if ch.get("title_vi") != new_title_vi:
                    ch["title_vi"] = new_title_vi
                    changed = True
                break
        if changed:
            storage_service._write_json_atomic(path, chapters)


def build_response(novel_id: str, chapter_number: int, text: str, engine: str, cached: bool) -> TranslateResponse:
    engine = engine or ENGINE_AI
    stored = storage_service.get_chapter(novel_id, chapter_number) or {}
    orig_title = stored.get("title") or ""
    extracted = extract_chapter_title_from_translation(chapter_number, orig_title, text)
    title_vi = extracted or stored.get("title_vi") or ""
    if extracted and stored.get("title_vi") != extracted:
        storage_service.save_chapter(novel_id, chapter_number, {"title_vi": extracted})
        sync_chapter_title_to_toc(novel_id, chapter_number, orig_title, extracted)
    return TranslateResponse(
        novel_id=novel_id,
        chapter_number=chapter_number,
        translated_content=text,
        title_vi=title_vi,
        cached=cached,
        engine=engine,
        translation_engine=engine,
        is_fallback=engine == ENGINE_FALLBACK,
    )


def _cooldown_key(novel_id: str, chapter_number: int, field: str) -> str:
    return f"{novel_id}:{chapter_number}:{field}"


def fallback_cooldown_active(novel_id: str, chapter_number: int, field: str) -> bool:
    until = _ai_retry_after.get(_cooldown_key(novel_id, chapter_number, field))
    return bool(until and until > time.time())


def mark_ai_failed(novel_id: str, chapter_number: int, field: str) -> None:
    _ai_retry_after[_cooldown_key(novel_id, chapter_number, field)] = time.time() + FALLBACK_RETRY_COOLDOWN_SECONDS


def persist_shared(
    novel_id: str, chapter_number: int, target: str, lvl: str,
    result: ChapterTranslation, source: SourceText,
) -> ChapterTranslation:
    """Ghi bản dịch vào kho chung và lên lịch trích tên riêng nếu là bản AI tiếng Việt.

    Trả về bản dịch đang là bản dùng chung SAU lời gọi: chính `result`, hoặc bản AI đã có sẵn trong kho
    khi `result` là bản dự phòng Google đến chậm. Độc giả (làn đọc, cho phép dự phòng) và việc nền (tải
    trước/dịch hàng loạt/auto-updater, không cho dự phòng) không gộp chung một lượt chạy, nên việc nền có
    thể ghi bản AI xong trước khi lượt của độc giả hết giờ và rơi xuống Google: không được ghi đè bản tốt
    bằng bản tạm (còn khiến mọi người bị phục vụ bản Google 10 phút). Kiểm-rồi-ghi ở đây không có `await`
    nào xen giữa nên nguyên tử trong một tiến trình.
    """
    field = cache_field_for(target, lvl)
    if result.is_fallback:
        text, engine = read_stored(storage_service.get_chapter(novel_id, chapter_number), target, lvl)
        if is_usable_translation(text) and engine != ENGINE_FALLBACK:
            return ChapterTranslation(text=text, engine=engine, from_cache=True)
    payload: Dict[str, Any] = {
        field: result.text,
        engine_field_for(field): result.engine,
        "translated_at": datetime.now(timezone.utc).isoformat(),
    }
    for key in ("title", "title_vi", "url"):
        value = getattr(source, key)
        if value:
            payload[key] = value
    extracted_title_vi = None
    if field == VI_FIELD and not result.is_fallback:
        extracted_title_vi = extract_chapter_title_from_translation(
            chapter_number, source.title, result.text
        )
        if extracted_title_vi:
            payload["title_vi"] = extracted_title_vi
    if source.is_original:
        payload["original_content"] = source.content
    legacy = _legacy_field_for(target, lvl)
    if legacy:
        payload[legacy] = result.text
    storage_service.save_chapter(novel_id, chapter_number, payload)

    if field == VI_FIELD:
        db_upd: Dict[str, Any] = {
            VI_FIELD: result.text,
            "translation_engine": result.engine,
        }
        if extracted_title_vi:
            db_upd["title_vi"] = extracted_title_vi
            sync_chapter_title_to_toc(novel_id, chapter_number, source.title, extracted_title_vi)
        db.table("chapters").update(db_upd).eq("novel_id", novel_id).eq("chapter_number", chapter_number).execute()
        if not result.is_fallback and source.is_original:
            auto_glossary.schedule(novel_id, chapter_number, source.content, result.text)
    return result
