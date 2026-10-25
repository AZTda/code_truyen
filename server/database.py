import os
import sqlite3
import logging
import threading
import weakref
from typing import Optional, Dict, Tuple
from config import settings
# Các lớp bảng nằm ở sqlite_tables.py; re-export để `from database import SqliteBackedTable` vẫn chạy.
from sqlite_tables import QueryResponse, SqliteQuery, SqliteBackedTable  # noqa: F401

logger = logging.getLogger("truyencloud.database")

# Mặc định nằm cạnh mã nguồn. TRUYENCLOUD_DB_PATH cho phép đặt tệp trong một THƯ MỤC được mount, để -wal/-shm
# cũng được lưu cùng tệp chính (xem ghi chú trong docker-compose.yml). Không đặt thì hành vi y như cũ.
DB_PATH = os.getenv("TRUYENCLOUD_DB_PATH") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "truyencloud.db")


class _ThreadConnections:
    """Các kết nối SQLite của MỘT thread (theo đường dẫn).

    Sống trong threading.local nên thread kết thúc thì đối tượng này bị thu hồi và đóng kết nối, y như cách dùng
    threading.local() thuần trước đây. Thêm vào đó nó được ghi vào một sổ yếu toàn cục (_holders) để lúc tắt máy
    chủ có thể checkpoint + đóng kết nối của MỌI thread còn sống.
    """

    def __init__(self) -> None:
        self.by_path: Dict[str, sqlite3.Connection] = {}

    def close(self, path: Optional[str] = None) -> int:
        closed = 0
        for key in [k for k in list(self.by_path) if path is None or k == path]:
            try:
                self.by_path.pop(key).close()
                closed += 1
            except (KeyError, sqlite3.Error):
                pass
        return closed

    def __del__(self) -> None:
        try:
            self.close()
        except Exception:  # lúc interpreter tắt các biến toàn cục có thể đã bị dọn
            pass


_local_storage = threading.local()
_holders: "weakref.WeakSet[_ThreadConnections]" = weakref.WeakSet()
_holders_lock = threading.Lock()


def _open_connection(path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(path, timeout=30.0, check_same_thread=False)
    conn.execute("PRAGMA journal_mode=WAL;")
    conn.execute("PRAGMA synchronous=NORMAL;")
    conn.execute("PRAGMA busy_timeout=30000;")
    return conn


def get_db_connection(db_path: Optional[str] = None) -> sqlite3.Connection:
    """Quản lý kết nối SQLite theo thread kèm chế độ WAL và busy_timeout.

    `db_path=None` nghĩa là `DB_PATH` hiện tại (đọc lúc gọi, không chốt lúc định nghĩa hàm).
    """
    path = db_path or DB_PATH
    holder = getattr(_local_storage, "holder", None)
    if holder is None:
        holder = _local_storage.holder = _ThreadConnections()
        with _holders_lock:
            _holders.add(holder)
    conn = holder.by_path.get(path)
    if conn is None:
        conn = holder.by_path[path] = _open_connection(path)
    return conn


def checkpoint(db_path: Optional[str] = None, mode: str = "TRUNCATE") -> Optional[Tuple[int, int, int]]:
    """Gộp tệp -wal vào tệp chính (PRAGMA wal_checkpoint) rồi cắt -wal về 0 byte.

    VÌ SAO: docker-compose chỉ bind-mount MỘT tệp truyencloud.db nên -wal/-shm nằm trong lớp ghi của
    container; tạo lại container sẽ mất mọi giao dịch còn nằm trong -wal. Gọi định kỳ và lúc tắt máy
    chủ để dữ liệu luôn nằm trong tệp chính. Trả (busy, log, checkpointed) hoặc None nếu không chạy.
    """
    if mode not in ("PASSIVE", "FULL", "RESTART", "TRUNCATE"):
        raise ValueError(f"Chế độ checkpoint không hợp lệ: {mode!r}")
    path = db_path or DB_PATH
    if not os.path.exists(path):
        return None  # tránh sqlite3.connect tự tạo một tệp rỗng
    try:
        row = get_db_connection(path).execute(f"PRAGMA wal_checkpoint({mode});").fetchone()
        return (int(row[0]), int(row[1]), int(row[2])) if row else None
    except sqlite3.Error as e:
        logger.warning("Không checkpoint được %s: %s", path, e)
        return None


def close_all_connections(db_path: Optional[str] = None) -> int:
    """Đóng kết nối của mọi thread (chỉ một đường dẫn nếu có `db_path`). Trả về số kết nối đã đóng."""
    with _holders_lock:
        holders = list(_holders)
    return sum(holder.close(db_path) for holder in holders)


class DatabaseService:
    """Dịch vụ kết nối cơ sở dữ liệu Supabase kèm cơ chế SQLite bền vững tự động."""

    def __init__(self):
        self.client = None
        self.is_mock = False
        self._mock_tables: Dict[str, SqliteBackedTable] = {}

        if settings.SUPABASE_URL and settings.SUPABASE_SERVICE_KEY:
            try:
                from supabase import create_client
                self.client = create_client(settings.SUPABASE_URL, settings.SUPABASE_SERVICE_KEY)
                logger.info("Connected to Supabase PostgreSQL cloud database.")
            except Exception as e:
                logger.warning(f"Failed to connect to Supabase ({e}). Using local SQLite persistent store.")
                self.is_mock = True
        else:
            logger.info("No Supabase credentials provided. Running with local SQLite persistent store (truyencloud.db).")
            self.is_mock = True

    def table(self, table_name: str):
        if not self.is_mock and self.client:
            return self.client.table(table_name)
        if table_name not in self._mock_tables:
            self._mock_tables[table_name] = SqliteBackedTable(table_name)
        return self._mock_tables[table_name]


db = DatabaseService()
