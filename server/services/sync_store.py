"""Khoá lưu trữ không mơ hồ và phép ghi có kiểm tra chủ cho các bảng đồng bộ theo người dùng.

Khoá kiểu cũ `f"{uid}_{id}"` mơ hồ: người dùng `alice` gửi id `bob_abc` ra khoá `user_alice_bob_abc`,
trùng khoá của `alice_bob` với id `abc`. Khoá mới có độ dài uid đứng trước nên không thể trùng giữa hai
người dùng; dòng kiểu cũ vẫn đọc được (theo user_id) và được di trú sang khoá mới ở lần ghi kế tiếp.
"""

import logging
from typing import Any, Dict, List, Optional, Sequence

logger = logging.getLogger("truyencloud.sync_store")


def scoped_key(uid: str, item_id: str) -> str:
    """Khoá của (uid, item_id); hai cặp khác nhau không bao giờ cho cùng một khoá."""
    return f"{len(uid)}:{uid}:{item_id}"


def legacy_key(uid: str, item_id: str) -> str:
    """Khoá kiểu cũ (mơ hồ) của bookmark / từ vựng / log ôn tập."""
    return f"{uid}_{item_id}"


def legacy_progress_key(uid: str, novel_id: str) -> str:
    return f"progress_{uid}_{novel_id}"


def write_owned(
    table: Any,
    records: Sequence[Dict[str, Any]],
    supersedes: Optional[Sequence[Sequence[str]]] = None,
    **upsert_kwargs: Any,
) -> List[bool]:
    """Ghi các dòng chỉ khi pkey chưa có hoặc đang cùng chủ (`user_id`); trả về từng dòng đã ghi hay chưa.

    Bảng SQLite làm việc này nguyên tử trong một giao dịch. Bảng khác (Supabase, bộ giả của test) đi
    đường dự phòng đọc-rồi-ghi, `upsert_kwargs` (vd. on_conflict) chuyển thẳng cho `upsert` của chúng.
    """
    native = getattr(table, "upsert_owned_many", None)
    if callable(native):
        return native(records, supersedes)
    older = supersedes if supersedes is not None else [() for _ in records]
    return [_generic_upsert(table, record, old, upsert_kwargs) for record, old in zip(records, older)]


def _generic_upsert(table: Any, record: Dict[str, Any], old_keys: Sequence[str], upsert_kwargs: Dict[str, Any]) -> bool:
    try:
        found = table.select("*").eq("id", record["id"]).execute().data
    except Exception:
        found = None
    rows = found if isinstance(found, list) else ([found] if isinstance(found, dict) else [])
    if any(isinstance(row, dict) and row.get("user_id") != record.get("user_id") for row in rows):
        return False
    table.upsert(record, **upsert_kwargs).execute()
    for old in old_keys:
        try:
            table.delete().eq("id", old).eq("user_id", record.get("user_id")).execute()
        except Exception:
            logger.debug("Không xoá được khoá cũ %s", old)
    return True
