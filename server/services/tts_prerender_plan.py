"""Kế hoạch dựng trước cho một chương: bản dịch đã lưu -> mảnh TTS -> tình trạng cache -> trạng thái cho app.

Văn bản chương là bản dịch DÙNG CHUNG đã lưu ở máy chủ (cùng nguồn với /v1/translate). Chương chưa có bản dịch dùng được
thì trạng thái `no_text` và KHÔNG xếp gì. Mảnh cắt bằng đúng thuật toán của app (tts_segments.py) nên khoá cache
trùng với những gì app sẽ gọi ở /v1/tts/stream. Đoạn tiếng Trung do giọng máy đọc nên được báo là `device`, không dựng.
"""

import hashlib
import math
from collections import OrderedDict
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

from services import tts_cache
from services.tts_local import DISABLED, FAILED, local_tts
from services.tts_prerender import QueueFull, RenderQueue
from services.tts_segments import build_tts_segments, split_display_paragraphs
from services.tts_voice_service import KIND_DEVICE, KIND_RENDER, KIND_SILENT, TtsPlan, resolve_tts_plan

STATE_READY, STATE_RENDERING, STATE_QUEUED = "ready", "rendering", "queued"
STATE_NO_TEXT, STATE_UNAVAILABLE = "no_text", "unavailable"
LEAD_MARGIN = 1.1
_ORIGINAL_ALIASES = frozenset({"original", "raw", "source", "goc", "gốc"})
_PLAN_CACHE_SIZE = 24


@dataclass(frozen=True)
class SegmentInfo:
    index: int
    plan: TtsPlan


@dataclass(frozen=True)
class ChapterPlan:
    novel_id: str
    chapter_number: int
    preset_name: str
    segments: Tuple[SegmentInfo, ...]
    has_text: bool

    @property
    def context(self) -> Tuple[str, int, str]:
        return (self.novel_id, self.chapter_number, self.preset_name)


def load_chapter_text(novel_id: str, chapter_number: int, lang: Optional[str]) -> Optional[str]:
    """Văn bản đọc của chương theo ngôn ngữ app hỏi, hoặc None nếu máy chủ chưa có bản dùng được."""
    from services.chapter_translation_service import normalize_target, read_stored, sync_vi_from_db
    from services.storage_service import storage_service
    from services.translation_validation import is_usable_translation

    try:
        stored = storage_service.get_chapter(novel_id, chapter_number)
    except ValueError:
        return None
    code = (lang or "").strip().lower()
    if code in _ORIGINAL_ALIASES:
        text = (stored or {}).get("original_content")
        return text if isinstance(text, str) and text.strip() else None

    target, level = normalize_target(lang, None)
    if target == "bilingual":
        return None  # bản song ngữ có thẻ [EN]/[VI]: app tự tách, không có một chuỗi đọc duy nhất
    text, _engine = read_stored(stored, target, level)
    if target == "vi" and not is_usable_translation(text):
        try:
            text, _engine = sync_vi_from_db(novel_id, chapter_number)
        except Exception:
            text = None
    return text if is_usable_translation(text) else None


_plans: "OrderedDict[Tuple, ChapterPlan]" = OrderedDict()


def build_chapter_plan(novel_id: str, chapter_number: int, voice: str, lang: Optional[str], cache_dir: str) -> ChapterPlan:
    text = load_chapter_text(novel_id, chapter_number, lang)
    if not text:
        return ChapterPlan(novel_id, chapter_number, "", (), False)
    cache_key = (novel_id, chapter_number, hashlib.md5(text.encode("utf-8")).hexdigest(), voice, cache_dir)
    cached = _plans.get(cache_key)
    if cached is not None:
        _plans.move_to_end(cache_key)
        return cached
    segments = []
    preset = ""
    for index, seg in enumerate(build_tts_segments(split_display_paragraphs(text))):
        plan = resolve_tts_plan(seg.text, voice, None, cache_dir)
        preset = plan.preset_name
        segments.append(SegmentInfo(index, plan))
    chapter = ChapterPlan(novel_id, chapter_number, preset, tuple(segments), True)
    _plans[cache_key] = chapter
    while len(_plans) > _PLAN_CACHE_SIZE:
        _plans.popitem(last=False)
    return chapter


def clear_plan_cache() -> None:
    _plans.clear()


def enqueue_chapter(chapter: ChapterPlan, queue: RenderQueue, priority: int, start_segment: int = 0,
                    front: bool = False) -> Tuple[int, bool]:
    """Xếp các mảnh chưa có trong cache, bắt đầu từ `start_segment` rồi vòng về đầu. (số đã xếp mới, hàng đợi đầy?).

    `front`: đây là chương người dùng vừa mở / vừa bấm nghe -> đưa lên đầu mức `priority` (xem RenderQueue.bring_to_front).
    """
    if not local_tts.is_ready():
        local_tts.request()
        return 0, False
    n = len(chapter.segments)
    start = min(max(start_segment, 0), max(n - 1, 0))
    order = list(range(start, n)) + list(range(0, start))
    queued, full, md5s = 0, False, []
    for i in order:
        info = chapter.segments[i]
        if info.plan.kind != KIND_RENDER or tts_cache.is_cached(info.plan.cache_file):
            continue
        try:
            before = queue.pending_for(info.plan.md5) is not None
            queue.submit(info.plan, priority, chapter.context)
            queued += 0 if before else 1
            md5s.append(info.plan.md5)
        except QueueFull:
            full = True
            break
    if front and md5s:
        queue.bring_to_front(md5s, priority)
    return queued, full


def supersede_other_voices(chapter: ChapterPlan, queue: RenderQueue) -> int:
    """Người dùng đổi giọng: việc đang chờ của chương này bằng giọng KHÁC xuống nền (không xoá, ai cần thì được nâng lại)."""
    if not chapter.has_text or not chapter.preset_name:
        return 0
    novel, number, preset = chapter.context

    def superseded(ctx) -> bool:
        return isinstance(ctx, tuple) and len(ctx) == 3 and ctx[0] == novel and ctx[1] == number and ctx[2] != preset

    return queue.demote(superseded)


def lead_needed_segments(durations: List[float], rtf: float, all_ready: bool) -> int:
    """Số mảnh ĐẦU (từ điểm bắt đầu nghe) phải dựng xong để nghe liền mạch tới hết chương.

    Dựng chậm hơn phát (rtf > 1) thì phần âm thanh dựng kịp trong lúc nghe chỉ là 1/rtf: cần sẵn T*(1 - 1/rtf) giây
    đệm (+10% dự phòng, +1 mảnh). rtf <= 1 hoặc đã dựng xong cả chương thì 0.
    """
    if all_ready or rtf <= 1.0 or not durations:
        return 0
    total = sum(durations)
    lead_seconds = min(total, total * (1.0 - 1.0 / rtf) * LEAD_MARGIN)
    if lead_seconds <= 0:
        return 0
    acc, count = 0.0, 0
    for d in durations:
        if acc >= lead_seconds:
            break
        acc += d
        count += 1
    return min(len(durations), count + 1)


def summarize(chapter: ChapterPlan, queue: RenderQueue, start_segment: int = 0, priority: int = 1) -> Dict[str, Any]:
    """Trạng thái một chương (khoá JSON của /v1/tts/prerender)."""
    base: Dict[str, Any] = {
        "chapter_number": chapter.chapter_number,
        "state": STATE_NO_TEXT,
        "total_segments": 0,
        "ready_segments": 0,
        "estimated_seconds_remaining": 0,
        "lead_needed_segments": 0,
        "segment_count": 0,
        "device_segments": 0,
        "device_segment_indices": [],
        "skipped_segments": 0,
        "start_segment": 0,
        "ready_prefix_segments": 0,
        "can_start": False,
    }
    if not chapter.has_text:
        return base

    n = len(chapter.segments)
    start = min(max(start_segment, 0), max(n - 1, 0))
    spc, rtf = queue.stats.seconds_per_char(), queue.stats.effective_rtf()
    render = [s for s in chapter.segments if s.plan.kind == KIND_RENDER]
    cached = {s.index for s in render if tts_cache.is_cached(s.plan.cache_file)}
    device = [s.index for s in chapter.segments if s.plan.kind == KIND_DEVICE]
    skipped = sum(1 for s in chapter.segments if s.plan.kind == KIND_SILENT)

    def seconds(info: SegmentInfo) -> float:
        if info.plan.kind != KIND_RENDER:
            return 0.0
        if info.index in cached:
            return tts_cache.read_duration_ms(info.plan.cache_file) / 1000.0
        return len(info.plan.norm_text) * spc

    remaining_audio = sum(seconds(s) for s in render if s.index not in cached)
    ordered = [chapter.segments[i] for i in range(start, n)]
    prefix = 0
    for info in ordered:
        if info.plan.kind == KIND_RENDER and info.index not in cached:
            break
        prefix += 1
    all_ready = len(render) == len(cached)
    pending_from_start = any(s.plan.kind == KIND_RENDER and s.index not in cached for s in ordered)
    lead = lead_needed_segments([seconds(s) for s in ordered], rtf, all_ready or not pending_from_start)

    if all_ready:
        state = STATE_READY
    elif local_tts.state in (DISABLED, FAILED):
        state = STATE_UNAVAILABLE
    elif queue.is_running_context(chapter.context) or queue.context_done(chapter.context) > 0:
        state = STATE_RENDERING
    else:
        state = STATE_QUEUED

    estimate = remaining_audio * rtf + (queue.backlog_seconds(priority) if not all_ready else 0.0)
    first_render = next((s.index for s in ordered if s.plan.kind == KIND_RENDER), None)
    can_start = state == STATE_READY or (
        state != STATE_UNAVAILABLE and prefix >= max(lead, 1)
        and (first_render is None or first_render in cached)
    )
    base.update({
        "state": state,
        "total_segments": len(render),
        "ready_segments": len(cached),
        "estimated_seconds_remaining": int(math.ceil(estimate)) if estimate > 0 else 0,
        "lead_needed_segments": lead,
        "segment_count": n,
        "device_segments": len(device),
        "device_segment_indices": device[:2000],
        "skipped_segments": skipped,
        "start_segment": start,
        "ready_prefix_segments": prefix,
        "can_start": bool(can_start),
        "audio_seconds_estimate": int(round(sum(seconds(s) for s in render))),
    })
    return base


def engine_summary(queue: RenderQueue) -> Dict[str, Any]:
    info = local_tts.status()
    return {
        "name": "vieneu",
        "model": info["model"],
        "state": info["state"],
        "model_loaded": info["model_loaded"],
        "queue_length": queue.length(),
        "avg_rtf": queue.stats.rtf(),
    }
