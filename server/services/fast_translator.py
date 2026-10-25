"""Instant translation of SHORT texts (book titles, TOC subtitles, search keywords) — no Google.

Short texts: model cache (Hy-MT2, refined in the background) > community convert dictionary > Hán-Việt
transliteration (services/title_service.py). Long texts (novel descriptions) and whole chapters go through the
paragraph-chunked fallback module services/chapter_fallback_translator.py, the only place that still calls Google.
"""

import hashlib
import logging
from typing import Dict, List, Optional

import httpx

from services.hanviet_dict import generate_cjk_variants, has_cjk, reverse_hanviet_candidates, reverse_hanviet_query
from services.lru_dict import LruDict
from services.query_dict import cjk_variants

logger = logging.getLogger("truyencloud.fast_translator")

# Văn bản dài (tóm tắt truyện...) được khoá bằng hash thay vì nguyên văn.
_MAX_RAW_KEY_CHARS = 200
# Dài hơn ngần này ký tự thì không còn là "tựa": đi đường văn bản dài.
SHORT_TEXT_MAX_CHARS = 120


def _text_cache_key(sl: str, tl: str, clean: str) -> str:
    if len(clean) <= _MAX_RAW_KEY_CHARS:
        return f"{sl}:{tl}:{clean}"
    return f"{sl}:{tl}#" + hashlib.sha1(clean.encode("utf-8")).hexdigest()


class FastTranslator:
    def __init__(self):
        self._long_cache: Dict[str, str] = LruDict(500)
        self._client: Optional[httpx.AsyncClient] = None

    def _get_client(self) -> httpx.AsyncClient:
        """Client HTTP bền vững (keep-alive) cho đường dịch văn bản dài."""
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(
                limits=httpx.Limits(max_connections=10, max_keepalive_connections=5)
            )
        return self._client

    async def aclose(self) -> None:
        if self._client is not None and not self._client.is_closed:
            await self._client.aclose()
        self._client = None

    async def translate_text(self, text: str, sl: str = "auto", tl: str = "vi",
                             target_language: Optional[str] = None) -> str:
        """Tựa ngắn tiếng Trung -> tiếng Việt tức thì (từ điển/cache mô hình). Văn bản dài -> translate_long_text."""
        if target_language:
            tl = target_language
        clean = (text or "").strip()
        if not clean or not has_cjk(clean):
            return clean
        if len(clean) > SHORT_TEXT_MAX_CHARS:
            return await self.translate_long_text(clean, tl) or clean
        if tl not in ("vi", "vie"):
            return clean
        from services.title_service import book_title
        vi, _src = book_title(clean)
        return vi or clean

    async def translate_long_text(self, text: str, tl: str = "vi") -> Optional[str]:
        """Văn bản dài (tóm tắt truyện): dịch theo đoạn qua module dự phòng chương. None nếu thất bại."""
        clean = (text or "").strip()
        if not clean:
            return ""
        key = _text_cache_key("auto", tl, clean)
        if key in self._long_cache:
            return self._long_cache[key]
        out = await self.translate_chapter_text(clean, tl=tl)
        if out and not has_cjk(out):
            self._long_cache[key] = out
            return out
        return None

    async def translate_chapter_text(self, text: str, tl: str = "vi") -> Optional[str]:
        """Dịch dự phòng NGUYÊN CHƯƠNG (chia đoạn, Google). Không dùng nhánh tựa đề."""
        from services.chapter_fallback_translator import translate_chapter_text
        return await translate_chapter_text(self._get_client(), text, tl)

    async def translate_query_to_cjk(self, query: str, use_hanviet: bool = True) -> List[str]:
        """Từ khoá tiếng Việt -> các biến thể chữ Hán để tìm trên trang nguồn.

        use_hanviet=True: ứng viên từ điển ngược (tức thì). use_hanviet=False: chỉ bản dịch của Hy-MT2 (cho từ khoá
        dịch nghĩa; chờ tối đa TITLE_MT_INTERACTIVE_TIMEOUT). Không có kết quả thì trả [query].
        """
        clean = (query or "").strip()
        if not clean:
            return []
        if has_cjk(clean):
            return generate_cjk_variants(clean)
        if use_hanviet:
            cands = reverse_hanviet_candidates(clean, 4) or [c for c in [reverse_hanviet_query(clean)] if c]
            if cands:
                return cjk_variants(cands)
        from services.title_mt_client import title_mt
        zh = await title_mt.vi_to_zh(clean)
        return cjk_variants([zh]) if zh else [clean]

    async def translate_batch_chapter_titles(self, titles: List[str], novel_id: Optional[str] = None) -> List[str]:
        """Danh sách tựa chương gốc -> 'Chương N: ...' (không trùng lặp 'Chương 1: Chương 1'), tức thì."""
        if not titles:
            return []
        from services.title_service import translate_toc
        return translate_toc(titles, novel_id)


fast_translator = FastTranslator()
