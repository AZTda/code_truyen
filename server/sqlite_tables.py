"""Bảng SQLite mô phỏng PostgREST của Supabase (tách khỏi database.py để giữ file nhỏ).

`database.py` chỉ giữ phần kết nối + DatabaseService và re-export các lớp ở đây.
"""

import re
import json
import hashlib
import sqlite3
import logging
import threading
from typing import Optional, Dict, Any, List, Tuple

from sqlite_owned import OwnedWritesMixin

logger = logging.getLogger("truyencloud.database")

_INDEX_CACHE_MAX = 500
_TABLE_NAME_RE = re.compile(r"^[a-zA-Z_][a-zA-Z0-9_]{0,63}$")


def _validate_table_name(name: str) -> str:
    """Khử khuẩn và kiểm tra tên bảng để phòng chống SQL injection."""
    sanitized = "".join(c for c in name if c.isalnum() or c == "_")
    if not _TABLE_NAME_RE.match(sanitized):
        raise ValueError(f"Invalid table name: '{name}' -> sanitized to '{sanitized}'.")
    return sanitized


def _connect(db_path: Optional[str]) -> sqlite3.Connection:
    # Import muộn: database.py import module này nên import ở đầu file sẽ tạo vòng.
    import database
    return database.get_db_connection(db_path)


class QueryResponse:
    def __init__(self, data: Any, count: int = 0):
        self.data = data
        self.count = count


class SqliteQuery:
    """Bộ dựng truy vấn thread-safe mô phỏng PostgREST của Supabase."""

    def __init__(self, table: "SqliteBackedTable", action: str = "select", update_data: Optional[Dict[str, Any]] = None):
        self.table = table
        self.action = action
        self.update_data = update_data
        self.filters: Dict[str, Any] = {}
        self.is_single = False

    def select(self, *args, **kwargs) -> "SqliteQuery":
        self.action = "select"
        return self

    def eq(self, column: str, value: Any) -> "SqliteQuery":
        self.filters[column] = value
        return self

    def single(self) -> "SqliteQuery":
        self.is_single = True
        return self

    def execute(self):
        return self.table._run_query(self)


class SqliteBackedTable(OwnedWritesMixin):
    """Lớp bảng SQLite bền vững dùng khi không cấu hình Supabase Cloud."""

    _VOLATILE_KEYS = frozenset({
        "updated_at", "created_at", "last_read_at", "translated_at",
        "scraped_at", "recrawled_at", "synced_at", "last_reviewed_at"
    })

    def __init__(self, name: str, db_path: Optional[str] = None):
        self.name = _validate_table_name(name)
        # None = đường dẫn mặc định, đọc lúc dùng (không chốt lúc định nghĩa hàm) để
        # test và `database.DB_PATH` đổi được nơi lưu.
        self._explicit_db_path = db_path
        self._index: Dict[str, Dict[str, Any]] = {}
        self._lock = threading.RLock()
        self._init_sqlite()

    @property
    def db_path(self) -> str:
        if self._explicit_db_path:
            return self._explicit_db_path
        import database
        return database.DB_PATH

    @db_path.setter
    def db_path(self, value: str) -> None:
        self._explicit_db_path = value

    def _get_connection(self) -> sqlite3.Connection:
        return _connect(self.db_path)

    def _init_sqlite(self):
        try:
            conn = self._get_connection()
            with conn:
                cursor = conn.cursor()
                cursor.execute(f"""
                    CREATE TABLE IF NOT EXISTS {self.name} (
                        pkey TEXT PRIMARY KEY,
                        data TEXT NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """)
                cursor.execute(f"CREATE INDEX IF NOT EXISTS idx_{self.name}_id ON {self.name}(json_extract(data, '$.id'))")
                cursor.execute(f"CREATE INDEX IF NOT EXISTS idx_{self.name}_novel_id ON {self.name}(json_extract(data, '$.novel_id'))")
                cursor.execute(f"CREATE INDEX IF NOT EXISTS idx_{self.name}_user_id ON {self.name}(json_extract(data, '$.user_id'))")
                cursor.execute(f"CREATE INDEX IF NOT EXISTS idx_{self.name}_source_url ON {self.name}(json_extract(data, '$.source_url'))")
        except Exception as e:
            logger.error(f"Lỗi khởi tạo bảng SQLite {self.name}: {e}")

    def _cache_put(self, pkey: str, data: Dict[str, Any]):
        with self._lock:
            self._index[pkey] = data
            if len(self._index) > _INDEX_CACHE_MAX:
                oldest_key = next(iter(self._index))
                del self._index[oldest_key]

    def _extract_pkey(self, data: Dict[str, Any]) -> str:
        if data.get("id"):
            return str(data["id"])
        if data.get("source_url"):
            return str(data["source_url"])
        if data.get("novel_id") and data.get("chapter_number") is not None:
            return f"{data['novel_id']}_{data['chapter_number']}"
        if data.get("novel_id") and data.get("current_chapter") is not None:
            uid = data.get("user_id") or "local"
            return f"progress_{uid}_{data['novel_id']}"
        if data.get("username"):
            return str(data["username"])
        stable = {k: v for k, v in data.items() if k not in self._VOLATILE_KEYS}
        digest = hashlib.sha1(
            json.dumps(stable, sort_keys=True, ensure_ascii=False, default=str).encode("utf-8")
        ).hexdigest()
        return f"auto_{digest}"

    def select(self, *args, **kwargs) -> SqliteQuery:
        return SqliteQuery(self, "select")

    def update(self, data: Dict[str, Any]) -> SqliteQuery:
        return SqliteQuery(self, "update", update_data=data)

    def delete(self) -> SqliteQuery:
        return SqliteQuery(self, "delete")

    def eq(self, column: str, value: Any) -> SqliteQuery:
        return SqliteQuery(self, "select").eq(column, value)

    def single(self) -> SqliteQuery:
        return SqliteQuery(self, "select").single()

    def _load_existing(self, conn: sqlite3.Connection, pkeys: List[str]) -> Dict[str, Dict[str, Any]]:
        if not pkeys:
            return {}
        found: Dict[str, Dict[str, Any]] = {}
        cursor = conn.cursor()
        for i in range(0, len(pkeys), 400):
            chunk = pkeys[i:i + 400]
            placeholders = ",".join("?" for _ in chunk)
            cursor.execute(f"SELECT pkey, data FROM {self.name} WHERE pkey IN ({placeholders})", chunk)
            for pk, raw in cursor.fetchall():
                if not raw:
                    continue
                try:
                    found[pk] = json.loads(raw)
                except Exception:
                    continue
        return found

    def upsert(self, data: Dict[str, Any], **_kwargs):
        pkey = self._extract_pkey(data)
        try:
            with self._lock:
                conn = self._get_connection()
                if pkey in self._index:
                    self._index[pkey].update(data)
                    data = dict(self._index[pkey])
                else:
                    existing = self._load_existing(conn, [pkey]).get(pkey)
                    if existing:
                        existing.update(data)
                        data = existing
                    else:
                        data = dict(data)
                    self._cache_put(pkey, data)

                with conn:
                    cursor = conn.cursor()
                    cursor.execute(f"""
                        INSERT INTO {self.name} (pkey, data)
                        VALUES (?, ?)
                        ON CONFLICT(pkey) DO UPDATE SET data = excluded.data, updated_at = CURRENT_TIMESTAMP
                    """, (pkey, json.dumps(data, ensure_ascii=False)))
        except Exception as e:
            logger.error(f"Lỗi ghi SQLite {self.name} pkey {pkey}: {e}")
        self._last_data = data
        return self

    def batch_upsert(self, records: List[Dict[str, Any]]):
        if not records:
            return self
        try:
            with self._lock:
                conn = self._get_connection()
                pkeys = [self._extract_pkey(item) for item in records]
                missing = [pk for pk in pkeys if pk not in self._index]
                persisted = self._load_existing(conn, missing) if missing else {}

                db_records = []
                for pkey, item in zip(pkeys, records):
                    if pkey in self._index:
                        self._index[pkey].update(item)
                        merged = dict(self._index[pkey])
                    elif pkey in persisted:
                        merged = persisted[pkey]
                        merged.update(item)
                        self._cache_put(pkey, merged)
                    else:
                        merged = dict(item)
                        self._cache_put(pkey, merged)
                    db_records.append((pkey, json.dumps(merged, ensure_ascii=False)))

                with conn:
                    cursor = conn.cursor()
                    cursor.executemany(f"""
                        INSERT INTO {self.name} (pkey, data)
                        VALUES (?, ?)
                        ON CONFLICT(pkey) DO UPDATE SET data = excluded.data, updated_at = CURRENT_TIMESTAMP
                    """, db_records)
        except Exception as e:
            logger.error(f"Lỗi batch upsert SQLite {self.name}: {e}")
        self._last_data = records
        return self

    def execute(self) -> QueryResponse:
        return QueryResponse(getattr(self, "_last_data", None))

    def _build_where(self, filters: Dict[str, Any]) -> Tuple[str, List[Any]]:
        if not filters:
            return "", []
        clauses = []
        params = []
        for key, val in filters.items():
            clean_key = "".join(c for c in key if c.isalnum() or c == "_")
            if not clean_key:
                continue
            if clean_key == "pkey":
                clauses.append("pkey = ?")
                params.append(str(val))
            elif clean_key == "id":
                clauses.append("(pkey = ? OR json_extract(data, '$.id') = ?)")
                params.extend([str(val), val])
            else:
                clauses.append(f"json_extract(data, '$.{clean_key}') = ?")
                params.append(val)
        where_sql = f" WHERE {' AND '.join(clauses)}" if clauses else ""
        return where_sql, params

    def _run_query(self, query: SqliteQuery) -> QueryResponse:
        # Tối ưu đọc điểm (point-lookup) nhanh qua cache nếu lọc theo id/pkey đơn lẻ
        if query.action == "select" and query.is_single and len(query.filters) == 1:
            col, val = next(iter(query.filters.items()))
            with self._lock:
                if col in ("id", "pkey") and str(val) in self._index:
                    return QueryResponse(dict(self._index[str(val)]))

        where_sql, params = self._build_where(query.filters)
        conn = self._get_connection()

        if query.action == "delete":
            to_delete_pkeys = []
            try:
                with self._lock, conn:
                    cursor = conn.cursor()
                    cursor.execute(f"SELECT pkey FROM {self.name}{where_sql}", params)
                    to_delete_pkeys = [r[0] for r in cursor.fetchall()]
                    for pk in to_delete_pkeys:
                        self._index.pop(pk, None)
                    if to_delete_pkeys:
                        cursor.execute(f"DELETE FROM {self.name}{where_sql}", params)
            except Exception as e:
                logger.error(f"Lỗi delete SQLite {self.name}: {e}")
            return QueryResponse([], count=len(to_delete_pkeys))

        if query.action == "update":
            update_data = query.update_data or {}
            updated_count = 0
            try:
                with self._lock, conn:
                    cursor = conn.cursor()
                    cursor.execute(f"SELECT pkey, data FROM {self.name}{where_sql}", params)
                    rows = cursor.fetchall()
                    updated_records = []
                    for pkey, raw_data in rows:
                        try:
                            obj = json.loads(raw_data) if raw_data else {}
                        except Exception:
                            continue
                        obj.update(update_data)
                        updated_records.append((json.dumps(obj, ensure_ascii=False), pkey))
                        if pkey in self._index:
                            self._index[pkey].update(update_data)
                    if updated_records:
                        cursor.executemany(
                            f"UPDATE {self.name} SET data = ?, updated_at = CURRENT_TIMESTAMP WHERE pkey = ?",
                            updated_records
                        )
                        updated_count = len(updated_records)
            except Exception as e:
                logger.error(f"Lỗi update SQLite {self.name}: {e}")
            return QueryResponse([], count=updated_count)

        # Mặc định: SELECT
        results = []
        try:
            with conn:
                cursor = conn.cursor()
                limit_clause = " LIMIT 1" if query.is_single else ""
                cursor.execute(f"SELECT pkey, data FROM {self.name}{where_sql}{limit_clause}", params)
                rows = cursor.fetchall()
                for pkey, raw_data in rows:
                    if not raw_data:
                        continue
                    try:
                        obj = json.loads(raw_data)
                    except Exception:
                        continue
                    results.append(obj)
                    self._cache_put(pkey, obj)
        except Exception as e:
            logger.error(f"Lỗi đọc SQLite {self.name}: {e}")

        res_data = results[0] if (query.is_single and results) else (None if query.is_single else results)
        return QueryResponse(res_data, count=len(results))
