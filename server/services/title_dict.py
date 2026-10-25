"""Convert-dictionary reader (zh->vi) over the compact sqlite index, plus its lazy, fail-soft manager.

Reads are thread-safe (one read-only connection per thread, LRU-cached lookups). When the index is missing or still
being built, `title_dict.get()` returns None and callers keep the small built-in Hán-Việt map: nothing crashes.
"""

import logging
import math
import os
import pathlib
import sqlite3
import threading
import time
from functools import lru_cache
from typing import Dict, List, Optional, Tuple

from services.text_similarity import to_simplified
from services.title_dict_build import BUILD_VERSION, build_index
from services.title_dict_convert import FUNC_CHARS, ConvertMixin  # noqa: F401 (FUNC_CHARS re-export)
from services.title_dict_sources import dict_dir, ensure_sources, index_path, missing_sources, sources_fingerprint
from services.title_dict_text import fold, norm_vi, strip_acc

logger = logging.getLogger("truyencloud.title_dict")

_BAD_VP_EDGE = frozenset("的了得个着过之所地们儿不很太更最被把在从向往话去来上下里外")


def _ensure_vp_index(path: str) -> None:
    """Đảm bảo chỉ mục tra ngược `idx_vp_vi_lower` có sẵn trên các tệp sqlite dựng trước đó."""
    try:
        conn = sqlite3.connect(path)
        try:
            has_idx = conn.execute(
                "SELECT 1 FROM sqlite_master WHERE type='index' AND name='idx_vp_vi_lower'"
            ).fetchone()
            if not has_idx:
                conn.execute("CREATE INDEX IF NOT EXISTS idx_vp_vi_lower ON vp(lower(vi))")
                conn.commit()
        finally:
            conn.close()
    except Exception:
        pass


class TitleDict(ConvertMixin):
    def __init__(self, path: str):
        self.path = path
        _ensure_vp_index(path)
        self._uri = pathlib.Path(os.path.abspath(path)).as_uri() + "?mode=ro"
        self._local = threading.local()
        meta = dict(self._conn().execute("SELECT k, v FROM meta").fetchall())
        if meta.get("build_version") != BUILD_VERSION:
            raise ValueError(f"chỉ mục từ điển phiên bản cũ: {meta.get('build_version')}")
        self.meta = meta
        self._has_vp_index = (
            self._one("SELECT 1 FROM sqlite_master WHERE type='index' AND name='idx_vp_vi_lower'", ()) is not None
        )
        max_f_row = self._one("SELECT MAX(freq) FROM chars", ())
        self._min_vp_freq = 15 if (max_f_row and (max_f_row[0] or 0) > 500) else 1
        self.readings = lru_cache(maxsize=40000)(self._readings)
        self.freq = lru_cache(maxsize=40000)(self._freq)
        self.name = lru_cache(maxsize=40000)(self._lookup_name)
        self.vp = lru_cache(maxsize=40000)(self._lookup_vp)
        self.prefix_max = lru_cache(maxsize=40000)(self._prefix_max)
        self.hv_term = lru_cache(maxsize=20000)(self._hv_term)
        self.word_read = lru_cache(maxsize=20000)(self._word_read)
        self.char_read = lru_cache(maxsize=20000)(self._char_read)
        self.name_val = lru_cache(maxsize=20000)(self._name_val)
        self.vp_val = lru_cache(maxsize=20000)(self._vp_val)

    # ---------- raw lookups ----------
    def _conn(self) -> sqlite3.Connection:
        conn = getattr(self._local, "conn", None)
        if conn is None:
            conn = sqlite3.connect(self._uri, uri=True, check_same_thread=False)
            conn.execute("PRAGMA cache_size=-4096")
            self._local.conn = conn
        return conn

    def _one(self, sql: str, args: tuple):
        return self._conn().execute(sql, args).fetchone()

    def _readings(self, ch: str) -> Tuple[str, ...]:
        row = self._one("SELECT readings FROM chars WHERE ch=?", (ch,))
        return tuple(row[0].split("/")) if row else ()

    def _freq(self, ch: str) -> int:
        row = self._one("SELECT freq FROM chars WHERE ch=?", (ch,))
        return int(row[0]) if row else 0

    def _lookup_name(self, zh: str) -> Optional[str]:
        row = self._one("SELECT vi FROM names WHERE zh=?", (zh,))
        return row[0] if row else None

    def _lookup_vp(self, zh: str) -> Optional[str]:
        """Nghĩa đầu tiên; '' nghĩa là khoá CÓ trong VietPhrase nhưng nghĩa đầu rỗng."""
        row = self._one("SELECT vi FROM vp WHERE zh=?", (zh,))
        return row[0] if row else None

    def _hv_term(self, zh: str) -> Optional[str]:
        """Nghĩa VietPhrase khi nghĩa ĐÚNG là âm Hán-Việt của từ (thuật ngữ giữ âm: 化神 -> Hóa Thần)."""
        row = self._one("SELECT vi FROM vp WHERE zh=? AND self_read=1", (zh,))
        if not row or not row[0]:
            return None
        readings = [self.reading(c) for c in zh]
        if not all(readings) or fold(row[0]) != " ".join(strip_acc(r) for r in readings):
            return None  # nghĩa đầu là dịch nghĩa (爱情 -> "tình yêu"), không phải âm Hán-Việt
        return row[0]

    def _prefix_max(self, ch: str) -> Tuple[int, int]:
        row = self._one("SELECT name_max, vp_max FROM prefix WHERE ch=?", (ch,))
        return (int(row[0]), int(row[1])) if row else (0, 0)

    def _word_read(self, key: str) -> Tuple[Tuple[float, str], ...]:
        rows = self._conn().execute("SELECT q, zh FROM word_read WHERE key=? ORDER BY rank", (key,)).fetchall()
        return tuple((float(q), zh) for q, zh in rows)

    def _char_read(self, key: str) -> Tuple[Tuple[str, str], ...]:
        rows = self._conn().execute(
            "SELECT ch, reading FROM char_read WHERE key=? ORDER BY rank LIMIT 12", (key,)).fetchall()
        return tuple((ch, r) for ch, r in rows)

    def _name_val(self, key: str) -> Tuple[str, ...]:
        rows = self._conn().execute("SELECT zh FROM name_val WHERE key=? ORDER BY seq", (key,)).fetchall()
        return tuple(r[0] for r in rows)

    def _vp_val(self, vi_phrase: str) -> Tuple[str, ...]:
        """Tra ngược cụm tiếng Việt / tiếng Anh trong 1,11 triệu mục VietPhrase (xếp hạng theo tần suất & chuẩn từ)."""
        if not self._has_vp_index:
            return ()
        q_acc = norm_vi(vi_phrase)
        if not q_acc:
            return ()
        q_fold = strip_acc(q_acc)
        n_syl = len(q_fold.split())
        rows = self._conn().execute(
            "SELECT zh FROM vp WHERE lower(vi)=? AND length(zh) BETWEEN 2 AND 5 LIMIT 60", (q_acc,)
        ).fetchall()
        scored = []
        for (zh,) in rows:
            if any(zh.startswith(c) or zh.endswith(c) for c in _BAD_VP_EDGE):
                continue
            freqs = [self.freq(c) for c in zh]
            min_f = min(freqs, default=0)
            if min_f < self._min_vp_freq:
                continue
            readings = [strip_acc(self.reading(c) or "") for c in zh]
            is_exact_hv = (" ".join(readings) == q_fold)
            in_names = self.name(zh) is not None
            same_len = (len(zh) == n_syl)
            score = (
                (5.0 if is_exact_hv else 0.0)
                + (3.0 if in_names else 0.0)
                + (2.0 if same_len else 0.0)
                + math.log1p(min_f)
                + 0.3 * math.log1p(sum(freqs))
                - 0.8 * len(zh)
            )
            scored.append((score, zh))
        scored.sort(reverse=True)
        out: List[str] = []
        for _, zh in scored:
            simp = to_simplified(zh)
            if simp and simp not in out:
                out.append(simp)
            if len(out) >= 3:
                break
        return tuple(out)

    def reading(self, ch: str) -> Optional[str]:
        rs = self.readings(ch)
        return rs[0] if rs else None

    def is_vn_syllable(self, folded: str) -> bool:
        """Âm tiết (không dấu) là âm đọc Hán-Việt của ít nhất một chữ — xấp xỉ "là âm tiết tiếng Việt"."""
        return bool(self.char_read(folded))

    def has_reading(self, key: str, accented: str) -> bool:
        """Có chữ nào đọc đúng âm có dấu này không (ngoài 12 chữ phổ biến nhất của khoá không dấu)."""
        return self._one("SELECT 1 FROM char_read WHERE key=? AND reading=? LIMIT 1", (key, accented)) is not None

    def close(self) -> None:
        conn = getattr(self._local, "conn", None)
        if conn is not None:
            conn.close()
            self._local.conn = None


class TitleDictManager:
    """Nạp lười chỉ mục; lần đầu tải + dựng ở luồng nền. Không bao giờ ném lỗi ra người gọi."""

    def __init__(self):
        self._dict: Optional[TitleDict] = None
        self._lock = threading.Lock()
        self._thread: Optional[threading.Thread] = None
        self.state = "idle"
        self.error: Optional[str] = None
        self._next_try = 0.0  # thời điểm được thử mở lại chỉ mục (chỉ mục có thể do lệnh tải tay dựng xong)

    @staticmethod
    def enabled() -> bool:
        return (os.getenv("TITLE_DICT_ENABLED", "1").strip() or "1") != "0"

    RETRY_SECONDS = 60.0

    def get(self) -> Optional[TitleDict]:
        d = self._dict
        if d is not None or time.monotonic() < self._next_try or not self.enabled():
            return d
        with self._lock:
            if self._dict is None and time.monotonic() >= self._next_try:
                self._next_try = time.monotonic() + self.RETRY_SECONDS
                self._try_open()
        return self._dict

    def _try_open(self) -> bool:
        path = index_path()
        if not os.path.exists(path):
            return False
        try:
            d = TitleDict(path)
            if d.meta.get("sources") != sources_fingerprint():
                raise ValueError("chỉ mục dựng từ bộ nguồn khác")
            self._dict, self.state, self.error = d, "ready", None
            return True
        except Exception as e:
            self.error = str(e)
            logger.warning("Không mở được chỉ mục từ điển %s: %s", path, e)
            return False

    def prepare_async(self) -> None:
        """Gọi lúc khởi động: tải tệp còn thiếu (nếu được phép) rồi dựng chỉ mục, ở luồng nền."""
        if not self.enabled() or self.get() is not None:
            return
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return
            self._thread = threading.Thread(target=self._prepare, name="title-dict-prepare", daemon=True)
            self._thread.start()

    def _prepare(self) -> None:
        try:
            directory = dict_dir()
            if missing_sources(directory):
                if (os.getenv("TITLE_DICT_AUTO_DOWNLOAD", "1").strip() or "1") == "0":
                    self.state = "missing"
                    return
                self.state = "downloading"
                ensure_sources(directory)
            self.state = "building"
            build_index(directory, index_path(directory))
            with self._lock:
                if self._try_open():
                    logger.info("Từ điển convert đã sẵn sàng (%s).", index_path(directory))
                else:
                    self.state = "error"
        except Exception as e:
            self.state, self.error = "error", str(e)
            logger.warning("Chuẩn bị từ điển convert thất bại (giữ bảng Hán-Việt nhỏ): %s", e)

    def install(self, d: Optional[TitleDict]) -> None:
        """Gắn sẵn một chỉ mục (test / công cụ chấm điểm)."""
        with self._lock:
            self._dict = d
            self._next_try = float("inf") if d is None else 0.0
            self.state = "ready" if d is not None else "idle"

    def reset(self) -> None:
        with self._lock:
            self._dict, self._next_try, self.state, self.error = None, 0.0, "idle", None

    def status(self) -> Dict[str, object]:
        return {"state": "ready" if self._dict is not None else self.state, "error": self.error}


title_dict = TitleDictManager()
