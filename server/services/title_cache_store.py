"""Persistent global cache of short-text translations (separate sqlite file, default data/title_cache.sqlite).

Row: (kind, src) -> {vi, source, novels, attempts, updated}. kind is book | chapter | query; src is the Chinese
text (for a query: the folded Vietnamese search string, vi then holds the Chinese keyword).
source = 'llm'   refined by Hy-MT2, served before the dictionary;
         'dict'  dictionary answer still waiting for refinement (the queue survives restarts through these rows);
         'final' the model failed twice: keep the dictionary answer, never retry.
`novels` lists novel ids whose stored TOC holds the subtitle, so a late result can patch chapters_index.json.
Shared by every user and every novel; lives in the ./data volume.
"""

import json
import logging
import os
import sqlite3
import threading
import time
from typing import Dict, Iterable, List, Optional, Tuple

from services.lru_dict import LruDict

logger = logging.getLogger("truyencloud.title_cache")

KINDS = ("book", "chapter", "query")
SOURCE_LLM, SOURCE_DICT, SOURCE_FINAL = "llm", "dict", "final"
_MISS = ("", "")
_SCHEMA = ("CREATE TABLE IF NOT EXISTS titles (kind TEXT NOT NULL, src TEXT NOT NULL, vi TEXT NOT NULL, "
           "source TEXT NOT NULL, novels TEXT NOT NULL DEFAULT '[]', attempts INTEGER NOT NULL DEFAULT 0, "
           "updated INTEGER NOT NULL, PRIMARY KEY (kind, src)) WITHOUT ROWID")


def default_path() -> str:
    configured = (os.getenv("TITLE_CACHE_PATH") or "").strip()
    if configured:
        return os.path.abspath(configured)
    return os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "title_cache.sqlite")


class TitleCacheStore:
    def __init__(self, path: Optional[str] = None):
        self._lock = threading.RLock()
        self._conn: Optional[sqlite3.Connection] = None
        self._mem: LruDict = LruDict(20000)
        self._retry_at = 0.0
        self.path = path or default_path()

    def use_path(self, path: str) -> None:
        """Đổi tệp (test). Đóng kết nối cũ, xoá bộ nhớ đệm."""
        with self._lock:
            self.close()
            self.path = path
            self._retry_at = 0.0
            self._mem.clear()

    def close(self) -> None:
        with self._lock:
            if self._conn is not None:
                try:
                    self._conn.close()
                except Exception:
                    pass
            self._conn = None

    RETRY_SECONDS = 60.0

    def _db(self) -> Optional[sqlite3.Connection]:
        if self._conn is not None:
            return self._conn
        if time.monotonic() < self._retry_at:
            return None  # tệp hỏng/không ghi được: không thử mở lại cho từng tựa của cả mục lục (chậm + tràn log)
        try:
            os.makedirs(os.path.dirname(self.path) or ".", exist_ok=True)
            conn = sqlite3.connect(self.path, check_same_thread=False, timeout=5)
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute(_SCHEMA)
            self._conn = conn
        except Exception as e:
            self._retry_at = time.monotonic() + self.RETRY_SECONDS
            logger.warning("Không mở được cache tựa đề %s: %s", self.path, e)
            return None
        return self._conn

    def get(self, kind: str, src: str) -> Optional[Tuple[str, str]]:
        """(vi, source) hoặc None. Lỗi đĩa = coi như chưa có (fail soft)."""
        key = (kind, src)
        with self._lock:
            hit = self._mem.get(key)
            if hit is not None:
                return None if hit == _MISS else hit
            row = None
            conn = self._db()
            if conn is not None:
                try:
                    row = conn.execute("SELECT vi, source FROM titles WHERE kind=? AND src=?", key).fetchone()
                except Exception as e:
                    logger.debug("Đọc cache tựa đề lỗi: %s", e)
            value = (row[0], row[1]) if row else _MISS
            self._mem[key] = value
            return None if value == _MISS else value

    def refined(self, kind: str, src: str) -> Optional[str]:
        hit = self.get(kind, src)
        return hit[0] if hit and hit[1] == SOURCE_LLM else None

    def put_llm(self, kind: str, src: str, vi: str) -> bool:
        if kind not in KINDS or not src or not vi:
            return False
        with self._lock:
            self._mem[(kind, src)] = (vi, SOURCE_LLM)
            return self._write(
                "INSERT INTO titles(kind, src, vi, source, updated) VALUES (?,?,?,?,?) ON CONFLICT(kind, src) "
                "DO UPDATE SET vi=excluded.vi, source=excluded.source, updated=excluded.updated",
                [(kind, src, vi, SOURCE_LLM, int(time.time()))])

    def add_pending(self, items: Iterable[Tuple[str, str, str, Iterable[str]]]) -> bool:
        """Ghi các mục đang chờ tinh chỉnh (kind, src, bản từ điển, novel ids); gộp novel id nếu đã chờ sẵn."""
        items = [(k, s, vi, set(n or ())) for k, s, vi, n in items if k in KINDS and s]
        if not items:
            return True
        now = int(time.time())
        with self._lock:
            conn = self._db()
            if conn is None:
                return False
            try:
                with conn:
                    for kind, src, vi, novels in items:
                        row = conn.execute("SELECT source, novels FROM titles WHERE kind=? AND src=?",
                                           (kind, src)).fetchone()
                        if row is None:
                            conn.execute("INSERT INTO titles(kind, src, vi, source, novels, updated) "
                                         "VALUES (?,?,?,?,?,?)",
                                         (kind, src, vi, SOURCE_DICT, json.dumps(sorted(novels)), now))
                            self._mem.pop((kind, src), None)
                            continue
                        merged = set(json.loads(row[1] or "[]")) | novels
                        if len(merged) > len(json.loads(row[1] or "[]")):
                            conn.execute("UPDATE titles SET novels=? WHERE kind=? AND src=?",
                                         (json.dumps(sorted(merged)), kind, src))
                return True
            except Exception as e:
                logger.warning("Ghi hàng đợi tựa đề lỗi: %s", e)
                return False

    def mark_failed(self, kind: str, src: str, max_attempts: int = 2) -> None:
        with self._lock:
            self._write("UPDATE titles SET attempts = attempts + 1, source = CASE WHEN attempts + 1 >= ? THEN "
                        "'final' ELSE source END WHERE kind=? AND src=? AND source='dict'",
                        [(max_attempts, kind, src)])
            self._mem.pop((kind, src), None)

    def pending(self, limit: int = 2000) -> List[Tuple[str, str, List[str]]]:
        with self._lock:
            conn = self._db()
            if conn is None:
                return []
            try:
                rows = conn.execute("SELECT kind, src, novels FROM titles WHERE source='dict' "
                                    "ORDER BY updated DESC LIMIT ?", (limit,)).fetchall()
            except Exception:
                return []
        return [(k, s, json.loads(n or "[]")) for k, s, n in rows]

    def novels_for(self, kind: str, src: str) -> List[str]:
        with self._lock:
            conn = self._db()
            try:
                row = conn.execute("SELECT novels FROM titles WHERE kind=? AND src=?", (kind, src)).fetchone() \
                    if conn is not None else None
            except Exception:
                row = None
        return json.loads(row[0]) if row and row[0] else []

    def _write(self, sql: str, rows: list) -> bool:
        conn = self._db()
        if conn is None:
            return False
        try:
            with conn:
                conn.executemany(sql, rows)
            return True
        except Exception as e:
            logger.warning("Ghi cache tựa đề lỗi: %s", e)
            return False

    def counts(self) -> Dict[str, int]:
        with self._lock:
            conn = self._db()
            if conn is None:
                return {}
            try:
                rows = conn.execute("SELECT kind || ':' || source, COUNT(*) FROM titles GROUP BY 1").fetchall()
                return dict(rows)
            except Exception:
                return {}


title_cache = TitleCacheStore()
