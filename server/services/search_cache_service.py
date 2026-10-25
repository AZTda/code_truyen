"""Persistent SQLite-backed search cache service for TruyenCloud."""

import json
import time
import hashlib
import logging
from typing import List, Optional, Dict, Any
from models.novel import Novel
from database import get_db_connection

logger = logging.getLogger("truyencloud.search_cache")

DEFAULT_SEARCH_TTL = 86400 * 2  # 48 hours


class SearchCacheService:
    """Caches search results in SQLite to eliminate repeated web scraping
    and AI query translations for identical or common search terms.
    """

    def __init__(self):
        self._init_db()

    def _init_db(self):
        try:
            conn = get_db_connection()
            with conn:
                conn.execute("""
                    CREATE TABLE IF NOT EXISTS search_cache (
                        cache_key TEXT PRIMARY KEY,
                        query TEXT NOT NULL,
                        source TEXT,
                        results_json TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        ttl_seconds INTEGER NOT NULL
                    )
                """)
                conn.execute("CREATE INDEX IF NOT EXISTS idx_search_cache_created ON search_cache(created_at)")
        except Exception as e:
            logger.error(f"Failed to initialize search_cache table: {e}")

    def _normalize_key(self, query: str, source: Optional[str] = None) -> str:
        clean_q = " ".join(query.strip().lower().split())
        clean_s = (source or "all").strip().lower()
        raw = f"{clean_s}:{clean_q}"
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:32]

    def get(self, query: str, source: Optional[str] = None) -> Optional[List[Novel]]:
        """Retrieve cached search results if valid and not expired."""
        key = self._normalize_key(query, source)
        now = int(time.time())
        try:
            conn = get_db_connection()
            cursor = conn.cursor()
            cursor.execute(
                "SELECT results_json, created_at, ttl_seconds FROM search_cache WHERE cache_key = ?",
                (key,)
            )
            row = cursor.fetchone()
            if not row:
                return None

            results_json, created_at, ttl_seconds = row
            if now - created_at > ttl_seconds:
                # Expired
                self.delete(key)
                return None

            raw_list = json.loads(results_json)
            novels = [Novel(**item) for item in raw_list]
            logger.info(f"Search cache HIT for query='{query}' (source={source or 'all'}, count={len(novels)})")
            return novels
        except Exception as e:
            logger.warning(f"Error reading search cache for query '{query}': {e}")
            return None

    def set(
        self,
        query: str,
        novels: List[Novel],
        source: Optional[str] = None,
        ttl_seconds: int = DEFAULT_SEARCH_TTL
    ) -> None:
        """Store search results into cache."""
        if not novels:
            # Still cache empty results with a shorter TTL (30 mins) to prevent DDoS on bad queries
            ttl_seconds = 1800

        key = self._normalize_key(query, source)
        now = int(time.time())
        clean_s = (source or "all").strip().lower()

        try:
            raw_list = [n.model_dump() for n in novels]
            data_str = json.dumps(raw_list, ensure_ascii=False)
            conn = get_db_connection()
            with conn:
                conn.execute("""
                    INSERT INTO search_cache (cache_key, query, source, results_json, created_at, ttl_seconds)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(cache_key) DO UPDATE SET
                        results_json = excluded.results_json,
                        created_at = excluded.created_at,
                        ttl_seconds = excluded.ttl_seconds
                """, (key, query.strip(), clean_s, data_str, now, ttl_seconds))
            logger.info(f"Stored {len(novels)} search results into cache for '{query}' (ttl={ttl_seconds}s)")
        except Exception as e:
            logger.warning(f"Error saving search cache for '{query}': {e}")

    def delete_for_query(self, query: str, sources) -> None:
        """Xóa các bản cache theo-từng-nguồn của một từ khóa (dùng sau AI refine)."""
        for s in sources:
            self.delete(self._normalize_key(query, s))

    def delete(self, cache_key: str) -> None:
        try:
            conn = get_db_connection()
            with conn:
                conn.execute("DELETE FROM search_cache WHERE cache_key = ?", (cache_key,))
        except Exception as e:
            logger.warning(f"Error deleting search cache key {cache_key}: {e}")

    def purge_expired(self) -> int:
        now = int(time.time())
        try:
            conn = get_db_connection()
            with conn:
                cursor = conn.cursor()
                cursor.execute("DELETE FROM search_cache WHERE (? - created_at) > ttl_seconds", (now,))
                deleted = cursor.rowcount
            if deleted > 0:
                logger.info(f"Purged {deleted} expired search cache entries.")
            return deleted
        except Exception as e:
            logger.warning(f"Error purging expired search cache: {e}")
            return 0


search_cache_service = SearchCacheService()
