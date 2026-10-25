import uuid
import asyncio
from typing import Optional
from pydantic import BaseModel
from fastapi import APIRouter, Depends, HTTPException
from database import db
from models.novel import NovelDetail
from services.scraper_registry import get_scraper_for_url
from services.translation_worker import translation_worker
from services.storage_service import storage_service
from services.url_guard import assert_safe_url, SCRAPE_HOST_ALLOWLIST, UnsafeUrlError
from services.auth_token import current_user

router = APIRouter(tags=["Scraping"])

class ScrapeRequest(BaseModel):
    source_url: str

@router.post("/scrape", response_model=NovelDetail)
async def scrape_novel_endpoint(req: ScrapeRequest, auth: dict = Depends(current_user)):
    """Cào thông tin toàn bộ truyện, danh sách chương từ web và tạo thư mục lưu trữ trên máy chủ."""
    return await perform_scrape(req.source_url)


async def perform_scrape(source_url: str) -> NovelDetail:
    """Phần lõi của việc cào truyện, không kèm xác thực.

    Tách riêng để các route khác (ví dụ `novels.py` tự phân giải truyện chưa có
    trong kho) gọi được mà không phải giả mạo phụ thuộc xác thực của FastAPI.
    Kiểm tra an toàn URL vẫn được áp dụng đầy đủ ở đây.
    """
    url = source_url.strip()
    if not url:
        raise HTTPException(status_code=400, detail="Vui lòng cung cấp URL truyện hợp lệ.")

    # CHAN SSRF: GenericScraper.supports() tra True cho MOI url http(s), nen
    # endpoint nay tung doc duoc ca dich vu noi bo (127.0.0.1, LAN, metadata cloud).
    try:
        assert_safe_url(url, SCRAPE_HOST_ALLOWLIST)
    except UnsafeUrlError as e:
        raise HTTPException(status_code=400, detail=f"URL nguồn không được chấp nhận: {e}")

    try:
        scraper = get_scraper_for_url(url)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    try:
        detail = await scraper.fetch_novel_info(url)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Lỗi khi cào dữ liệu từ nguồn: {str(e)}")

    from services.title_extractor import is_conversational_filler
    from services.hanviet_dict import transliterate_to_hanviet, has_cjk as _has_cjk
    from services.fast_translator import fast_translator
    from services.cover_service import cover_service

    # 4 việc độc lập (dịch tựa đề, dịch tóm tắt, dịch 30 tựa chương, tìm ảnh bìa)
    # chạy SONG SONG thay vì tuần tự — giảm đáng kể thời gian thêm truyện.
    # Dịch qua translation_worker để tận dụng từ điển tựa đề/tác giả tuyển chọn.
    async def _resolve_title_vi() -> str:
        from services.search_title_polisher import polish_modern_search_title
        try:
            t = await asyncio.wait_for(translation_worker.translate_novel_title(detail.title), timeout=2.5)
        except Exception:
            t = ""
        if not t or is_conversational_filler(t) or _has_cjk(t):
            t = detail.title_vi if (detail.title_vi and not _has_cjk(detail.title_vi)) else (transliterate_to_hanviet(detail.title) or detail.title)
        return polish_modern_search_title(detail.title, t)

    async def _resolve_description() -> str:
        if not detail.description or not _has_cjk(detail.description):
            return detail.description or ""
        try:
            return await asyncio.wait_for(
                translation_worker.translate_novel_description(detail.description[:600]),
                timeout=2.5
            )
        except Exception:
            return transliterate_to_hanviet(detail.description)

    async def _translate_first_titles() -> list:
        if not detail.chapters:
            return []
        titles = [ch.title for ch in detail.chapters[:30]]
        try:
            return await asyncio.wait_for(fast_translator.translate_batch_chapter_titles(titles), timeout=3.0)
        except Exception:
            return []

    async def _resolve_cover() -> str:
        cover = (detail.cover_url or "").strip()
        if not cover:
            cover = await cover_service.find_cover_by_title(detail.title)
        return cover or "/static/fallback_cover.jpg"

    async def _resolve_author() -> str:
        if not detail.author or not _has_cjk(detail.author):
            return detail.author or ""
        try:
            a = await asyncio.wait_for(translation_worker.translate_author_name(detail.author), timeout=2.0)
        except Exception:
            a = ""
        if not a or _has_cjk(a):
            a = transliterate_to_hanviet(detail.author) or detail.author
        return a

    title_vi, description, translated_titles, cover_url, author = await asyncio.gather(
        _resolve_title_vi(), _resolve_description(), _translate_first_titles(), _resolve_cover(), _resolve_author()
    )
    detail.title_vi = title_vi
    detail.description = description
    detail.cover_url = cover_url
    detail.author = author

    # Nếu tác phẩm chưa có chương nào (tác giả đang lưu bản thảo trên web gốc)
    if not detail.chapters:
        detail.status = "Chưa phát hành chương (Tác giả đang lưu bản thảo)"

    if detail.chapters:
        for idx, ch in enumerate(detail.chapters[:30]):
            if idx < len(translated_titles) and translated_titles[idx]:
                ch.title_vi = translated_titles[idx]

        # Đảm bảo 100% tất cả chương đều có tiêu đề tiếng Việt chuẩn, không bao giờ để rỗng
        from services.title_extractor import clean_chapter_title_vi

        def _fill_titles() -> None:
            for ch in detail.chapters:
                ch.title_vi = clean_chapter_title_vi(ch.chapter_number, ch.title_vi, ch.title)
        # Convert từ điển tốn ~0,1-0,3 ms/chương: mục lục nghìn chương chạy ở luồng phụ để không chặn event loop.
        await asyncio.to_thread(_fill_titles)

    # 1. Tìm hoặc gán novel_id (tránh đụng độ ID thuần số giữa các nguồn khác nhau)
    existing = db.table("novels").select("*").eq("source_url", detail.source_url).single().execute()
    if existing.data and existing.data.get("id"):
        novel_id = existing.data["id"]
    else:
        candidate_id = detail.id or str(uuid.uuid4())
        existing_disk = storage_service.get_novel_info(candidate_id)
        if (
            existing_disk
            and existing_disk.get("source_url")
            and existing_disk.get("source_url") != detail.source_url
            and existing_disk.get("source_id") != detail.source_id
        ):
            novel_id = f"{detail.source_id}_{candidate_id}"
        else:
            novel_id = candidate_id

    novel_record = {
        "id": novel_id,
        "source_url": detail.source_url,
        "source_id": detail.source_id,
        "title": detail.title,
        "title_vi": detail.title_vi or "",
        "author": detail.author or "",
        "cover_url": detail.cover_url or "",
        "description": detail.description or "",
        "total_chapters": detail.total_chapters,
        "status": detail.status,
    }

    # 2. Tạo thư mục truyện và lưu info.json trên máy chủ
    storage_service.ensure_novel_dir(novel_id)
    storage_service.save_novel_info(novel_id, novel_record)

    # Lưu vào database cache
    db.table("novels").upsert(novel_record).execute()

    # 3. Lưu danh mục toàn bộ chương vào chapters_index.json (siêu tốc < 10ms)
    chapters_index = [
        {
            "chapter_number": ch.chapter_number,
            "id": str(ch.chapter_number),
            "title": ch.title,
            "title_vi": ch.title_vi or "",
            "url": ch.url
        }
        for ch in detail.chapters
    ]
    # Xếp hàng Hy-MT2 cho tựa chương dễ sai trật tự từ (gắn novel_id để vá chapters_index.json khi bản tốt về)
    from services.title_patcher import overlay_toc
    overlay_toc(novel_id, chapters_index)
    storage_service.save_chapters_index(novel_id, chapters_index)

    # Lưu 30 chương đầu vào database cache đồng bộ để phục vụ đọc ngay
    from services.chapter_persistence import persist_chapters
    ch_records = [
        {
            "novel_id": novel_id,
            "chapter_number": ch.chapter_number,
            "title": ch.title,
            "title_vi": ch.title_vi or "",
            "url": ch.url
        }
        for ch in detail.chapters[:30]
    ]
    persist_chapters(novel_id, ch_records)

    detail.id = novel_id
    return detail
