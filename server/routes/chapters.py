import os
import time
from datetime import datetime, timezone
from typing import Annotated, Optional
from fastapi import APIRouter, HTTPException, Depends, Path
from database import db
from models.chapter import ChapterContent
from services.scraper_registry import get_scraper_for_url
from services.glossary_store import glossary_store
from services.storage_service import storage_service
from services.translation_worker import translation_worker
from services.translation_validation import ENGINE_AI, ENGINE_FALLBACK, is_refusal
from services.toc_lookup import toc_lookup, TocUnavailableError, TOC_FRESH_SECONDS
from services.chapter_recap_service import chapter_recap_service, collect_prior_chapters
from services.auth_token import current_user, optional_user

router = APIRouter(prefix="/novels", tags=["Chapters"])

# Số chương hợp lệ: storage_service.get_chapter ném ValueError với số < 1 (trước đây thành HTTP 500)
ChapterNum = Annotated[int, Path(ge=1, le=2_000_000)]


def _as_int(value) -> int:
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def _known_total(novel_info: dict, index: list) -> int:
    numbers = [_as_int(c.get("chapter_number")) for c in index if isinstance(c, dict)]
    return max([len(index), _as_int(novel_info.get("total_chapters"))] + numbers)


def _index_file_is_recent(novel_id: str) -> bool:
    """chapters_index.json được ghi trong TOC_FRESH_SECONDS gần đây (auto_updater/nhập truyện)."""
    try:
        mtime = os.path.getmtime(storage_service.get_chapters_index_path(novel_id))
        return time.time() - mtime < TOC_FRESH_SECONDS
    except OSError:
        return False


async def _fetch_toc(source_url: str) -> list:
    detail = await get_scraper_for_url(source_url).fetch_novel_info(source_url)
    return detail.chapters or []

async def _scrape_chapter_fresh(
    novel_id: str,
    chapter_num: int,
    auto_translate: bool = False,
    custom_prompt: Optional[str] = None
) -> ChapterContent:
    """Tải lại (re-crawl) nội dung gốc nguyên văn từ trang web nguồn và tuỳ chọn dịch qua Antigravity."""
    # 1. Lấy thông tin tiểu thuyết (từ disk hoặc db)
    novel_info = storage_service.get_novel_info(novel_id)
    if not novel_info:
        novel_res = db.table("novels").select("*").eq("id", novel_id).single().execute()
        if not novel_res.data:
            novel_res = db.table("novels").select("*").eq("source_id", novel_id).single().execute()
        if novel_res.data:
            novel_info = novel_res.data
            storage_service.save_novel_info(novel_id, novel_info)

    if not novel_info:
        raise HTTPException(status_code=404, detail=f"Không tìm thấy tiểu thuyết ID: {novel_id}")

    source_url = novel_info.get("source_url")
    if not source_url:
        raise HTTPException(status_code=400, detail="Tiểu thuyết không có URL nguồn để cào chương.")

    # 2. Tìm URL của chương
    stored = storage_service.get_chapter(novel_id, chapter_num)
    ch_res = db.table("chapters").select("*") \
        .eq("novel_id", novel_id) \
        .eq("chapter_number", chapter_num) \
        .single().execute()

    chapter_url = None
    chapter_title = f"Chương {chapter_num}"
    if stored and stored.get("url"):
        chapter_url = stored["url"]
        chapter_title = stored.get("title", chapter_title)
    elif ch_res.data and ch_res.data.get("url"):
        chapter_url = ch_res.data["url"]
        chapter_title = ch_res.data.get("title", chapter_title)

    all_ch: list = []
    if not chapter_url:
        # Kiểm tra nhanh trong chapters_index.json trước khi cào lại toàn bộ mục lục
        all_ch = storage_service.list_chapters(novel_id)
        idx_ch = next((c for c in all_ch if c.get("chapter_number") == chapter_num), None)
        if idx_ch and idx_ch.get("url"):
            chapter_url = idx_ch["url"]
            chapter_title = idx_ch.get("title", chapter_title)

    if not chapter_url:
        not_found = HTTPException(status_code=404, detail=f"Không tìm thấy chương số {chapter_num} trong mục lục.")
        # Ứng dụng tải trước N+1..N+5: vượt cuối truyện mà mục lục vừa làm mới thì
        # trả 404 ngay, không cào lại cả mục lục nguồn cho mỗi lượt. Mục lục đã cào
        # nằm trong bộ nhớ thì toc_lookup.find trả lời thẳng (có thể còn mới hơn chỉ
        # mục trên đĩa), nên lối tắt này chỉ dành cho trường hợp chưa có nó.
        known_total = _known_total(novel_info, all_ch)
        if (known_total and chapter_num > known_total
                and not toc_lookup.refreshed_recently(novel_id)
                and _index_file_is_recent(novel_id)):
            raise not_found
        try:
            found = await toc_lookup.find(novel_id, source_url, chapter_num, _fetch_toc)
        except TocUnavailableError as e:
            raise HTTPException(status_code=503, detail=str(e))
        except Exception as e:
            raise HTTPException(status_code=502, detail=f"Lỗi cào mục lục nguồn: {e}")
        if not found:
            raise not_found
        chapter_url, chapter_title = found

    # 3. Cào nội dung gốc mới nhất từ web nguồn
    scraper = get_scraper_for_url(chapter_url or source_url)
    try:
        content = await scraper.fetch_chapter_content(chapter_url)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Lỗi cào nội dung chương: {str(e)}")

    if not content or not content.strip():
        raise HTTPException(status_code=502, detail="Nội dung cào về từ web nguồn bị rỗng.")

    # 4. Tựa đề chương tiếng Việt
    chapter_title_vi = ""
    if stored and stored.get("title_vi"):
        chapter_title_vi = stored["title_vi"]
    elif ch_res.data and ch_res.data.get("title_vi"):
        chapter_title_vi = ch_res.data["title_vi"]

    if not chapter_title_vi:
        tr = await translation_worker.translate_chapter_titles_batch([chapter_title])
        chapter_title_vi = tr[0] if tr else ""

    # 5. Tự động dịch lại qua Antigravity CLI nếu có yêu cầu ("dịch lại" => bỏ qua cache)
    translated_content = None
    translation_engine = None
    custom_prompt = (custom_prompt or "").strip() or None
    if auto_translate:
        try:
            result = await translation_worker.translate_chapter_detailed(
                content=content,
                # Duong nay chay khi vua cao xong chuong, khong co ung dung o dau
                # ben kia de gui bang thuat ngu len — lay ban da luu cua truyen.
                glossary=glossary_store.load(novel_id),
                custom_prompt=custom_prompt,
                force=True,
                # Yêu cầu theo phong cách riêng mà AI lỗi thì để trống, đừng trả bản Google
                # (ứng dụng lưu nguyên văn trả về vào máy).
                allow_fallback=not custom_prompt,
            )
            if result and result.text:
                translated_content, translation_engine = result.text, result.engine
        except Exception:
            # Ghi nhận lỗi nhưng vẫn lưu raw thành công
            translated_content = None

    chapter_record = {
        "novel_id": novel_id,
        "chapter_number": chapter_num,
        "title": chapter_title,
        "title_vi": chapter_title_vi,
        "url": chapter_url,
        "original_content": content,
        "word_count": len(content.split()),
        "updated_at": datetime.now(timezone.utc).isoformat(),
        "recrawled_at": datetime.now(timezone.utc).isoformat()
    }
    # Bản dịch theo prompt riêng chỉ trả cho người yêu cầu, không ghi đè bản dùng chung.
    if translated_content and not custom_prompt:
        chapter_record["translated_content"] = translated_content
        chapter_record["translation_engine"] = translation_engine
        chapter_record["translated_at"] = datetime.now(timezone.utc).isoformat()

    # Lưu vĩnh viễn vào thư mục truyện trên máy chủ (Dùng chung cho mọi user)
    storage_service.save_chapter(novel_id, chapter_num, chapter_record)

    # Cập nhật cache db
    db.table("chapters").upsert(chapter_record).execute()

    if translated_content and not custom_prompt and translation_engine == ENGINE_AI:
        from services.auto_glossary import auto_glossary
        auto_glossary.schedule(novel_id, chapter_num, content, translated_content)

    return ChapterContent(
        novel_id=novel_id,
        chapter_number=chapter_num,
        title=chapter_title,
        title_vi=chapter_title_vi,
        original_content=content,
        translated_content=translated_content,
        translation_engine=translation_engine,
        is_fallback=translation_engine == ENGINE_FALLBACK,
        cached=False
    )

def _clean_translated_content(content: Optional[str]) -> Optional[str]:
    if not content or is_refusal(content):
        return None
    return content


def _clean_ch_title_vi(ch_num: int, title_vi: Optional[str], orig_title: str, translated_content: Optional[str] = None) -> str:
    from services.title_extractor import clean_chapter_title_vi, extract_chapter_title_from_translation
    if translated_content:
        extracted = extract_chapter_title_from_translation(ch_num, orig_title, translated_content)
        if extracted:
            return extracted
    return clean_chapter_title_vi(ch_num, title_vi, orig_title)


async def _recrawl_needs_login(recrawl: bool = False, session: Optional[dict] = Depends(optional_user)) -> None:
    """Cào lại chương từ trang nguồn chạm web ngoài (và có thể kéo theo dịch AI) nên phải đăng nhập.

    Là dependency của riêng route HTTP: các lời gọi hàm trực tiếp từ translate.py / batch runner đã
    được bảo vệ bởi current_user ở endpoint của chúng nên không đi qua đây.
    """
    if recrawl and session is None:
        raise HTTPException(
            status_code=401,
            detail="Cần đăng nhập để cào lại chương từ trang nguồn.",
            headers={"WWW-Authenticate": "Bearer"},
        )


@router.get("/{novel_id}/chapters/{chapter_num}", response_model=ChapterContent, dependencies=[Depends(_recrawl_needs_login)])
async def get_chapter(novel_id: str, chapter_num: ChapterNum, recrawl: bool = False):
    """Lấy nội dung chương truyện. Trả về từ tệp lưu trữ trên máy chủ hoặc cào mới tự động."""
    if not recrawl:
        # 1. Kiểm tra trong thư mục lưu trữ đĩa máy chủ trước
        stored = storage_service.get_chapter(novel_id, chapter_num)
        if stored and stored.get("original_content"):
            c_title = stored.get("title", f"Chương {chapter_num}")
            c_trans = _clean_translated_content(stored.get("translated_content"))
            c_title_vi = _clean_ch_title_vi(chapter_num, stored.get("title_vi"), c_title, c_trans)
            return ChapterContent(
                novel_id=novel_id,
                chapter_number=chapter_num,
                title=c_title,
                title_vi=c_title_vi,
                original_content=stored["original_content"],
                translated_content=c_trans,
                translation_engine=stored.get("translation_engine", ENGINE_AI),
                is_fallback=bool(c_trans) and stored.get("translation_engine") == ENGINE_FALLBACK,
                cached=True
            )

        # 2. Kiểm tra trong DB cache
        ch_res = db.table("chapters").select("*") \
            .eq("novel_id", novel_id) \
            .eq("chapter_number", chapter_num) \
            .single().execute()

        if ch_res.data and ch_res.data.get("original_content"):
            data = ch_res.data
            storage_service.save_chapter(novel_id, chapter_num, data)
            c_title = data.get("title", f"Chương {chapter_num}")
            c_title_vi = _clean_ch_title_vi(chapter_num, data.get("title_vi"), c_title)
            c_trans = _clean_translated_content(data.get("translated_content"))
            return ChapterContent(
                novel_id=novel_id,
                chapter_number=chapter_num,
                title=c_title,
                title_vi=c_title_vi,
                original_content=data["original_content"],
                translated_content=c_trans,
                translation_engine=data.get("translation_engine", ENGINE_AI),
                is_fallback=bool(c_trans) and data.get("translation_engine") == ENGINE_FALLBACK,
                cached=True
            )

    return await _scrape_chapter_fresh(novel_id, chapter_num, auto_translate=False)

@router.post("/{novel_id}/chapters/{chapter_num}/recrawl", response_model=ChapterContent)
async def recrawl_chapter_endpoint(
    novel_id: str,
    chapter_num: ChapterNum,
    auto_translate: bool = True,
    custom_prompt: Optional[str] = None,
    auth: dict = Depends(current_user),
):
    """Cào lại nội dung raw nguyên bản từ web nguồn và tự động dịch lại bằng Antigravity."""
    return await _scrape_chapter_fresh(
        novel_id=novel_id,
        chapter_num=chapter_num,
        auto_translate=auto_translate,
        custom_prompt=custom_prompt
    )

@router.get("/{novel_id}/chapters/{chapter_num}/recap")
async def get_chapter_recap_endpoint(novel_id: str, chapter_num: ChapterNum, auth: dict = Depends(current_user)):
    """Lấy tóm tắt hồi tưởng diễn biến chương trước (Previously On) bằng Antigravity AI."""
    novel_info = storage_service.get_novel_info(novel_id) or {}
    novel_title = novel_info.get("title_vi") or novel_info.get("title") or "Tiểu Thuyết"

    current_stored = storage_service.get_chapter(novel_id, chapter_num) or {}
    current_title = current_stored.get("title_vi") or current_stored.get("title") or f"Chương {chapter_num}"

    # Tóm tắt từng chương N-2, N-1 (có cache theo nội dung) rồi ghép thành recap. Chương trước chưa có
    # trong kho (độc giả nhảy tới chương xa) thì cào về thay vì bịa "chương mở đầu".
    prior_chapters = await collect_prior_chapters(novel_id, chapter_num, get_chapter)

    recap = await chapter_recap_service.build_recap(
        novel_id=novel_id,
        novel_title=novel_title,
        current_title=current_title,
        prior_chapters=prior_chapters,
        chapter_number=chapter_num,
    )
    return {
        "novel_id": novel_id,
        "chapter_number": chapter_num,
        "novel_title": novel_title,
        "chapter_title": current_title,
        **recap
    }


@router.get("/{novel_id}/export.epub")
async def export_novel_epub_for_app(novel_id: str, translated_only: bool = True):
    """Đóng gói và tải về tệp `.epub` cho các chương đã dịch của `novel_id` trên ứng dụng di động."""
    import asyncio
    from fastapi import Response
    from services.epub_exporter import build_novel_epub
    res = await asyncio.to_thread(build_novel_epub, novel_id, translated_only)
    if not res:
        raise HTTPException(status_code=404, detail="Truyện chưa có chương đã dịch nào trên máy chủ để xuất EPUB.")
    epub_bytes, filename = res
    return Response(
        content=epub_bytes,
        media_type="application/epub+zip",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )
