import re
import json
import time
import asyncio
import logging
from typing import List, Dict
from services.cover_service import TITLE_MAPPING
from services.agy_runner import AgyRunnerMixin, TaskPriority
from services.task_coalescer import task_coalescer
from database import get_db_connection
from services.hanviet_dict import reverse_hanviet_query, has_cjk, generate_cjk_variants
from services.query_dict import cjk_variants, dictionary_candidates, merge_variants
from services.query_title_mapping import EXTENDED_TITLE_MAPPING

logger = logging.getLogger("truyencloud.query_translator")


class QueryTranslator(AgyRunnerMixin):
    """Từ khoá tìm kiếm (Việt/Anh/Latin) -> từ khoá tiếng Trung: bảng tuyển chọn, từ điển convert ngược,
    Hy-MT2 cho từ khoá dịch nghĩa, cache SQLite, Antigravity là đường cuối không chặn."""

    # Không chặn tìm kiếm quá mức này khi chờ AI dịch từ khóa; phần còn lại chạy nền
    INTERACTIVE_WAIT_SECONDS = 1.5
    NEGATIVE_CACHE_TTL = 600.0

    def __init__(self):
        self._cache: Dict[str, List[str]] = {}
        # Nguồn của từng mục cache: static (bảng tuyển chọn) | legacy (dòng cũ: Google hoặc AI, không rõ) | ai | mt
        self._cache_src: Dict[str, str] = {}
        self._neg_cache: Dict[str, float] = {}
        self._init_db()
        self._preseed_cache()

    def _init_db(self):
        try:
            conn = get_db_connection()
            with conn:
                conn.execute("""
                    CREATE TABLE IF NOT EXISTS query_translation_cache (
                        query_norm TEXT PRIMARY KEY,
                        variants_json TEXT NOT NULL,
                        created_at INTEGER DEFAULT (strftime('%s', 'now'))
                    )
                """)
            cols = {r[1] for r in conn.execute("PRAGMA table_info(query_translation_cache)").fetchall()}
            if "source" not in cols:
                with conn:
                    conn.execute("ALTER TABLE query_translation_cache ADD COLUMN source TEXT")
        except Exception as e:
            logger.warning(f"Failed to initialize query_translation_cache: {e}")

    def _preseed_cache(self):
        # 1. Pre-seed from static dictionaries
        for k, v in TITLE_MAPPING.items():
            self._cache[k.lower().strip()] = [v]
            self._cache_src[k.lower().strip()] = "static"
        for k, v in EXTENDED_TITLE_MAPPING.items():
            self._cache[k.lower().strip()] = [v]
            self._cache_src[k.lower().strip()] = "static"

        # 2. Pre-seed from SQLite persistent cache (dòng không có cột source là dòng cũ thời Google)
        try:
            conn = get_db_connection()
            cursor = conn.cursor()
            cursor.execute("SELECT query_norm, variants_json, source FROM query_translation_cache")
            rows = cursor.fetchall()
            for q_norm, v_json, source in rows:
                try:
                    self._cache[q_norm] = json.loads(v_json)
                    self._cache_src[q_norm] = source or "legacy"
                except Exception:
                    pass
            logger.info(f"Loaded {len(rows)} persisted query translations into memory cache.")
        except Exception as e:
            logger.warning(f"Could not load query_translation_cache: {e}")

    def has_cjk(self, text: str) -> bool:
        return has_cjk(text)

    def generate_cjk_variants(self, text: str) -> List[str]:
        """Generates search term variants for Chinese queries (with/without punctuation)."""
        return generate_cjk_variants(text)

    async def translate_query(self, query: str) -> List[str]:
        """Từ khoá -> các từ khoá tiếng Trung (tốt nhất trước), không Google.

        Bảng tuyển chọn > từ điển ngược (tức thì, cho từ khoá kiểu Hán-Việt) > Hy-MT2 Việt->Trung (từ khoá dịch
        nghĩa; chờ tối đa INTERACTIVE_WAIT_SECONDS, phần còn lại chạy nền rồi tự cache) > Antigravity.
        """
        clean = query.strip()
        if not clean:
            return []

        # If query already contains Chinese characters: instant normalization (0ms)
        if self.has_cjk(clean):
            return self.generate_cjk_variants(clean)

        lower_query = clean.lower()
        cached = self._cache.get(lower_query)
        source = self._cache_src.get(lower_query, "legacy")
        if cached and source == "static":
            return cached

        dict_cands, looks_hanviet = dictionary_candidates(clean)
        if cached:
            # Dòng cũ (không rõ Google hay AI) — hoặc bản Hy-MT2 dịch NGHĨA lúc từ điển chưa sẵn sàng — cho từ khoá
            # Hán-Việt: ứng viên tốt nhất của từ điển đứng đầu, dòng cache thứ hai (search_service thử nó khi kết quả
            # của từ khoá đầu kém liên quan).
            if source in ("legacy", "mt") and looks_hanviet and dict_cands:
                return merge_variants(dict_cands[:1] + cached[:1], dict_cands[1:] + cached[1:])
            return merge_variants(cached, dict_cands)
        if looks_hanviet and dict_cands:
            return dict_cands

        # Ứng viên phụ: từ điển (từ khoá lẫn âm thuần Việt) hoặc phiên âm ngược bảng nhỏ — không cache như bản dịch.
        hv_variants = dict_cands
        if not hv_variants:
            hanviet_cjk = reverse_hanviet_query(clean)
            hv_variants = self.generate_cjk_variants(hanviet_cjk) if hanviet_cjk else []

        # Negative cache: từ khóa từng dịch thất bại thì không gọi lại trong vòng 10 phút.
        if time.monotonic() - self._neg_cache.get(lower_query, -1e12) < self.NEGATIVE_CACHE_TTL:
            return hv_variants or [clean]

        # Gộp các lượt dịch trùng đang chạy. Chỉ chờ tối đa INTERACTIVE_WAIT_SECONDS; chậm hơn thì trả ứng viên
        # hiện có còn tác vụ vẫn chạy nền và tự cache cho lần tìm sau.
        task_key = f"query_trans:{lower_query}"
        task = asyncio.ensure_future(
            task_coalescer.run_coalesced(task_key, lambda: self._do_translate(clean, lower_query))
        )
        try:
            primary = await asyncio.wait_for(asyncio.shield(task), timeout=self.INTERACTIVE_WAIT_SECONDS)
        except asyncio.TimeoutError:
            logger.info(f"Dịch từ khóa '{clean}' chậm — tiếp tục chạy nền, dùng ứng viên hiện có.")
            primary = []
        except Exception:
            primary = []

        merged = [t for t in primary if self.has_cjk(t)]
        merged += [v for v in hv_variants if v not in merged]
        return merged or [clean]

    async def _do_translate(self, clean: str, lower_query: str) -> List[str]:
        # Double check cache inside coalesced task
        if lower_query in self._cache:
            return self._cache[lower_query]

        # 1. Hy-MT2 Việt -> Trung (máy chủ nội bộ). Timeout nền: lượt tìm hiện tại chỉ chờ 1,5 s ở trên.
        try:
            from services.title_mt_client import title_mt
            zh = await title_mt.vi_to_zh(clean, timeout=title_mt.background_timeout / 3)
            if zh:
                variants = cjk_variants([zh])
                self._remember(lower_query, variants, "mt")
                logger.info(f"Hy-MT2 dịch từ khóa '{clean}' -> {variants}")
                return variants
        except Exception as e:
            logger.debug(f"Hy-MT2 query failed for '{clean}': {e}")

        result_variants = await self._translate_via_antigravity(
            clean, timeout=2.5, priority=TaskPriority.INTERACTIVE
        )
        if result_variants:
            self._remember(lower_query, result_variants, "ai")
            return result_variants

        # Fallback to original term (ghi negative cache trong bộ nhớ, không ghi SQLite
        # để lỗi tạm thời không bị "đóng đinh" vĩnh viễn)
        self._neg_cache[lower_query] = time.monotonic()
        return [clean]

    def _remember(self, lower_query: str, variants: List[str], source: str) -> None:
        self._cache[lower_query] = variants
        self._cache_src[lower_query] = source
        self._persist_translation(lower_query, variants, source)

    async def _translate_via_antigravity(
        self, clean: str, timeout: float, priority: TaskPriority
    ) -> List[str]:
        """Hỏi Antigravity TÊN GỐC tiếng Trung chính thức của truyện. Trả []
        khi AI không chắc chắn (guard KHONG_RO chống bịa tên làm bẩn cache)."""
        prompt = (
            f"Bạn là chuyên gia tra cứu tiểu thuyết mạng Trung Quốc. "
            f"Người dùng đang tìm truyện bằng tiếng Việt hoặc tiếng Anh: '{clean}'. "
            f"Hãy cho biết TÊN GỐC TIẾNG TRUNG CHÍNH THỨC của bộ tiểu thuyết này (tên dùng trên các trang truyện Trung Quốc). "
            f"Chỉ trả về DUY NHẤT tên tiếng Trung chuẩn xác (hoặc 1-2 từ khóa tiếng Trung cách nhau bởi dấu phẩy), "
            f"tuyệt đối không kèm giải thích. "
            f"Nếu bạn KHÔNG chắc chắn về tên gốc, trả về đúng chuỗi: KHONG_RO"
        )
        try:
            logger.info(f"Đang dùng Antigravity tra tên gốc cho từ khóa '{clean}' (lane {priority.value})...")
            raw_out = await self._execute_agy_process(
                prompt=prompt,
                timeout=timeout,
                model="gemini-3.8-flash-low",
                priority=priority,
            )
            if not raw_out or "KHONG_RO" in raw_out.upper():
                return []

            raw_clean = re.sub(r"[\"\'《》`*]", "", raw_out).strip()
            cjk_parts = [
                p.strip() for p in re.split(r"[,，\n/]+", raw_clean)
                if self.has_cjk(p.strip()) and self._looks_like_title(p.strip())
            ]
            all_variants: List[str] = []
            for p in cjk_parts:
                for v in self.generate_cjk_variants(p):
                    if v not in all_variants:
                        all_variants.append(v)
            result = all_variants or cjk_parts
            if result:
                logger.info(f"Antigravity dịch từ khóa '{clean}' -> {result}")
            return result
        except Exception as e:
            logger.warning(f"Lỗi khi dịch từ khóa '{clean}' qua Antigravity: {e}")
            return []

    _REFUSAL_MARKERS = ("不确定", "不知道", "未知", "无法", "抱歉", "对不起", "没有", "无从")

    def _looks_like_title(self, part: str) -> bool:
        """Chặn câu từ chối bằng tiếng Trung lọt qua guard KHONG_RO: loại phần
        chứa từ chối, dấu câu văn xuôi, quá dài, hoặc lẫn quá nhiều chữ Latin."""
        if any(m in part for m in self._REFUSAL_MARKERS):
            return False
        if any(p in part for p in "。！？；："):
            return False
        if len(part) > 15:
            return False
        cjk_ratio = sum(1 for c in part if '一' <= c <= '鿿') / len(part)
        return cjk_ratio >= 0.8

    async def translate_query_via_ai(self, query: str) -> List[str]:
        """Đường dịch AI chuyên sâu cho vòng tinh chỉnh nền: bỏ qua Google
        Translate (thường dịch NGHĨA thay vì tên gốc), hỏi thẳng Antigravity
        với thời lượng rộng hơn, và ghi đè cache khi có kết quả tin cậy."""
        clean = query.strip()
        if not clean:
            return []
        if self.has_cjk(clean):
            return self.generate_cjk_variants(clean)

        lower_query = clean.lower()
        task_key = f"query_trans_ai:{lower_query}"

        async def _do_ai() -> List[str]:
            variants = await self._translate_via_antigravity(
                clean, timeout=20.0, priority=TaskPriority.BACKGROUND
            )
            if variants:
                self._remember(lower_query, variants, "ai")
                self._neg_cache.pop(lower_query, None)
                return variants
            return [clean]

        return await task_coalescer.run_coalesced(task_key, _do_ai)

    def _persist_translation(self, lower_query: str, variants: List[str], source: str = "ai") -> None:
        try:
            conn = get_db_connection()
            with conn:
                conn.execute("""
                    INSERT INTO query_translation_cache (query_norm, variants_json, source)
                    VALUES (?, ?, ?)
                    ON CONFLICT(query_norm) DO UPDATE SET variants_json = excluded.variants_json,
                        source = excluded.source
                """, (lower_query, json.dumps(variants, ensure_ascii=False), source))
        except Exception as e:
            logger.warning(f"Could not persist query translation for '{lower_query}': {e}")


query_translator = QueryTranslator()
