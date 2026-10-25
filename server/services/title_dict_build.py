"""One-time build of the compact sqlite index from the convert dictionaries (streaming, low RAM).

Python dicts of the same data cost ~730 MB RSS; the sqlite file is read through a small page cache instead.
Semantics follow the benchmark engine (bench/hv_dict.py): first occurrence wins, Names from three files with the
same filters, readings from HanViet then PhienAm, reverse indexes ranked by character frequency.
"""

import logging
import math
import os
import pathlib
import re
import sqlite3
import time
from collections import Counter
from typing import Dict, Iterator, List, Optional, Tuple

from services.title_dict_sources import CHAR_FILES, NAME_FILES, PHRASE_FILE, dir_lock, sources_fingerprint
from services.title_dict_text import norm_vi, strip_acc

logger = logging.getLogger("truyencloud.title_dict.build")

BUILD_VERSION = "1"
BAD_NAME_KEY = re.compile(r"[0-9０-９\s]|^第.*[章节卷回]$")
WORD_MIN, WORD_MAX, WORD_KEEP = 2, 6, 30
_BATCH = 5000

SCHEMA = """
CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT) WITHOUT ROWID;
CREATE TABLE chars(ch TEXT PRIMARY KEY, readings TEXT NOT NULL, freq INTEGER NOT NULL) WITHOUT ROWID;
CREATE TABLE names(zh TEXT PRIMARY KEY, vi TEXT NOT NULL, seq INTEGER NOT NULL) WITHOUT ROWID;
CREATE TABLE vp(zh TEXT PRIMARY KEY, vi TEXT NOT NULL, self_read INTEGER NOT NULL) WITHOUT ROWID;
CREATE INDEX idx_vp_vi_lower ON vp(lower(vi));
CREATE TABLE prefix(ch TEXT PRIMARY KEY, name_max INTEGER NOT NULL, vp_max INTEGER NOT NULL) WITHOUT ROWID;
CREATE TABLE char_read(key TEXT, rank INTEGER, ch TEXT, reading TEXT, PRIMARY KEY(key, rank)) WITHOUT ROWID;
CREATE TABLE name_val(key TEXT, seq INTEGER, zh TEXT, PRIMARY KEY(key, seq)) WITHOUT ROWID;
CREATE TABLE word_read(key TEXT, rank INTEGER, zh TEXT, q REAL, PRIMARY KEY(key, rank)) WITHOUT ROWID;
"""


def iter_kv(path: str) -> Iterator[Tuple[str, str]]:
    """Cặp (khoá, nghĩa) của tệp QuickTranslator; dòng không có '=' nối vào nghĩa của khoá trước."""
    key: Optional[str] = None
    val = ""
    with open(path, encoding="utf-8-sig") as f:
        for line in f:
            line = line.rstrip("\r\n")
            if "=" in line:
                if key:
                    yield key, val
                k, v = line.split("=", 1)
                key, val = k.strip(), v.strip()
            elif line.strip() and key:
                val = (val + "/" + line.strip()).strip("/")
    if key:
        yield key, val


def _load_chars(src_dir: str) -> Tuple[Dict[str, List[str]], List[str]]:
    chars: Dict[str, List[str]] = {}
    order: List[str] = []
    for name in CHAR_FILES:
        for k, v in iter_kv(os.path.join(src_dir, name)):
            readings = [r.strip().lower() for r in v.split("/") if r.strip()]
            if len(k) == 1 and readings and k not in chars:
                chars[k] = readings
                order.append(k)
    return chars, order


def _read_key(word: str, chars: Dict[str, List[str]]) -> Optional[str]:
    if not WORD_MIN <= len(word) <= WORD_MAX:
        return None
    parts = []
    for c in word:
        rs = chars.get(c)
        if not rs:
            return None
        parts.append(strip_acc(rs[0]))
    return " ".join(parts)


def _flush(conn: sqlite3.Connection, sql: str, rows: list) -> None:
    if rows:
        conn.executemany(sql, rows)
        rows.clear()


def _load_names(conn, src_dir: str, freq: Counter) -> None:
    sql = "INSERT OR IGNORE INTO names(zh, vi, seq) VALUES (?,?,?)"
    rows, seq = [], 0
    for name in NAME_FILES:
        for k, v in iter_kv(os.path.join(src_dir, name)):
            v = v.split("/")[0].strip()
            if not v or BAD_NAME_KEY.search(k) or "chương" in v.lower():
                continue
            seq += 1
            rows.append((k, v, seq))
            if len(rows) >= _BATCH:
                _flush(conn, sql, rows)
        _flush(conn, sql, rows)
    for (k,) in conn.execute("SELECT zh FROM names"):
        freq.update(k)


def _load_phrases(conn, src_dir: str, chars: Dict[str, List[str]], freq: Counter) -> None:
    sql = "INSERT OR IGNORE INTO vp(zh, vi, self_read) VALUES (?,?,?)"
    rows = []
    for k, v in iter_kv(os.path.join(src_dir, PHRASE_FILE)):
        if not k:
            continue
        r = _read_key(k, chars)
        self_read = int(r is not None and any(strip_acc(norm_vi(m)) == r for m in v.split("/")))
        rows.append((k, v.split("/")[0].strip(), self_read))
        if len(rows) >= _BATCH:
            _flush(conn, sql, rows)
    _flush(conn, sql, rows)
    for (k,) in conn.execute("SELECT zh FROM vp"):
        freq.update(k)


def _build_reverse(conn, chars: Dict[str, List[str]], order: List[str], freq: Counter) -> None:
    rows = []
    for ch in order:
        conn.execute("INSERT INTO chars VALUES (?,?,?)", (ch, "/".join(chars[ch]), freq[ch]))
    by_key: Dict[str, List[Tuple[str, str]]] = {}
    for ch in order:
        for r in chars[ch][:3]:
            by_key.setdefault(strip_acc(r), []).append((ch, r))
    for key, lst in by_key.items():
        lst.sort(key=lambda x: -freq[x[0]])  # sort ổn định: hoà điểm giữ thứ tự tệp như benchmark
        rows.extend((key, i, ch, r) for i, (ch, r) in enumerate(lst))
    _flush(conn, "INSERT INTO char_read VALUES (?,?,?,?)", rows)
    for zh, vi, seq in conn.execute("SELECT zh, vi, seq FROM names").fetchall():
        rows.append((strip_acc(norm_vi(vi)), seq, zh))
    _flush(conn, "INSERT OR IGNORE INTO name_val VALUES (?,?,?)", rows)

    conn.execute("CREATE TEMP TABLE cand(key TEXT, zh TEXT, q REAL)")
    cur = conn.execute(
        "SELECT u.zh, v.zh IS NOT NULL, COALESCE(v.self_read, 0), n.zh IS NOT NULL FROM "
        "(SELECT zh FROM vp WHERE length(zh) BETWEEN ? AND ? UNION SELECT zh FROM names "
        "WHERE length(zh) BETWEEN ? AND ?) u LEFT JOIN vp v ON v.zh = u.zh LEFT JOIN names n ON n.zh = u.zh",
        (WORD_MIN, WORD_MAX, WORD_MIN, WORD_MAX))
    for zh, in_vp, self_read, in_names in cur:
        key = _read_key(zh, chars)
        if key is None:
            continue
        q = 0.15 * math.log1p(min(freq[c] for c in zh))
        if in_vp:
            q += 1.0 + (2.0 if self_read else 0.0)
        if in_names:
            q += 0.5
        rows.append((key, zh, q))
        if len(rows) >= _BATCH:
            _flush(conn, "INSERT INTO cand VALUES (?,?,?)", rows)
    _flush(conn, "INSERT INTO cand VALUES (?,?,?)", rows)
    conn.execute(
        "INSERT INTO word_read SELECT key, rn, zh, q FROM (SELECT key, zh, q, ROW_NUMBER() OVER "
        "(PARTITION BY key ORDER BY q DESC, zh DESC) AS rn FROM cand) WHERE rn <= ?", (WORD_KEEP,))
    conn.execute("DROP TABLE cand")


def _build_prefix(conn) -> None:
    conn.execute(
        "INSERT INTO prefix SELECT ch, MAX(nm), MAX(vm) FROM ("
        "SELECT substr(zh, 1, 1) AS ch, length(zh) AS nm, 0 AS vm FROM names UNION ALL "
        "SELECT substr(zh, 1, 1), 0, length(zh) FROM vp) GROUP BY ch")


def index_is_current(path: str) -> bool:
    try:
        conn = sqlite3.connect(pathlib.Path(os.path.abspath(path)).as_uri() + "?mode=ro", uri=True)
        try:
            meta = dict(conn.execute("SELECT k, v FROM meta").fetchall())
        finally:
            conn.close()
    except Exception:
        return False
    return meta.get("build_version") == BUILD_VERSION and meta.get("sources") == sources_fingerprint()


def build_index(src_dir: str, out_path: str, force: bool = False) -> Dict[str, object]:
    """Dựng (một tiến trình một lúc) nếu chỉ mục chưa có/cũ; bỏ qua khi tiến trình khác vừa dựng xong."""
    with dir_lock(os.path.dirname(os.path.abspath(out_path))):
        if not force and os.path.exists(out_path) and index_is_current(out_path):
            return {"skipped": True}
        return _build(src_dir, out_path)


def _build(src_dir: str, out_path: str) -> Dict[str, object]:
    """Dựng vào tệp tạm rồi os.replace: tiến trình bị giết giữa chừng không để lại chỉ mục dở."""
    started = time.monotonic()
    tmp = out_path + ".building"
    for leftover in (tmp, tmp + "-journal"):
        if os.path.exists(leftover):
            os.remove(leftover)
    conn = sqlite3.connect(tmp)
    try:
        conn.executescript("PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA temp_store=FILE;")
        conn.executescript(SCHEMA)
        chars, order = _load_chars(src_dir)
        freq: Counter = Counter()
        _load_names(conn, src_dir, freq)
        _load_phrases(conn, src_dir, chars, freq)
        _build_reverse(conn, chars, order, freq)
        _build_prefix(conn)
        stats = {t: conn.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
                 for t in ("chars", "names", "vp", "word_read", "name_val")}
        meta = {"build_version": BUILD_VERSION, "sources": sources_fingerprint(), "built_at": str(int(time.time()))}
        conn.executemany("INSERT INTO meta VALUES (?,?)", list(meta.items()))
        conn.commit()
    finally:
        conn.close()
    os.replace(tmp, out_path)
    stats["seconds"] = round(time.monotonic() - started, 1)
    stats["mb"] = round(os.path.getsize(out_path) / 1e6, 1)
    logger.info("Đã dựng chỉ mục từ điển %s: %s", out_path, stats)
    return stats
