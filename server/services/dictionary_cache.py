"""Bộ nhớ đệm bền vững cho các kết quả phân tích từ vựng / câu do AI sinh ra.

Vì sao cần: mỗi lời gọi `explain-word`, `life-scenarios`, `translate-sentence`...
đều spawn một tiến trình Antigravity CLI mất 25-35 giây. Từ vựng học thuật lặp lại
rất nhiều giữa các truyện và giữa các lần tra của cùng một người học, nên cache
giúp giảm hẳn độ trễ lẫn tải máy chủ.

Lưu trong SQLite riêng (`dictionary_cache.db`) để không đụng tới dữ liệu truyện.
"""

from contextlib import contextmanager
import os
import json
import time
import sqlite3
import hashlib
import logging
from typing import Optional, Dict, Any, Iterator

logger = logging.getLogger("truyencloud.dictionary_cache")

DB_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "data",
    "dictionary_cache.db",
)

# Thời gian sống mặc định: 180 ngày. Nghĩa từ vựng gần như không đổi,
# nhưng vẫn đặt TTL để có đường làm mới khi đổi model.
DEFAULT_TTL_SECONDS = 180 * 24 * 3600


class DictionaryCache:
    def __init__(self, db_path: str = DB_PATH):
        self.db_path = db_path
        os.makedirs(os.path.dirname(self.db_path), exist_ok=True)
        self._init_db()

    @contextmanager
    def _conn(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(self.db_path, timeout=10)
        try:
            conn.execute("PRAGMA journal_mode=WAL;")
            conn.execute("PRAGMA synchronous=NORMAL;")
            with conn:
                yield conn
        finally:
            conn.close()

    def _init_db(self):
        try:
            with self._conn() as conn:
                conn.execute(
                    """
                    CREATE TABLE IF NOT EXISTS dictionary_cache (
                        cache_key   TEXT PRIMARY KEY,
                        kind        TEXT NOT NULL,
                        term        TEXT NOT NULL,
                        payload     TEXT NOT NULL,
                        created_at  REAL NOT NULL,
                        hit_count   INTEGER NOT NULL DEFAULT 0
                    )
                    """
                )
                conn.execute(
                    "CREATE INDEX IF NOT EXISTS idx_dict_kind_term ON dictionary_cache(kind, term)"
                )
                conn.commit()
        except Exception as e:
            logger.error(f"Lỗi khởi tạo dictionary_cache: {e}")

    @staticmethod
    def make_key(kind: str, term: str, *parts: Optional[str]) -> str:
        """Khóa cache = kind + từ + các tham số phụ (ngữ cảnh, cấp độ...).

        Ngữ cảnh được băm để khóa không phình to; cùng một từ trong một câu
        giống hệt nhau sẽ tái sử dụng được kết quả.
        """
        raw = "|".join([kind, term.strip().lower()] + [(p or "").strip().lower() for p in parts])
        return hashlib.sha1(raw.encode("utf-8")).hexdigest()

    def get(self, cache_key: str, ttl_seconds: int = DEFAULT_TTL_SECONDS) -> Optional[Dict[str, Any]]:
        try:
            with self._conn() as conn:
                row = conn.execute(
                    "SELECT payload, created_at FROM dictionary_cache WHERE cache_key = ?",
                    (cache_key,),
                ).fetchone()
                if not row:
                    return None
                payload_raw, created_at = row
                if ttl_seconds > 0 and (time.time() - created_at) > ttl_seconds:
                    conn.execute("DELETE FROM dictionary_cache WHERE cache_key = ?", (cache_key,))
                    conn.commit()
                    return None
                conn.execute(
                    "UPDATE dictionary_cache SET hit_count = hit_count + 1 WHERE cache_key = ?",
                    (cache_key,),
                )
                conn.commit()
                return json.loads(payload_raw)
        except Exception as e:
            logger.warning(f"Lỗi đọc dictionary_cache: {e}")
            return None

    def put(self, cache_key: str, kind: str, term: str, payload: Dict[str, Any]) -> None:
        # KHÔNG cache kết quả dự phòng — nếu không sẽ đóng băng dữ liệu rỗng vĩnh viễn.
        if not payload or payload.get("is_fallback"):
            return
        try:
            with self._conn() as conn:
                conn.execute(
                    """
                    INSERT INTO dictionary_cache (cache_key, kind, term, payload, created_at, hit_count)
                    VALUES (?, ?, ?, ?, ?, 0)
                    ON CONFLICT(cache_key) DO UPDATE SET
                        payload = excluded.payload,
                        created_at = excluded.created_at
                    """,
                    (cache_key, kind, term.strip().lower(),
                     json.dumps(payload, ensure_ascii=False), time.time()),
                )
                conn.commit()
        except Exception as e:
            logger.warning(f"Lỗi ghi dictionary_cache: {e}")

    def stats(self) -> Dict[str, Any]:
        try:
            with self._conn() as conn:
                total = conn.execute("SELECT COUNT(*) FROM dictionary_cache").fetchone()[0]
                hits = conn.execute("SELECT COALESCE(SUM(hit_count), 0) FROM dictionary_cache").fetchone()[0]
                by_kind = dict(
                    conn.execute("SELECT kind, COUNT(*) FROM dictionary_cache GROUP BY kind").fetchall()
                )
                return {"entries": total, "total_hits": hits, "by_kind": by_kind}
        except Exception:
            return {"entries": 0, "total_hits": 0, "by_kind": {}}

    def purge(self, kind: Optional[str] = None) -> int:
        try:
            with self._conn() as conn:
                if kind:
                    cur = conn.execute("DELETE FROM dictionary_cache WHERE kind = ?", (kind,))
                else:
                    cur = conn.execute("DELETE FROM dictionary_cache")
                conn.commit()
                return cur.rowcount
        except Exception:
            return 0


dictionary_cache = DictionaryCache()
