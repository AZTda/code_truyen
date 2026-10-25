import asyncio
import time
import logging
from typing import List, Optional, Set
from models.novel import Novel, SearchResult
from .scraper_registry import registry
from .cover_service import cover_service
from .query_translator import query_translator
from .hanviet_dict import transliterate_to_hanviet, has_cjk
from .search_cache_service import search_cache_service
from .local_search import LocalSearchMixin, LOCAL_FUZZY_THRESHOLD, LOCAL_MAX_RESULTS  # noqa: F401 (re-export)
from .search_ranker import rank_results, top_web_score, drop_weak_web_results, LOW_CONFIDENCE, STRONG_MATCH
from .source_policy import REMOVED_SOURCE_IDS
from .text_similarity import to_simplified
from .task_coalescer import task_coalescer
from .title_extractor import is_conversational_filler
from .title_patcher import overlay_novels
from .title_refiner import PRIORITY_SEARCH_BOOK
from .title_service import book_title
from .search_title_polisher import refine_top_search_titles_fast

logger = logging.getLogger("truyencloud.search")

# Trần thời gian tổng cho một lượt tìm tương tác (giây)
SEARCH_DEADLINE_SECONDS = 6.0
REFINE_COOLDOWN_SECONDS = 600.0
REFINE_MAX_PENDING = 2
# Các nguồn web còn lại (tấn Giang đã gỡ vì không miễn phí)
WEB_SOURCE_IDS = ("69shuba", "uukanshu", "piaotia")


class SearchService(LocalSearchMixin):
    """4-tier bilingual high-accuracy search service:
    Tier 1: In-memory local library index — fuzzy VI + CJK, ranked (< 1ms)
    Tier 2: Persistent SQLite search cache (< 2ms)
    Tier 3: Offline/coalesced query translation (dict -> Hán-Việt -> GT -> AI)
    Tier 4: Tiered bounded web scraping, verified + ranked via Hán-Việt back-check
    """

    def __init__(self):
        self._refine_recent: dict = {}
        self._refine_pending = 0

    async def search(self, query: str, source: Optional[str] = None) -> SearchResult:
        clean_query = query.strip()
        if not clean_query:
            return SearchResult(query="", total=0, results=[])

        filter_source = (source or "").strip().lower()
        if filter_source in ("", "all"):
            filter_source = None

        # Tier 2: Check persistent search cache (< 2ms)
        cached = search_cache_service.get(clean_query, filter_source)
        if cached is not None:
            cached = [n for n in cached if n.source_id not in REMOVED_SOURCE_IDS]
            # Hòa trộn với thư viện local hiện tại để truyện vừa thêm
            # không bị bản cache 48h che mất.
            merged = self._merge_cached_with_local(clean_query, cached, filter_source)
            overlay_novels(merged)  # tựa đã được Hy-MT2 tinh chỉnh sau khi cache được ghi
            return SearchResult(query=clean_query, total=len(merged), results=merged)

        combined: List[Novel] = []
        seen_keys: Set[str] = set()

        # Tier 1: Search local library first if searching 'all' or 'local'
        if not filter_source or filter_source == "local":
            local_novels = self.search_local_library(clean_query)
            for novel in local_novels:
                key = f"local_{novel.id}"
                if key not in seen_keys:
                    seen_keys.add(key)
                    combined.append(novel)

            # If user explicitly filtered for 'local', return immediately
            if filter_source == "local":
                search_cache_service.set(clean_query, combined, "local")
                return SearchResult(query=clean_query, total=len(combined), results=combined)

        # Trần thời gian tổng: các vòng phụ về sau chỉ được dùng phần còn lại
        started_at = time.monotonic()

        # Tier 3: Translate query terms (cached/coalesced).
        # Copy trước khi append — list trả về có thể là chính object trong cache.
        search_terms = list(await query_translator.translate_query(clean_query))
        if clean_query not in search_terms:
            search_terms.append(clean_query)

        # For Chinese scrapers, MUST pick CJK (Chinese) keyword if available!
        cjk_terms = [t for t in search_terms if has_cjk(t)]
        primary_term = cjk_terms[0] if cjk_terms else clean_query
        if not filter_source and cjk_terms:
            for novel in self.search_local_library(clean_query, cjk_terms):
                key = f"local_{novel.id}"
                if key not in seen_keys:
                    seen_keys.add(key)
                    combined.append(novel)

        # Map local novels to deduplicate against incoming online results
        local_lookup = self._build_local_lookup(combined)

        scraper_failed = False

        def _absorb(res) -> None:
            nonlocal scraper_failed
            if isinstance(res, list):
                self._merge_novels(res, combined, seen_keys, local_lookup)
            else:
                scraper_failed = True

        if filter_source:
            # Single source requested
            scraper = registry.get_by_source_id(filter_source)
            if scraper:
                _absorb(await self._search_scraper_with_timeout(scraper, primary_term))
        else:
            # Multi-source Tiered: khởi chạy CẢ HAI tầng song song ngay từ đầu
            # (speculative) — tầng chậm chỉ được chờ khi tầng nhanh trả < 3 kết quả,
            # nhưng vì nó đã chạy sẵn nên không phải cộng dồn thêm 3.5s.
            fast_scrapers = [
                registry.get_by_source_id("69shuba"),
                registry.get_by_source_id("uukanshu"),
            ]
            slow_scrapers = [
                registry.get_by_source_id("piaotia"),
            ]
            fast_tasks = [asyncio.create_task(self._search_scraper_with_timeout(s, primary_term)) for s in fast_scrapers if s]
            slow_tasks = [asyncio.create_task(self._search_scraper_with_timeout(s, primary_term)) for s in slow_scrapers if s]

            for res in await asyncio.gather(*fast_tasks, return_exceptions=True):
                _absorb(res)

            if len(combined) < 3:
                for res in await asyncio.gather(*slow_tasks, return_exceptions=True):
                    _absorb(res)
            else:
                for t in slow_tasks:
                    t.cancel()

            # Độ tin cậy thấp: thử các biến thể từ khóa tiếng Trung còn lại
            # (từ điển/AI có thể trả nhiều ứng viên) trên các nguồn nhanh —
            # chỉ trong phần ngân sách thời gian còn lại của lượt tìm.
            confidence = top_web_score(clean_query, combined, cjk_terms)
            alt_terms = [t for t in cjk_terms[1:3] if t != primary_term]
            if alt_terms and (confidence is None or confidence < STRONG_MATCH):
                alt_timeout = max(0.8, SEARCH_DEADLINE_SECONDS - (time.monotonic() - started_at))
                alt_tasks = [
                    asyncio.create_task(self._search_scraper_with_timeout(s, t, timeout=alt_timeout))
                    for t in alt_terms for s in fast_scrapers if s
                ]
                for res in await asyncio.gather(*alt_tasks, return_exceptions=True):
                    _absorb(res)

        # Xếp hạng song ngữ: local trước, sau đó theo điểm liên quan (kết quả
        # web tiếng Trung được kiểm định ngược qua phiên âm Hán-Việt + đối chiếu cjk_terms).
        combined = rank_results(clean_query, combined, cjk_terms)
        combined = drop_weak_web_results(clean_query, combined, cjk_terms)
        await refine_top_search_titles_fast(
            combined, max(0.0, SEARCH_DEADLINE_SECONDS - (time.monotonic() - started_at))
        )

        # Từ khóa tiếng Việt mà kết quả web vẫn kém liên quan: cho AI dịch lại
        # TÊN GỐC chính thức ở chế độ nền rồi tự làm mới cache cho lần tìm sau.
        if not filter_source and not has_cjk(clean_query):
            final_conf = top_web_score(clean_query, combined, cjk_terms)
            if final_conf is None or final_conf < LOW_CONFIDENCE:
                self._schedule_ai_refine(clean_query, primary_term, scraper_failed)

        # Chỉ cache kết quả rỗng khi KHÔNG có scraper nào gặp lỗi — tránh việc
        # một lần lỗi mạng tạm thời bị ghi nhớ thành "không có kết quả" 30 phút.
        if combined or not scraper_failed:
            search_cache_service.set(clean_query, combined, filter_source)
        logger.info(f"Search for '{clean_query}' completed with {len(combined)} results (cached={bool(combined) or not scraper_failed}).")
        return SearchResult(query=clean_query, total=len(combined), results=combined)

    def _schedule_ai_refine(self, query: str, primary_term: str, scraper_failed: bool = False) -> None:
        """Lên lịch tinh chỉnh nền, có chống bão: bỏ qua khi kết quả kém là do
        lỗi mạng, từ khóa quá ngắn/rác, đã refine gần đây, hoặc quá 2 tác vụ chờ."""
        if scraper_failed:
            return
        clean = query.strip()
        if len(clean) < 5 or len(clean.split()) < 2 or is_conversational_filler(clean):
            return
        key = clean.lower()
        now = time.monotonic()
        if now - self._refine_recent.get(key, -1e12) < REFINE_COOLDOWN_SECONDS:
            return
        if self._refine_pending >= REFINE_MAX_PENDING:
            return
        try:
            asyncio.get_running_loop()
        except RuntimeError:
            return

        self._refine_recent[key] = now
        if len(self._refine_recent) > 200:
            cutoff = now - REFINE_COOLDOWN_SECONDS
            for stale in [k for k, ts in self._refine_recent.items() if ts < cutoff]:
                del self._refine_recent[stale]

        self._refine_pending += 1

        async def _run():
            try:
                await task_coalescer.run_coalesced(
                    f"search_refine:{key}",
                    lambda: self._refine_search_in_background(clean, primary_term),
                )
            finally:
                self._refine_pending -= 1

        asyncio.create_task(_run())

    async def _refine_search_in_background(self, query: str, primary_term: str) -> None:
        """AI dịch lại tên gốc tiếng Trung chính thức rồi làm mới cache tìm kiếm.

        Chạy hoàn toàn ở nền: lượt tìm hiện tại không chờ; lượt tìm sau (kể cả
        do app tự lặp lại) nhận kết quả đã chính xác từ cache.
        """
        try:
            ai_terms = await query_translator.translate_query_via_ai(query)
            ai_cjk = [t for t in ai_terms if has_cjk(t) and t != primary_term]
            if not ai_cjk:
                return
            term = ai_cjk[0]
            local_novels = self.search_local_library(query)
            combined = list(local_novels)
            seen_keys: Set[str] = set()
            local_lookup = self._build_local_lookup(local_novels)

            scrapers = [registry.get_by_source_id(s) for s in WEB_SOURCE_IDS]
            gathered = await asyncio.gather(
                *(self._search_scraper_with_timeout(s, term) for s in scrapers if s),
                return_exceptions=True,
            )
            new_web = 0
            for res in gathered:
                if isinstance(res, list):
                    before = len(combined)
                    self._merge_novels(res, combined, seen_keys, local_lookup)
                    new_web += len(combined) - before
            if new_web == 0:
                return

            # Gộp lại kết quả web cũ (từ từ khóa Google) — AI chỉ BỔ SUNG ứng
            # viên, việc xếp hạng quyết định thứ tự; không để cache mất dữ liệu.
            prior = search_cache_service.get(query, None) or []
            self._merge_novels([n for n in prior if n.source_id != "local"], combined, seen_keys, local_lookup)

            combined = rank_results(query, combined)
            search_cache_service.set(query, combined, None)
            # Xóa cache theo-từng-nguồn để lượt tìm có lọc nguồn cũng được cào
            # lại với từ khóa đã sửa (translator cache giờ đã đúng).
            search_cache_service.delete_for_query(query, WEB_SOURCE_IDS)
            logger.info(
                f"AI đã tinh chỉnh nền kết quả cho '{query}' (từ khóa gốc '{term}', {len(combined)} kết quả)."
            )
        except Exception as e:
            logger.debug(f"Tinh chỉnh nền cho '{query}' thất bại: {e}")

    def _merge_novels(self, new_novels: List[Novel], combined: List[Novel], seen_keys: Set[str], local_lookup: dict):
        for novel in new_novels:
            # Check if this online novel is already in our local library
            norm_title = cover_service.normalize_key(to_simplified(novel.title))
            if novel.id in local_lookup or (novel.url and novel.url in local_lookup) or (norm_title and norm_title in local_lookup):
                # Already present as local novel, avoid duplicate online card
                continue

            key = f"{novel.source_id}_{novel.id}"
            if key in seen_keys or norm_title in seen_keys:
                continue
            seen_keys.add(key)
            if norm_title:
                seen_keys.add(norm_title)

            # Fast Cover Resolution
            if not novel.cover_url or novel.cover_url.strip() == "":
                novel.cover_url = cover_service._memory_cache.get(norm_title) or "/static/fallback_cover.jpg"

            # Tựa hiển thị tức thì (tuyển chọn > bản Hy-MT2 đã tinh chỉnh > từ điển convert); tựa hiện đại xếp hàng
            # tinh chỉnh với ưu tiên cao nhất (kết quả tìm kiếm là thứ người dùng đang nhìn).
            if not novel.title_vi or is_conversational_filler(novel.title_vi) or has_cjk(novel.title_vi):
                vi_title, _src = book_title(novel.title, PRIORITY_SEARCH_BOOK)
                novel.title_vi = vi_title or transliterate_to_hanviet(novel.title) or novel.title

            # Fast Author Display
            if novel.author and has_cjk(novel.author):
                novel.author = transliterate_to_hanviet(novel.author) or novel.author

            combined.append(novel)

    async def _search_scraper_with_timeout(self, scraper, term: str, timeout: float = 3.5) -> Optional[List[Novel]]:
        """Runs scraper search with a bounded timeout so slow external sites do not stall search.

        Trả về None khi scraper lỗi/timeout (khác với [] — thực sự không có kết quả),
        để tầng trên biết không nên cache kết quả rỗng do lỗi tạm thời.
        """
        try:
            return await asyncio.wait_for(scraper.search(term), timeout=timeout)
        except (asyncio.TimeoutError, Exception) as e:
            logger.warning(f"Scraper '{getattr(scraper, 'SOURCE_ID', 'unknown')}' skipped for '{term}': {e}")
            return None


search_service = SearchService()
