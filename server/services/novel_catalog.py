"""Compact novel metadata for bookshelf rebuilds and the Discover feed."""

import time
import logging
import threading
from dataclasses import dataclass, field
from typing import Any, Dict, FrozenSet, List, Optional

from database import db
from services.discover_ranking import co_reader_counts, rank_for_you, taste_of
from services.sync_merge import ms_from_iso

logger = logging.getLogger("truyencloud.novel_catalog")

PROGRESS_TABLE = "reading_progress_backend"
PRIVATE_SOURCE_ID = "local_import"
# Kệ "bia mộ": app báo truyện đã bị gỡ khỏi tủ bằng cách ghi dòng tiến độ với kệ này (máy chủ không có API xoá).
REMOVED_SHELF = "removed"
DISCOVER_MAX_LIMIT = 500
_READER_COUNT_TTL_SECONDS = 60.0
# Id dùng chung cho mọi khách / tài khoản cũ thiếu id (xem services.user_data._UNSAFE_IDS): không phải một người.
_SHARED_USER_IDS = frozenset({"local", "user_internal"})


def classify_novel_category(row: Dict[str, Any], title_vi: str = "") -> str:
    """Phân loại thể loại truyện tự động từ tựa đề và văn án cho bộ lọc Khám phá."""
    blob = f"{row.get('title', '')} {title_vi or row.get('title_vi', '')} {row.get('description', '')[:400]}".lower()
    if any(k in blob for k in ("học bá", "khoa học", "nghiên cứu", "viện sĩ", "giáo sư", "toán học", "vật lý", "công nghệ", "hắc khoa kỹ", "学霸", "科研", "院士", "教授", "科技", "数学")):
        return "Khoa học & Học bá"
    if any(k in blob for k in ("đồng nhân", "chư thiên", "vạn giới", "hải tặc", "hokage", "nhẫn giả", "marvel", "mỹ mạn", "quần chat", "tổng mạn", "mô phỏng", "同人", "诸天", "万界", "海贼", "火影", "木叶", "漫威", "美漫", "聊天群")):
        return "Đồng nhân & Chư thiên"
    if any(k in blob for k in ("lịch sử", "đại đường", "đại minh", "đại tống", "tam quốc", "đại phụng", "hoàng đế", "vương triều", "quân sự", "tần", "hán", "历史", "大唐", "大明", "大宋", "三国", "皇帝", "朝廷")):
        return "Lịch sử & Quân sự"
    if any(k in blob for k in ("trọng sinh", "trùng sinh", "đô thị", "thập niên", "197", "198", "199", "200", "làm giàu", "thương nghiệp", "tài phiệt", "giải trí", "sống lại", "重生", "都市", "年代", "发家", "首富", "文娱")):
        return "Đô thị & Trọng sinh"
    return "Tiên hiệp & Huyền huyễn"


def novel_summary(row: Dict[str, Any], title_vi: Optional[str] = None) -> Dict[str, Any]:
    from services.novel_chapter_stats import get_chapter_counts
    try:
        total = int(row.get("total_chapters") or 0)
    except (TypeError, ValueError):
        total = 0
    nid = str(row.get("id") or "")
    t_vi = str(title_vi or row.get("title_vi") or "")
    counts = get_chapter_counts(nid) if nid else {}
    return {
        "id": nid,
        "title": str(row.get("title") or ""),
        "title_vi": t_vi,
        "author": str(row.get("author") or ""),
        "cover_url": str(row.get("cover_url") or ""),
        "total_chapters": total,
        "source_id": str(row.get("source_id") or ""),
        "source_url": str(row.get("source_url") or ""),
        "description": str(row.get("description") or ""),
        "status": str(row.get("status") or ""),
        "translated_chapters": int(counts.get("translated_chapters") or 0),
        "overnight_enabled": bool(row.get("overnight_enabled")),
        "category": classify_novel_category(row, t_vi),
    }


def lookup_novel_summary(novel_id: str) -> Optional[Dict[str, Any]]:
    """Metadata from info.json, then the `novels` table; None when unknown."""
    from services.storage_service import storage_service

    row = None
    try:
        row = storage_service.get_novel_info(novel_id)
    except Exception as e:
        logger.warning(f"Lỗi đọc info.json cho {novel_id}: {e}")
    if not row:
        try:
            res = db.table("novels").select("*").eq("id", novel_id).execute()
            rows = res.data or []
            row = rows[0] if isinstance(rows, list) and rows else None
        except Exception as e:
            logger.warning(f"Lỗi đọc bảng novels cho {novel_id}: {e}")
    if not isinstance(row, dict) or not row.get("title"):
        return None
    summary = novel_summary(row)
    summary["id"] = summary["id"] or novel_id
    return summary


def _is_private(novel: Dict[str, Any]) -> bool:
    return (novel.get("source_id") == PRIVATE_SOURCE_ID
            or str(novel.get("id") or "").startswith("import_"))


def _recency_ms(novel: Dict[str, Any]) -> int:
    return ms_from_iso(novel.get("updated_at")) or ms_from_iso(novel.get("created_at"))


@dataclass(frozen=True)
class ReaderSnapshot:
    """Một lượt quét bảng tiến độ. Chỉ đọc, và chứa id người dùng nên KHÔNG bao giờ được đưa nguyên ra phản hồi."""
    # truyện -> số người đọc phân biệt, tính cả dòng đã gỡ khỏi tủ (bảng "popular" xưa nay đếm vậy)
    counts: Dict[str, int] = field(default_factory=dict)
    # người dùng -> truyện còn trên kệ của họ (bỏ dòng bia mộ)
    libraries: Dict[str, FrozenSet[str]] = field(default_factory=dict)
    # truyện -> những người còn giữ nó trên kệ; chỉ dùng để ĐẾM người cùng gu
    keepers: Dict[str, FrozenSet[str]] = field(default_factory=dict)


class _ReaderCounter:
    """Một lượt quét tiến độ cho cả số người đọc mỗi truyện ("popular") lẫn tủ sách từng người ("for_you").

    Đệm ngắn vì mỗi lượt quét đọc mọi dòng tiến độ; `invalidate()` buộc quét lại ở lần đọc kế tiếp.
    """

    def __init__(self):
        self._snapshot = ReaderSnapshot()
        self._built_at = 0.0
        self._generation = 0
        self._lock = threading.Lock()

    def invalidate(self) -> None:
        self._generation += 1
        self._built_at = 0.0

    def snapshot(self) -> ReaderSnapshot:
        with self._lock:
            if time.monotonic() - self._built_at > _READER_COUNT_TTL_SECONDS:
                started = self._generation
                self._snapshot = self._scan()
                # invalidate() lọt vào giữa lúc quét (xoá tài khoản): bản vừa quét có thể còn dòng đã xoá, nên không
                # được ghi nhận là mới; lượt đọc kế tiếp quét lại thay vì giữ bản cũ thêm 60 giây.
                self._built_at = time.monotonic() if started == self._generation else 0.0
            return self._snapshot

    def counts(self) -> Dict[str, int]:
        return self.snapshot().counts

    @staticmethod
    def _scan() -> ReaderSnapshot:
        try:
            rows = db.table(PROGRESS_TABLE).select("user_id,novel_id,shelf_name").execute().data or []
        except Exception as e:
            logger.warning(f"Lỗi đếm người đọc: {e}")
            return ReaderSnapshot()
        readers: Dict[str, set] = {}
        shelved: Dict[str, set] = {}
        keepers: Dict[str, set] = {}
        for row in rows if isinstance(rows, list) else []:
            if not isinstance(row, dict) or not row.get("novel_id") or not row.get("user_id"):
                continue
            novel_id, user_id = str(row["novel_id"]), str(row["user_id"])
            readers.setdefault(novel_id, set()).add(user_id)
            # Id dùng chung gom dòng của nhiều người nên không phải "một người cùng gu": cho nó làm hàng xóm thì
            # nó góp một phiếu giả vào ngưỡng MIN_CO_READERS và lộ tủ sách của người thật giữ cùng truyện.
            if row.get("shelf_name") != REMOVED_SHELF and user_id not in _SHARED_USER_IDS:
                shelved.setdefault(user_id, set()).add(novel_id)
                keepers.setdefault(novel_id, set()).add(user_id)
        return ReaderSnapshot(
            counts={nid: len(users) for nid, users in readers.items()},
            libraries={uid: frozenset(ids) for uid, ids in shelved.items()},
            keepers={nid: frozenset(users) for nid, users in keepers.items()},
        )


reader_counter = _ReaderCounter()


def _summary(entry: Dict[str, Any], novel: Dict[str, Any]) -> Dict[str, Any]:
    return novel_summary(novel, title_vi=entry.get("title_vi"))


def _discover_for_you(
    entries: List[Dict[str, Any]], candidates: list, limit: int, user_id: Optional[str]
) -> Dict[str, Any]:
    """Xếp theo gu của `user_id`; khách, tài khoản lạ hay tủ sách trống nhận đúng bảng "recent"."""
    snapshot = None if not user_id or user_id in _SHARED_USER_IDS else reader_counter.snapshot()
    owned = snapshot.libraries.get(user_id, frozenset()) if snapshot is not None else frozenset()
    if not owned:
        candidates.sort(key=lambda c: c[2], reverse=True)
        novels = [dict(_summary(entry, novel), reason="") for entry, novel, _ in candidates[:limit]]
        return {"novels": novels, "total": len(candidates), "personalized": False}

    own_novels = (entry.get("novel") or {} for entry in entries if entry.get("id") in owned)
    taste = taste_of(own_novels, _is_private)
    public_ids = {entry["id"] for entry, _, _ in candidates}
    co_counts = co_reader_counts(owned, user_id, snapshot.libraries, snapshot.keepers, public_ids)
    ranked = rank_for_you(candidates, owned, taste, co_counts)
    novels = [dict(_summary(entry, novel), reason=reason) for entry, novel, reason in ranked[:limit]]
    return {"novels": novels, "total": len(ranked), "personalized": True}


def discover_novels(sort: str = "recent", limit: int = 30, user_id: Optional[str] = None) -> Dict[str, Any]:
    """Public, server-stored novels ordered by recency, distinct readers, or (for_you) the caller's own taste.

    `recent` / `popular` ignore `user_id` and keep their original result shape; only `for_you` adds
    `reason` per novel and `personalized` to the response.
    """
    from services.library_index import library_index

    limit = max(1, min(int(limit or 30), DISCOVER_MAX_LIMIT))
    entries = library_index.get_entries()
    candidates = []
    for entry in entries:
        novel = entry.get("novel") or {}
        if not novel.get("title") or _is_private(novel):
            continue
        candidates.append((entry, novel, _recency_ms(novel)))

    if sort == "for_you":
        return _discover_for_you(entries, candidates, limit, user_id)
    if sort == "popular":
        counts = reader_counter.counts()
        candidates.sort(key=lambda c: (counts.get(c[0]["id"], 0), c[2]), reverse=True)
    else:
        candidates.sort(key=lambda c: c[2], reverse=True)

    novels: List[Dict[str, Any]] = [_summary(entry, novel) for entry, novel, _ in candidates[:limit]]
    return {"novels": novels, "total": len(candidates)}
