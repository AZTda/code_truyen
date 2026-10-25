import logging
from typing import Optional
from fastapi import APIRouter, HTTPException, BackgroundTasks, Depends, Header
from models.translate import TranslateRequest, TranslateResponse, BatchTranslateRequest
from services.translation_worker import translation_worker
from services.agy_runner import TaskPriority
from services.storage_service import storage_service
from services.glossary_store import glossary_store, sanitize, validate_persistable
from services.batch_translation_runner import batch_translation_runner
from services.detached_task import run_detached
from services.chapter_translation_service import (
    SourceText,
    build_response,
    cache_field_for,
    fallback_cooldown_active,
    mark_ai_failed,
    normalize_target,
    novel_preferred_model,
    persist_shared,
    read_stored,
    sync_vi_from_db,
    with_chapter_heading,
)
from services.translation_validation import ENGINE_FALLBACK, is_usable_translation
from routes.admin import verify_admin_access
from routes.chapters import get_chapter
from services.auth_token import current_user, optional_user, user_id_of
from services.job_registry import job_registry

logger = logging.getLogger("truyencloud.routes.translate")
router = APIRouter(tags=["Translation"])

_AI_BUSY_DETAIL = "AI tạm thời chưa dịch được chương này. Vui lòng thử lại sau ít phút."


async def _caller_is_admin(auth: dict, x_admin_key: Optional[str]) -> bool:
    """Chỉ quản trị viên (tài khoản is_admin hoặc khoá quản trị) được ghi bảng thuật ngữ DÙNG CHUNG của truyện.

    Bảng đó được áp lên chữ Hán của mọi chương rồi lưu thành bản dịch chung cho mọi độc giả, nên một tài
    khoản thường không được sửa nó: thuật ngữ họ gửi chỉ áp cho bản dịch của chính họ.
    """
    try:
        return bool(await verify_admin_access(auth, x_admin_key, None))
    except HTTPException:
        return False


async def _resolve_source(req: TranslateRequest, stored_ch: Optional[dict], target: str) -> SourceText:
    """Chọn văn bản nguồn. Bản gốc đã lưu LUÔN thắng `req.content`.

    Trước đây `req.content` (tới 500k ký tự, không xác thực) được dịch rồi ghi đè
    bản gốc + bản dịch dùng chung cho mọi độc giả. Giờ nội dung ứng dụng gửi chỉ
    được dùng khi máy chủ hoàn toàn không có chương, và khi đó KHÔNG ghi vào kho chung.
    """
    def _from(chapter_obj) -> SourceText:
        return SourceText(chapter_obj.original_content, chapter_obj.title, chapter_obj.title_vi or "")

    if req.recrawl:
        return _from(await get_chapter(req.novel_id, req.chapter_number, recrawl=True))

    if stored_ch and stored_ch.get("original_content"):
        return SourceText(
            stored_ch["original_content"],
            stored_ch.get("title", ""),
            stored_ch.get("title_vi", ""),
            stored_ch.get("url", ""),
        )

    if stored_ch and stored_ch.get("translated_content"):
        # Có bản dịch mà mất bản gốc: cào lại từ nguồn thay vì dịch chồng lên bản dịch cũ —
        # dịch hai chặng làm mất sắc thái và cộng dồn lỗi.
        try:
            return _from(await get_chapter(req.novel_id, req.chapter_number, recrawl=True))
        except Exception as e:
            legacy = stored_ch["translated_content"]
            if target == "vi" or not is_usable_translation(legacy):
                raise HTTPException(status_code=502, detail=f"Không lấy lại được bản gốc để dịch: {e}")
            return SourceText(legacy, stored_ch.get("title", ""), stored_ch.get("title_vi", ""),
                              stored_ch.get("url", ""), is_original=False)

    try:
        return _from(await get_chapter(req.novel_id, req.chapter_number))
    except Exception:
        if req.content and req.content.strip():
            return SourceText(req.content, shareable=False, is_original=False)
        raise


@router.post("/translate", response_model=TranslateResponse)
async def translate_chapter_endpoint(
    req: TranslateRequest,
    auth: dict = Depends(current_user),
    x_admin_key: Optional[str] = Header(None, alias="X-Admin-Key"),
):
    """Dịch nguyên vẹn 1 chương truyện bằng Google Antigravity CLI ($0 API). Hỗ trợ tiếng Việt (vi), tiếng Anh (B1, B2, C1, C2), và song ngữ.

    Bản dịch AI được lưu dùng chung cho mọi độc giả. Khi AI lỗi, chương đọc trực tiếp nhận
    bản dự phòng Google (is_fallback=true, engine=GOOGLE_FALLBACK) lưu TẠM: lần mở sau máy
    chủ thử AI lại. Yêu cầu tải trước (prefetch) hoặc prompt riêng thì AI lỗi là 503 —
    không trả bản dự phòng, vì ứng dụng lưu nguyên văn trả về vào máy.

    Thuật ngữ trong yêu cầu luôn áp cho bản dịch của người gọi. Chỉ quản trị viên mới ghi được chúng vào
    bảng dùng chung; bản dịch dùng thuật ngữ mà bảng dùng chung không có là "bản riêng": trả về cho người
    gọi nhưng không lưu thành bản mọi độc giả cùng đọc.
    """
    target, lvl = normalize_target(req.target_language, req.english_level)
    field = cache_field_for(target, lvl)
    custom_prompt = (req.custom_prompt or "").strip() or None
    priority = TaskPriority.BACKGROUND if req.prefetch else TaskPriority.READER
    request_terms = sanitize(req.glossary)

    stored_ch = storage_service.get_chapter(req.novel_id, req.chapter_number)
    existing, existing_engine = read_stored(stored_ch, target, lvl)
    if target == "vi" and not is_usable_translation(existing):
        existing, existing_engine = sync_vi_from_db(req.novel_id, req.chapter_number)
        if existing:
            stored_ch = storage_service.get_chapter(req.novel_id, req.chapter_number) or stored_ch

    usable = is_usable_translation(existing)
    provisional = usable and existing_engine == ENGINE_FALLBACK
    plain = not (req.force or req.recrawl or custom_prompt)
    if plain and usable and (not provisional or fallback_cooldown_active(req.novel_id, req.chapter_number, field)):
        return build_response(req.novel_id, req.chapter_number, existing, existing_engine, cached=True)

    # AI lỗi mà đang có bản dùng được (bản AI cũ khi dịch lại, hoặc bản tạm) thì giữ bản đó.
    # Yêu cầu có prompt riêng hay recrawl thì bản cũ không phải thứ được hỏi — không giữ.
    keep = existing if usable and not req.recrawl and not custom_prompt else None
    allow_fallback = keep is None and not req.prefetch and not custom_prompt
    source = await _resolve_source(req, stored_ch, target)
    if not source.content or not source.content.strip():
        raise HTTPException(status_code=400, detail="Không có nội dung gốc để dịch.")

    # Hai đường dịch NGẦM (tự dịch khi vừa cào xong, dịch trước chương mới) không có ứng dụng ở đầu
    # bên kia gửi bảng thuật ngữ lên nên dùng bảng đã lưu. Ghi sau khi chương chắc chắn tồn tại (truyện
    # không có thì 404 ở trên, không sinh tệp rác) và chỉ những mục thật sự có trong bản gốc.
    if request_terms and source.shareable and source.is_original and await _caller_is_admin(auth, x_admin_key):
        glossary_store.save(req.novel_id, validate_persistable(request_terms, source.content))
    # Bảng đã lưu (gồm tên riêng AI tự trích) chỉ áp cho tiếng Việt: đích là tên tiếng Việt.
    glossary = glossary_store.merged_for_request(req.novel_id, request_terms) if target == "vi" else request_terms
    personalized = glossary_store.diverges_from_store(req.novel_id, request_terms, source.content)
    shareable = source.shareable and not custom_prompt and not personalized

    async def _translate_and_store():
        input_content = (
            with_chapter_heading(source.content, source.title, req.chapter_number)
            if target == "vi" and source.is_original
            else source.content
        )
        result = await translation_worker.translate_chapter_detailed(
            content=input_content,
            glossary=glossary,
            custom_prompt=custom_prompt,
            target_language=target,
            english_level=lvl,
            priority=priority,
            force=req.force,
            allow_fallback=allow_fallback,
            max_attempts=1 if provisional else 2,
            model_override=novel_preferred_model(req.novel_id),
        )
        # Bản dịch theo prompt riêng hoặc theo thuật ngữ riêng chỉ trả cho người yêu cầu (ứng dụng tự lưu
        # cục bộ), không thay bản mọi độc giả khác cùng đọc. Lưu trong việc tách rời để client
        # ngắt kết nối giữa chừng cũng không làm mất lượt AI vừa chạy. persist_shared có thể trả về bản
        # AI đã có sẵn trong kho thay cho bản dự phòng Google đến chậm.
        if result is not None and shareable:
            result = persist_shared(req.novel_id, req.chapter_number, target, lvl, result, source)
        return result

    try:
        result = await run_detached(_translate_and_store())
    except Exception as e:
        logger.warning(f"Dịch chương {req.novel_id}/{req.chapter_number} thất bại: {e}")
        raise HTTPException(status_code=503, detail=f"Lỗi dịch Antigravity CLI: {str(e)}")

    if result is None:
        if keep is None:
            raise HTTPException(status_code=503, detail=_AI_BUSY_DETAIL)
        if provisional:
            mark_ai_failed(req.novel_id, req.chapter_number, field)
        return build_response(req.novel_id, req.chapter_number, keep, existing_engine, cached=True)

    if result.is_fallback and shareable:
        # Chỉ khi bản tạm thật sự nằm trong kho dùng chung mới có "thời gian chờ thử AI lại" để đặt
        mark_ai_failed(req.novel_id, req.chapter_number, field)
    return build_response(req.novel_id, req.chapter_number, result.text, result.engine, cached=result.from_cache)


@router.get("/novels/{novel_id}/glossary")
async def get_novel_glossary(novel_id: str, auth: Optional[dict] = Depends(optional_user)):
    """Bảng thuật ngữ máy chủ đang áp cho truyện (gồm tên riêng AI tự trích: auto=true)."""
    return {"terms": glossary_store.list_entries(novel_id)}


@router.post("/batch-translate")
async def batch_translate_endpoint(
    req: BatchTranslateRequest,
    bg_tasks: BackgroundTasks,
    auth: dict = Depends(current_user),
    x_admin_key: Optional[str] = Header(None, alias="X-Admin-Key"),
):
    """Kích hoạt dịch nền hàng loạt cho một dải chương truyện.

    Kết quả của đợt này đều được lưu thành bản dùng chung, nên thuật ngữ trong yêu cầu chỉ được dùng (và
    ghi vào bảng dùng chung) khi người gọi là quản trị viên; tài khoản thường dịch bằng bảng đã lưu.
    """
    if req.from_chapter > req.to_chapter:
        raise HTTPException(status_code=400, detail="'from_chapter' phải nhỏ hơn hoặc bằng 'to_chapter'.")
    if req.overnight:
        from services.overnight_translator import overnight_translator
        return overnight_translator.enqueue_novel(req.novel_id, req.from_chapter, req.to_chapter)
    if req.to_chapter - req.from_chapter > 100:
        raise HTTPException(status_code=400, detail="Mỗi đợt dịch tối đa 100 chương.")

    user_id = user_id_of(auth)
    base = {"novel_id": req.novel_id, "from_chapter": req.from_chapter, "to_chapter": req.to_chapter}
    reservation = batch_translation_runner.reserve(
        user_id, req.novel_id, list(range(req.from_chapter, req.to_chapter + 1))
    )
    if reservation.error:
        raise HTTPException(status_code=429, detail=reservation.error)
    if reservation.attach_job_id:
        # Gửi lại đúng dải đang chạy (mở lại hộp thoại): gắn lại đợt cũ để theo dõi tiến độ.
        return {**base, "status": "queued", "job_id": reservation.attach_job_id, "queued_chapters": 0,
                "message": "Đợt dịch ngầm cho các chương này đang chạy, đã gắn lại để theo dõi tiến độ."}

    is_admin = bool(req.glossary) and await _caller_is_admin(auth, x_admin_key)
    job = job_registry.create("batch_translation", user_id)
    batch_translation_runner.activate(user_id, req.novel_id, job.id, reservation.chapters)
    bg_tasks.add_task(
        batch_translation_runner.run, user_id, req.novel_id, reservation.chapters,
        sanitize(req.glossary) if is_admin else [], job.id, persist_terms=is_admin,
    )
    return {
        **base,
        "status": "queued",
        "job_id": job.id,
        "queued_chapters": len(reservation.chapters),
        "message": f"Đã đưa {len(reservation.chapters)} chương vào hàng đợi dịch ngầm qua Antigravity CLI.",
    }


@router.post("/overnight/{novel_id}/stop")
async def stop_overnight_translation_endpoint(
    novel_id: str,
    auth: dict = Depends(current_user),
):
    """Tạm dừng / tắt Dịch Ngầm Ban Đêm cho một bộ truyện từ ứng dụng di động."""
    from services.overnight_translator import overnight_translator
    return overnight_translator.disable_novel(novel_id)


@router.get("/overnight/status")
async def get_overnight_status_endpoint():
    """Trạng thái trực tiếp của tiến trình Dịch Ngầm Ban Đêm (Gemini Flash Medium + Qwen3-4B)."""
    from services.overnight_translator import overnight_translator
    return overnight_translator.status()
