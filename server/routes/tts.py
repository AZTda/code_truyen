import logging
import os
from typing import Annotated, Any, Dict, List, Optional
from urllib.parse import urlencode

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from services import tts_cache
from services.auth_token import current_user, optional_user
from services.rate_limit import client_key
from services.tts_http import audio_response, touch_and_duration
from services.tts_local import EngineUnavailable, local_tts
from services.tts_prerender import PRIORITY_CURRENT, JobFailed, render_queue
from services.tts_synthesis import enqueue_ahead, max_text_chars, ondemand_timeout, render_for_stream, synthesize_to_cache
from services.tts_voice_profiles import (
    catalog_voices,
    default_voices,
    public_voice_catalog,
    voices_note,
)
from services.tts_local_catalog import active_mode, default_ids, legacy_map
from services.tts_voice_service import (  # noqa: F401  (tên cũ test/app vẫn import từ routes.tts)
    KIND_DEVICE,
    PROFILE_VOICE_CONFIG,
    is_chinese,
    is_english,
    resolve_tts_plan,
    resolve_voice_params,
)
from routes.tts_prerender import router as prerender_router

logger = logging.getLogger("truyencloud.tts")

router = APIRouter(prefix="/tts", tags=["Text-to-Speech"])
router.include_router(prerender_router)

CACHE_DIR = tts_cache.CACHE_DIR


class SynthesizeRequest(BaseModel):
    text: str = Field(min_length=1, max_length=12_000)
    voice: Optional[str] = Field(default="mai_phuong", max_length=80)
    speed: Optional[float] = Field(default=1.0, ge=0.5, le=2.0)
    pitch: Optional[str] = Field(default=None, max_length=40)
    lang: Optional[str] = Field(default=None, max_length=12)


# Mỗi đoạn TTS tối đa 12.000 ký tự — kiểm tra ngay lúc parse để một đoạn khổng lồ không lọt vào handler.
TtsText = Annotated[str, Field(max_length=12_000)]


class PrefetchRequest(BaseModel):
    texts: List[TtsText] = Field(min_length=1, max_length=15)
    voice: Optional[str] = Field(default="mai_phuong", max_length=80)
    speed: Optional[float] = Field(default=1.0, ge=0.5, le=2.0)
    pitch: Optional[str] = Field(default=None, max_length=40)
    lang: Optional[str] = Field(default=None, max_length=12)


# Một khách không được giữ hết chỗ "nghe ngay" của mọi người (app thật chờ tối đa ~3 mảnh cùng lúc: mảnh đang phát + 2 tải sẵn)
_ondemand_inflight: Dict[str, int] = {}


def ondemand_per_client() -> int:
    try:
        return max(1, int(os.getenv("TTS_ONDEMAND_PER_CLIENT", "8")))
    except ValueError:
        return 8


def _unavailable(exc: EngineUnavailable) -> JSONResponse:
    return JSONResponse(
        status_code=503,
        content={"detail": "local_tts_unavailable"},
        headers={"Retry-After": str(max(1, int(exc.retry_after))), "X-Local-Tts-Reason": exc.reason},
    )


def engine_status() -> Dict[str, Any]:
    files, size = tts_cache.usage(CACHE_DIR)
    info = local_tts.status()
    return {
        "engine": "vieneu",
        "model": info["model"],
        "model_loaded": info["model_loaded"],
        "queue_length": render_queue.length(),
        "avg_rtf": render_queue.stats.rtf(),
        "cache_files": files,
        "cache_mb": round(size / 1024 ** 2, 2),
        "state": info["state"],
        "enabled": info["enabled"],
        "reason": info["reason"],
        "threads": info["threads"],
        "load_seconds": info["load_seconds"],
        "rendered_in_process": render_queue.stats.total,
        "queued_by_priority": {str(k): v for k, v in sorted(render_queue.queued_by_priority().items())},
        "ondemand_timeout_seconds": ondemand_timeout(),
        "cache_limit_mb": round(tts_cache.max_cache_bytes() / 1024 ** 2),
    }


@router.get("/voices")
async def get_available_voices():
    """Danh sách giọng đọc: giọng thật của mô hình đang chạy (`catalog`) và mười hồ sơ cũ (`voices`).

    Giữ nguyên các khoá cũ (`voices`, `base_voices`, `note`) để app đời trước vẫn chạy.
    """
    state = local_tts.status()
    female, male = default_ids()
    return {
        "status": "ok",
        "engine": {"name": "vieneu", "model": active_mode(), "state": state["state"],
                   "model_loaded": state["model_loaded"], "languages": ["vi", "en"], "device_languages": ["zh"],
                   "speed_control": "client"},
        "voices": public_voice_catalog(),
        "catalog": catalog_voices(),
        "default_voice": female,
        "default_voices": {"female": female, "male": male},
        "legacy_map": legacy_map(),
        "base_voices": default_voices(),
        "note": voices_note(),
    }


@router.get("/status")
async def tts_status():
    """Tình trạng giọng local và bộ đệm (không chứa bí mật)."""
    return engine_status()


@router.post("/prefetch")
async def prefetch_tts(req: PrefetchRequest, auth: Optional[dict] = Depends(optional_user)):
    """Tiền nạp các đoạn văn tiếp theo qua hàng đợi dựng (ưu tiên 1), không chờ.

    Mở cho khách vì app Android gọi endpoint này không kèm token; chỉ làm ấm bộ đệm TTS
    và đã có bucket giới hạn tần suất riêng ("tts_prefetch").
    """
    queued = skipped = 0
    for text in req.texts[:15]:
        clean = (text or "").strip()
        if not clean:
            continue
        plan = resolve_tts_plan(clean, req.voice or "mai_phuong", req.lang, CACHE_DIR)
        if plan.kind == KIND_DEVICE:
            skipped += 1
        elif enqueue_ahead(plan, PRIORITY_CURRENT):
            queued += 1
    return {"status": "ok", "queued": queued, "device": skipped, "requested": min(len(req.texts), 15)}


@router.get("/stream")
async def stream_tts(
    request: Request,
    text: str = Query(..., min_length=1, max_length=12_000, description="Đoạn văn bản cần phát âm"),
    voice: str = Query("mai_phuong", description="Id giọng thật (xem /v1/tts/voices), id hồ sơ cũ hoặc tên giọng"),
    speed: float = Query(1.0, ge=0.5, le=2.0, description="Nhận và BỎ QUA: app áp tốc độ ở trình phát"),
    pitch: Optional[str] = Query(None, description="Nhận và BỎ QUA"),
    lang: Optional[str] = Query(None, description="'vi', 'en' hoặc 'zh'. zh -> 422 unsupported_language (giọng máy đọc)"),
    prefetch_next: Optional[str] = Query(None, max_length=12_000, description="Đoạn văn tiếp theo để tiền nạp ngầm trong nền"),
):
    """Phát MP3 CBR 64 kbps (hỗ trợ Range, header X-Audio-Duration-Ms). Chưa có trong cache thì dựng ưu tiên 0.

    Lỗi: 422 `unsupported_language` (tiếng Trung) / `text_too_long`; 503 `local_tts_unavailable` + Retry-After khi giọng
    local đang nạp/lỗi/tắt/quá chậm. Không có engine nào khác: app dùng giọng máy của điện thoại.
    """
    clean = text.strip()
    if not clean:
        raise HTTPException(status_code=400, detail="Văn bản không được để trống")
    if len(clean) > max_text_chars():
        raise HTTPException(status_code=422, detail="text_too_long")

    plan = resolve_tts_plan(clean, voice, lang, CACHE_DIR)
    if plan.kind == KIND_DEVICE:
        raise HTTPException(status_code=422, detail="unsupported_language")

    def queue_next() -> None:
        hint = (prefetch_next or "").strip()
        if hint and len(hint) <= max_text_chars():
            enqueue_ahead(resolve_tts_plan(hint, voice, lang, CACHE_DIR), PRIORITY_CURRENT)

    who = None if tts_cache.is_cached(plan.cache_file) else client_key(request)
    if who is not None:
        if _ondemand_inflight.get(who, 0) >= ondemand_per_client():
            return _unavailable(EngineUnavailable("busy", 5))
        _ondemand_inflight[who] = _ondemand_inflight.get(who, 0) + 1
    try:
        path = await render_for_stream(plan, on_submitted=queue_next)
    except EngineUnavailable as exc:
        return _unavailable(exc)
    except JobFailed as exc:
        logger.warning("Dựng giọng lỗi cho /stream: %s", exc)
        return _unavailable(EngineUnavailable("failed", 30))
    finally:
        if who is not None:
            left = _ondemand_inflight.get(who, 1) - 1
            if left > 0:
                _ondemand_inflight[who] = left
            else:
                _ondemand_inflight.pop(who, None)

    duration_ms = touch_and_duration(path)
    return audio_response(
        path, plan.md5, duration_ms,
        range_header=request.headers.get("range"), if_range=request.headers.get("if-range"),
    )


@router.post("/synthesize")
async def synthesize_tts(req: SynthesizeRequest, request: Request, auth: dict = Depends(current_user)):
    """API tổng hợp audio và trả về URL audio để phát."""
    clean_text = req.text.strip()
    if not clean_text:
        raise HTTPException(status_code=400, detail="Văn bản không được để trống")

    voice = req.voice or "mai_phuong"
    speed = req.speed or 1.0
    cache_file = await synthesize_to_cache(CACHE_DIR, clean_text, voice, speed, req.pitch, req.lang)
    cached = bool(cache_file and tts_cache.is_cached(cache_file))

    params = {"text": clean_text, "voice": voice, "speed": str(speed)}
    if req.pitch:
        params["pitch"] = req.pitch
    if req.lang:
        params["lang"] = req.lang
    return {
        "status": "ok",
        "audio_url": "/v1/tts/stream?" + urlencode(params),
        "cached": cached,
        "profile": voice,
    }
