import asyncio
import re
from typing import Optional
from fastapi import APIRouter, Depends, HTTPException, Query, Response
from database import db
from models.novel import NovelDetail, SearchResult
from services.auth_token import optional_user, user_id_of
from services.search_service import search_service
from services.scraper_registry import get_scraper_for_url
from services.storage_service import storage_service
from services.hanviet_dict import has_cjk
from services.novel_catalog import discover_novels

router = APIRouter(prefix="/novels", tags=["Novels"])

@router.get("/search", response_model=SearchResult)
async def search_novels(
    q: str = Query(..., description="Từ khóa tìm kiếm tên truyện hoặc tác giả"),
    source: Optional[str] = Query(None, description="Lọc theo nguồn (69shuba, piaotia, uukanshu)")
):
    """Tìm kiếm tiểu thuyết trên các nguồn web được hỗ trợ."""
    return await search_service.search(query=q, source=source)


@router.get("/suggest")
def suggest_novel_queries(
    q: str = Query("", description="Từ khóa đang gõ để gợi ý tức thì"),
    limit: int = Query(8, ge=1, le=15),
):
    """Gợi ý tức thì (< 5ms) từ kho nội bộ, truyện nổi tiếng và từ điển chủ đề Việt/Anh -> Trung."""
    from services.search_suggest_service import suggest_queries
    return {"query": q, "suggestions": suggest_queries(q, limit=limit)}

# Phải khai báo TRƯỚC "/{novel_id}", nếu không "discover" bị hiểu là một novel_id.
@router.get("/discover")
def discover(
    response: Response,
    sort: str = Query("recent", pattern="^(recent|popular|for_you)$", description="recent | popular | for_you"),
    limit: int = Query(300, description="Số truyện tối đa (tối đa 500)"),
    auth: Optional[dict] = Depends(optional_user),
):
    """Truyện công khai đã lưu trên máy chủ, xếp theo mới cập nhật, số người đọc hoặc gu người gọi (for_you)."""
    if sort == "for_you":  # kết quả theo từng người: bộ đệm dùng chung không được trả nó cho tài khoản khác
        response.headers.update({"Cache-Control": "private, no-store", "Vary": "Authorization, X-Auth-Token"})
    return discover_novels(sort=sort, limit=limit, user_id=user_id_of(auth) if auth else None)

@router.get("/{novel_id}", response_model=NovelDetail)
async def get_novel_detail(novel_id: str, url: Optional[str] = Query(None, description="URL nguồn gốc của truyện nếu có")):
    """Lấy thông tin chi tiết truyện và danh sách các chương từ máy chủ."""
    
    # 1. Tìm thông tin truyện từ thư mục máy chủ (info.json) hoặc DB
    novel_row = storage_service.get_novel_info(novel_id)
    if not novel_row and url:
        novel_row = storage_service.get_novel_info(url)
    if not novel_row:
        res = db.table("novels").select("*").eq("id", novel_id).single().execute()
        if not res.data:
            res = db.table("novels").select("*").eq("source_id", novel_id).single().execute()
        if not res.data and url:
            res = db.table("novels").select("*").eq("source_url", url).single().execute()
        if res.data:
            novel_row = res.data
            storage_service.save_novel_info(novel_id, novel_row)

    # 2. Tự động cào truyện nếu chưa có sẵn trong kho
    if not novel_row:
        auto_url = None
        if url and (url.startswith("http://") or url.startswith("https://")):
            auto_url = url
        elif novel_id.startswith("http://") or novel_id.startswith("https://"):
            auto_url = novel_id
        elif novel_id.startswith("69shuba_"):
            raw_bid = novel_id.replace("69shuba_", "")
            auto_url = f"https://www.69shuba.com/book/{raw_bid}.htm"
        elif novel_id.startswith("uukanshu_"):
            raw_bid = novel_id.replace("uukanshu_", "")
            auto_url = f"https://uukanshu.cc/book/{raw_bid}/"
        elif re.match(r"^\d+_\d+$", novel_id):
            cat, bid = novel_id.split("_")
            auto_url = f"https://www.piaotia.com/html/{cat}/{bid}/index.html"

        if auto_url:
            try:
                from routes.scrape import perform_scrape
                detail = await perform_scrape(auto_url)
                novel_row = storage_service.get_novel_info(detail.id) or storage_service.get_novel_info(novel_id) or {
                    "id": detail.id,
                    "title": detail.title,
                    "title_vi": detail.title_vi,
                    "author": detail.author,
                    "source_url": detail.source_url,
                    "source_id": detail.source_id,
                    "cover_url": detail.cover_url,
                    "description": detail.description,
                    "total_chapters": detail.total_chapters,
                    "status": detail.status,
                }
            except Exception:
                pass

    if not novel_row:
        raise HTTPException(
            status_code=404,
            detail=f"Không tìm thấy tiểu thuyết với ID: {novel_id}. Hãy cào truyện trước bằng endpoint /v1/scrape."
        )

    # 3. Tải danh sách chương từ thư mục chapters/ trên máy chủ hoặc DB
    target_id = novel_row.get("id") or novel_id
    chapters_data = storage_service.list_chapters(novel_id)
    if not chapters_data and target_id != novel_id:
        chapters_data = storage_service.list_chapters(target_id)

    if not chapters_data:
        ch_res = db.table("chapters").select("*").eq("novel_id", target_id).execute()
        if not ch_res.data and target_id != novel_id:
            ch_res = db.table("chapters").select("*").eq("novel_id", novel_id).execute()
        raw_db_chapters = ch_res.data or []
        if isinstance(raw_db_chapters, dict):
            raw_db_chapters = list(raw_db_chapters.values())
        if isinstance(raw_db_chapters, list):
            chapters_data = [ch for ch in raw_db_chapters if isinstance(ch, dict)]
            chapters_data.sort(key=lambda x: x.get("chapter_number", 0))

    # 3. Tự động cào danh mục chương nếu chưa có
    if not chapters_data and novel_row.get("source_url"):
        try:
            scraper = get_scraper_for_url(novel_row["source_url"])
            detail = await scraper.fetch_novel_info(novel_row["source_url"])
            if detail.chapters:
                from services.translation_worker import translation_worker
                # Dịch tối đa 30 chương đầu để phản hồi nhanh
                first_30 = detail.chapters[:30]
                titles = [c.title for c in first_30]
                translated = await translation_worker.translate_chapter_titles_batch(titles)
                chapters_index = []
                ch_records = []
                for idx, c in enumerate(detail.chapters):
                    vi = translated[idx] if idx < len(translated) else ""
                    c.title_vi = vi
                    ch_records.append({
                        "novel_id": novel_id,
                        "chapter_number": c.chapter_number,
                        "title": c.title,
                        "title_vi": vi,
                        "url": c.url
                    })
                    chapters_index.append({
                        "chapter_number": c.chapter_number,
                        "id": c.id or str(c.chapter_number),
                        "title": c.title,
                        "title_vi": vi,
                        "url": c.url
                    })
                from services.chapter_persistence import persist_chapters
                persist_chapters(novel_id, ch_records, chapters_index)
                chapters_data = chapters_index
        except Exception:
            pass

    # 4. Kiểm tra xem có chương nào trong 30 chương đầu cần dịch tựa đề không (tránh dịch 1000+ chương gây timeout)
    needs_trans = any(not ch.get("title_vi") for ch in chapters_data[:30])
    if needs_trans and chapters_data:
        first_30 = chapters_data[:30]
        titles = [ch.get("title", f"Chương {ch.get('chapter_number', idx + 1)}") for idx, ch in enumerate(first_30)]
        from services.translation_worker import translation_worker
        translated_titles = await translation_worker.translate_chapter_titles_batch(titles)
        for idx, ch in enumerate(first_30):
            if idx < len(translated_titles) and translated_titles[idx]:
                ch["title_vi"] = translated_titles[idx]
                storage_service.save_chapter(novel_id, ch.get("chapter_number", idx + 1), {"title_vi": ch["title_vi"]})
        storage_service.save_chapters_index(novel_id, chapters_data)

    # 5. Chuẩn hoá thứ tự chương và bổ sung tựa đề Hán-Việt cho toàn bộ chương còn lại
    if chapters_data:
        from services.toc_normalizer import normalize_chapters
        urls_with_val = [c.get("url") for c in chapters_data if c.get("url")]
        has_duplicates = len(urls_with_val) != len(set(urls_with_val))
        is_reversed = len(chapters_data) >= 2 and chapters_data[0].get("chapter_number", 1) > chapters_data[-1].get("chapter_number", 1)
        is_non_sequential = any(chapters_data[i].get("chapter_number") != i + 1 for i in range(min(50, len(chapters_data))))

        if is_reversed or has_duplicates or is_non_sequential:
            norm_res = await asyncio.to_thread(normalize_chapters, chapters_data)
            chapters_data = [
                {
                    "chapter_number": ch.chapter_number,
                    "id": ch.id,
                    "title": ch.title,
                    "title_vi": ch.title_vi,
                    "url": ch.url
                }
                for ch in norm_res
            ]
            storage_service.save_chapters_index(novel_id, chapters_data)
            novel_row["total_chapters"] = len(chapters_data)
            has_meta_update = True
        else:
            chapters_data.sort(key=lambda x: x.get("chapter_number", 0))

        from services.title_extractor import clean_chapter_title_vi

        def _clean_titles() -> bool:
            changed = False
            for idx, ch in enumerate(chapters_data):
                c_num = ch.get("chapter_number", idx + 1)
                raw_c_title_vi = ch.get("title_vi") or ""
                clean_vi = clean_chapter_title_vi(c_num, raw_c_title_vi, ch.get("title", f"Chương {c_num}"))
                if clean_vi != raw_c_title_vi:
                    ch["title_vi"] = clean_vi
                    changed = True
            return changed
        # Tựa rỗng được convert bằng từ điển (~0,1-0,3 ms/chương): mục lục nghìn chương chạy ở luồng phụ.
        has_new_titles = await asyncio.to_thread(_clean_titles)

        # Tựa chương còn là bản từ điển: gắn bản Hy-MT2 đã tinh chỉnh (nếu có) và xếp hàng tinh chỉnh nền.
        from services.title_patcher import overlay_toc
        if overlay_toc(novel_id, chapters_data):
            has_new_titles = True

        if has_new_titles:
            storage_service.save_chapters_index(novel_id, chapters_data)

    # Ensure title_vi, author and description are properly populated and translated
    from services.title_extractor import is_conversational_filler, extract_clean_ai_title
    from services.fast_translator import fast_translator
    from services.translation_worker import translation_worker
    from services.hanviet_dict import transliterate_to_hanviet
    has_meta_update = False
    current_title_vi = novel_row.get("title_vi", "")
    from services.title_service import book_title, book_title_replaceable
    if not current_title_vi or is_conversational_filler(current_title_vi) or has_cjk(current_title_vi):
        trans_t = await fast_translator.translate_text(novel_row.get("title", ""), sl="zh-CN", tl="vi")
        clean_t = extract_clean_ai_title(trans_t, novel_row.get("title", "")) or trans_t
        if not clean_t or is_conversational_filler(clean_t):
            clean_t = transliterate_to_hanviet(novel_row.get("title", ""))
        novel_row["title_vi"] = clean_t
        has_meta_update = True
    elif has_cjk(novel_row.get("title", "")) and book_title_replaceable(novel_row["title"], current_title_vi):
        better, _src = book_title(novel_row["title"])  # bản tinh chỉnh đã về thì thay; chưa thì xếp hàng
        if better and better != current_title_vi and not has_cjk(better):
            novel_row["title_vi"] = better
            has_meta_update = True

    current_author = novel_row.get("author", "")
    author_is_invalid = not current_author or current_author == novel_row.get("title") or current_author == novel_row.get("title_vi")
    current_desc = novel_row.get("description", "")
    from services.title_extractor import clean_ai_description
    cleaned_desc = clean_ai_description(current_desc)
    if cleaned_desc != current_desc:
        novel_row["description"] = cleaned_desc
        current_desc = cleaned_desc
        has_meta_update = True

    # Re-fetch source info if description is missing/Chinese or author was erroneously set to title
    if (not current_desc or has_cjk(current_desc) or author_is_invalid) and novel_row.get("source_url"):
        try:
            scraper = get_scraper_for_url(novel_row["source_url"])
            detail = await scraper.fetch_novel_info(novel_row["source_url"])
            if author_is_invalid and detail.author:
                if has_cjk(detail.author):
                    trans_a = await translation_worker.translate_author_name(detail.author)
                    novel_row["author"] = trans_a or transliterate_to_hanviet(detail.author)
                else:
                    novel_row["author"] = detail.author
                current_author = novel_row["author"]
                has_meta_update = True

            raw_desc = detail.description or current_desc
            if raw_desc and (not current_desc or has_cjk(current_desc)):
                if has_cjk(raw_desc):
                    trans_d = await translation_worker.translate_novel_description(raw_desc)
                    novel_row["description"] = trans_d or transliterate_to_hanviet(raw_desc)
                else:
                    novel_row["description"] = raw_desc
                has_meta_update = True
        except Exception:
            pass

    if current_author and has_cjk(current_author):
        translated_author = await translation_worker.translate_author_name(current_author)
        novel_row["author"] = translated_author or transliterate_to_hanviet(current_author)
        has_meta_update = True

    # If novel has 0 chapters, reflect status clearly
    if not chapters_data:
        novel_row["status"] = "Chưa phát hành chương (Tác giả đang lưu bản thảo)"
        has_meta_update = True

    cover = novel_row.get("cover_url") or ""
    if not cover or cover.strip() == "":
        from services.cover_service import cover_service
        cover = await cover_service.find_cover_by_title(novel_row.get("title", ""))
        novel_row["cover_url"] = cover
        has_meta_update = True

    if has_meta_update:
        try:
            storage_service.save_novel_info(novel_id, novel_row)
            db.table("novels").upsert(novel_row).execute()
        except Exception:
            pass

    from services.novel_chapter_stats import get_novel_translation_meta
    tmeta = await asyncio.to_thread(get_novel_translation_meta, novel_row["id"])

    return NovelDetail(
        id=novel_row["id"],
        title=novel_row["title"],
        title_vi=novel_row.get("title_vi", ""),
        author=novel_row.get("author", ""),
        source_url=novel_row["source_url"],
        source_id=novel_row["source_id"],
        cover_url=cover,
        description=novel_row.get("description", ""),
        status=novel_row.get("status", "ongoing"),
        total_chapters=novel_row.get("total_chapters", len(chapters_data)),
        translated_chapters=tmeta["translated_chapters"],
        overnight_enabled=tmeta["overnight_enabled"],
        translated_chapter_numbers=tmeta["translated_chapter_numbers"],
        chapters=[
            {
                "chapter_number": ch["chapter_number"],
                "id": ch.get("id", str(ch["chapter_number"])),
                "title": ch.get("title", f"Chương {ch['chapter_number']}"),
                "title_vi": ch.get("title_vi", ""),
                "url": ch.get("url", "")
            }
            for ch in chapters_data
        ]
    )