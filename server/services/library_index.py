"""In-memory index of the local library for instant Tier-1 search.

Trước đây mỗi lượt tìm kiếm đọc lại toàn bộ info.json trên đĩa, quét cả bảng
`novels` trong SQLite và phiên âm Hán-Việt lại từng dòng. Index này dựng sẵn
một lần, tự làm mới theo TTL và được storage_service báo hiệu khi thư viện
thay đổi, nên Tier-1 search chỉ còn so khớp chuỗi trong bộ nhớ.
"""

import re
import time
import logging
import threading
from typing import Any, Dict, List

from services.source_policy import REMOVED_SOURCE_IDS
from services.text_similarity import strip_accents

logger = logging.getLogger("truyencloud.library_index")

INDEX_TTL_SECONDS = 30.0


class LibraryIndex:
    """Danh sách truyện trong thư viện kèm các trường tìm kiếm đã tính sẵn."""

    def __init__(self):
        self._entries: List[Dict[str, Any]] = []
        self._built_at: float = 0.0
        self._lock = threading.Lock()
        self._rebuilding = False
        self._dirty = False

    def invalidate(self) -> None:
        self._built_at = 0.0
        self._dirty = True

    def get_entries(self) -> List[Dict[str, Any]]:
        if time.monotonic() - self._built_at <= INDEX_TTL_SECONDS:
            return self._entries

        if not self._entries or self._dirty:
            # Lần đầu, hoặc thư viện VỪA thay đổi (thêm/sửa truyện): dựng đồng
            # bộ để truyện vừa thêm hiện ngay ở lượt tìm kế tiếp.
            self._dirty = False
            self._rebuild_locked()
            return self._entries

        # Đã có bản cũ: trả ngay bản cũ, dựng lại ở thread nền để việc đọc
        # hàng trăm info.json không chặn event loop giữa lượt tìm kiếm.
        if not self._rebuilding:
            self._rebuilding = True
            threading.Thread(target=self._rebuild_locked, daemon=True).start()
        return self._entries

    def _rebuild_locked(self) -> None:
        try:
            with self._lock:
                if time.monotonic() - self._built_at > INDEX_TTL_SECONDS:
                    self._rebuild()
        finally:
            self._rebuilding = False

    def _rebuild(self) -> None:
        from services.storage_service import storage_service
        from services.hanviet_dict import transliterate_to_hanviet
        from services.title_extractor import is_conversational_filler
        from database import db

        started = time.perf_counter()
        entries: List[Dict[str, Any]] = []
        seen_ids = set()

        raw_novels: List[Dict[str, Any]] = []
        try:
            raw_novels.extend(storage_service.list_all_novels())
        except Exception as e:
            logger.warning(f"Lỗi đọc thư viện đĩa khi dựng index: {e}")
        try:
            res = db.table("novels").select("*").execute()
            db_novels = res.data or []
            if isinstance(db_novels, dict):
                db_novels = list(db_novels.values())
            raw_novels.extend(n for n in db_novels if isinstance(n, dict))
        except Exception as e:
            logger.warning(f"Lỗi đọc database khi dựng index: {e}")

        for n in raw_novels:
            n_id = str(n.get("id", ""))
            if not n_id or n_id in seen_ids or n.get("source_id") in REMOVED_SOURCE_IDS:
                continue
            seen_ids.add(n_id)

            title = str(n.get("title") or "")
            raw_t_vi = n.get("title_vi") or n.get("translated_title") or ""
            if not raw_t_vi or is_conversational_filler(raw_t_vi):
                t_vi = transliterate_to_hanviet(title)
            else:
                t_vi = raw_t_vi
            t_vi_no_acc = strip_accents(t_vi)
            t_no_acc = strip_accents(title)

            author = str(n.get("author") or "")
            author_hv = transliterate_to_hanviet(author) if author else ""
            desc_parts = [
                str(n.get("description") or "")[:350],
                str(n.get("translated_description") or "")[:350],
                str(n.get("category") or ""),
            ]
            desc = " ".join(p for p in desc_parts if p)
            desc_no_acc = strip_accents(desc)
            entries.append({
                "novel": n,
                "id": n_id,
                "title_lower": title.lower(),
                "title_vi": t_vi,
                "title_vi_lower": t_vi.lower(),
                "author_lower": author.lower(),
                "author_no_acc": strip_accents(f"{author} {author_hv}".strip()),
                "title_no_acc": t_no_acc,
                "title_vi_no_acc": t_vi_no_acc,
                "desc_lower": desc.lower(),
                "desc_no_acc": desc_no_acc,
                "word_set": set(re.findall(r"\w+", t_vi_no_acc)) | set(re.findall(r"\w+", t_no_acc)),
                "desc_word_set": set(re.findall(r"\w+", desc_no_acc)),
            })

        self._entries = entries
        self._built_at = time.monotonic()
        logger.info(
            f"Đã dựng index thư viện: {len(entries)} truyện trong {(time.perf_counter() - started) * 1000:.1f}ms"
        )


library_index = LibraryIndex()
