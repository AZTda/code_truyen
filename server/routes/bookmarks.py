import logging
from typing import List, Optional, Dict, Any
from fastapi import APIRouter, Depends, HTTPException, Query, Response
from pydantic import BaseModel, Field, ValidationError
from database import db
from services.auth_token import current_user, user_id_of
from services.sync_merge import (
    now_ms, iso_from_ms, as_ms, clamp_future, incoming_wins, changed_since, tombstone_expired,
    same_content, trusted_ts, sync_clock,
)
from services.sync_store import scoped_key, legacy_key, write_owned

logger = logging.getLogger("truyencloud.bookmarks")

router = APIRouter(prefix="/bookmarks", tags=["Bookmarks Sync"])

_BOOKMARK_FIELDS = (
    "novel_id", "chapter_number", "chapter_title", "paragraph_index",
    "selected_text", "note", "color_hex", "created_at",
)


class BookmarkItem(BaseModel):
    id: str = Field(min_length=1, max_length=200)
    novel_id: str = Field(min_length=1, max_length=200)
    chapter_number: int = Field(ge=1, le=2_000_000)
    chapter_title: str = Field(default="", max_length=500)
    paragraph_index: int = Field(ge=0, le=2_000_000)
    selected_text: str = Field(default="", max_length=20_000)
    note: str = Field(default="", max_length=8_000)
    color_hex: str = Field(default="#FFD54F", max_length=20)
    created_at: int = Field(ge=0)
    # Epoch ms; 0 = app cũ không gửi (luôn thua mọi bản có giờ sửa thật).
    # Giờ vượt server + 5 phút bị kẹp lại khi lưu (xem sync_merge.clamp_future).
    updated_at: int = Field(default=0, ge=0)
    # > 0 nghĩa là tombstone: bookmark đã bị xoá lúc deleted_at.
    deleted_at: int = Field(default=0, ge=0)


class SyncBookmarksRequest(BaseModel):
    bookmarks: List[BookmarkItem] = Field(max_length=500)


def _user_rows(uid: str) -> List[Dict[str, Any]]:
    res = db.table("bookmarks").select("*").eq("user_id", uid).execute()
    return [r for r in (res.data or []) if isinstance(r, dict)]


def _to_item(row: Dict[str, Any]) -> Optional[BookmarkItem]:
    try:
        return BookmarkItem(
            id=row.get("client_id") or row.get("id", ""),
            novel_id=row.get("novel_id", ""),
            chapter_number=row.get("chapter_number", 0),
            chapter_title=row.get("chapter_title", ""),
            paragraph_index=row.get("paragraph_index", 0),
            selected_text=row.get("selected_text", ""),
            note=row.get("note", ""),
            color_hex=row.get("color_hex", "#FFD54F"),
            created_at=row.get("created_at", 0),
            updated_at=trusted_ts(as_ms(row.get("updated_at")), row),
            deleted_at=trusted_ts(as_ms(row.get("deleted_at")), row),
        )
    except ValidationError as e:
        logger.warning(f"Bỏ qua bookmark hỏng {row.get('id')}: {e.error_count()} lỗi")
        return None


def _precedence(uid: str):
    """Thứ tự ưu tiên khi nhiều dòng cùng một bookmark: dòng khoá mới thắng dòng khoá cũ."""
    def rank(row: Dict[str, Any]):
        client_id = row.get("client_id")
        return bool(client_id), bool(client_id) and row.get("id") == scoped_key(uid, client_id)
    return rank


@router.get("", response_model=List[BookmarkItem])
async def get_all_bookmarks(
    response: Response,
    since: Optional[int] = Query(None, ge=0, description="Chỉ trả các dòng thay đổi sau mốc này (epoch ms)"),
    auth: dict = Depends(current_user),
):
    """Bookmark còn sống + tombstone < 90 ngày (client tự xoá bản cục bộ theo tombstone)."""
    uid = user_id_of(auth)
    # Con trỏ lấy TRƯỚC khi đọc; mọi lượt ghi sau đó có mốc >= nó (xem SyncClock).
    cursor = sync_clock.cursor()
    current = now_ms()
    response.headers["X-Server-Time-Ms"] = str(cursor)
    # Dòng khoá kiểu cũ (không namespace, không client_id) nhường cho dòng mới
    # cùng id, để client không nhận cùng lúc một bản sống và một tombstone.
    canonical = {}
    for row in sorted(_user_rows(uid), key=_precedence(uid)):
        canonical[row.get("client_id") or row.get("id")] = row
    items = []
    for row in canonical.values():
        deleted_at = as_ms(row.get("deleted_at"))
        # App bản cũ không gửi `since` và không hiểu tombstone (sẽ lưu nó như bookmark
        # sống) — chỉ trả tombstone cho client mới, vốn luôn gửi `since` (0 lần đầu).
        if deleted_at and (since is None or tombstone_expired(row, deleted_at, current)):
            continue
        if not changed_since(row, as_ms(row.get("updated_at")), since):
            continue
        item = _to_item(row)
        if item:
            items.append(item)
    return items


@router.post("/sync")
async def sync_bookmarks(req: SyncBookmarksRequest, auth: dict = Depends(current_user)):
    """Đồng bộ bookmark lên máy chủ: last-write-wins theo updated_at, giữ tombstone."""
    uid = user_id_of(auth)
    received = now_ms()
    now = iso_from_ms(received)
    stamp = sync_clock.stamp()
    stored_by_id = {r.get("id"): r for r in _user_rows(uid)}
    applied, unchanged, stale_ids = 0, 0, []
    to_write, to_write_ids, supersedes = [], [], []
    for bm in req.bookmarks:
        # Khoá có tiền tố độ dài uid: hai người dùng không thể sinh cùng một khoá, và hai thiết bị
        # của một người chọn cùng id cục bộ vẫn ra cùng một dòng.
        key, old_key = scoped_key(uid, bm.id), legacy_key(uid, bm.id)
        stored = stored_by_id.get(key) or stored_by_id.get(old_key)
        # Lúc xoá cũng là một lần sửa: tombstone thiếu updated_at vẫn so theo deleted_at.
        deleted_at = clamp_future(bm.deleted_at, received)
        incoming_ts = max(clamp_future(bm.updated_at, received), deleted_at)
        if stored and not incoming_wins(
            incoming_ts, deleted_at > 0,
            trusted_ts(as_ms(stored.get("updated_at")), stored), as_ms(stored.get("deleted_at")) > 0,
        ):
            stale_ids.append(bm.id)
            continue
        record = {"id": key, "client_id": bm.id, "user_id": uid}
        record.update({f: getattr(bm, f) for f in _BOOKMARK_FIELDS})
        record.update({
            "updated_at": incoming_ts,
            "deleted_at": deleted_at,
            "server_updated_at": stamp,
            "synced_at": now,
        })
        if stored and same_content(stored, record):
            unchanged += 1
            continue
        to_write.append(record)
        to_write_ids.append(bm.id)
        supersedes.append([old_key] if old_key in stored_by_id else [])
        stored_by_id[key] = record  # cùng id lặp lại trong một request so với bản vừa quyết định
    for bookmark_id, written in zip(to_write_ids, write_owned(db.table("bookmarks"), to_write, supersedes)):
        if written:
            applied += 1
        else:
            logger.warning("Từ chối ghi bookmark %s: khoá đang thuộc người dùng khác", bookmark_id)
            stale_ids.append(bookmark_id)
    return {
        "status": "success",
        "count": len(req.bookmarks),
        "applied": applied,
        "unchanged": unchanged,
        "stale_ids": stale_ids,
        "synced_at": now,
        "server_time_ms": sync_clock.cursor(),
    }


@router.delete("/{bookmark_id}")
async def delete_bookmark(bookmark_id: str, auth: dict = Depends(current_user)):
    """Xoá bookmark = ghi tombstone (updated_at = deleted_at = bây giờ). Chỉ chạm dòng của người gọi."""
    uid = user_id_of(auth)
    current = now_ms()
    stamp = sync_clock.stamp()
    key, old_key = scoped_key(uid, bookmark_id), legacy_key(uid, bookmark_id)
    rows = {r.get("id"): r for r in _user_rows(uid)}
    stored = rows.get(key) or rows.get(old_key) or {}
    # Chưa từng lên server: vẫn ghi tombstone (với giá trị giữ chỗ hợp lệ) để
    # một lượt POST cũ tới sau không tạo lại bookmark đã xoá.
    record = {
        "novel_id": "_deleted", "chapter_number": 1, "chapter_title": "",
        "paragraph_index": 0, "selected_text": "", "note": "",
        "color_hex": "#FFD54F", "created_at": 0,
    }
    record.update({f: stored[f] for f in _BOOKMARK_FIELDS if stored.get(f) not in (None, "")})
    record.update({
        "id": key, "client_id": bookmark_id, "user_id": uid,
        "updated_at": current, "deleted_at": current,
        "server_updated_at": stamp, "synced_at": iso_from_ms(current),
    })
    table = db.table("bookmarks")
    if not write_owned(table, [record], [[old_key] if old_key in rows else []])[0]:
        raise HTTPException(status_code=409, detail="Không ghi được tombstone: khoá bookmark đang thuộc người khác.")
    # Dòng của phiên bản chưa namespace (id = id client): lọc theo user_id nên không chạm dòng người khác.
    table.delete().eq("id", bookmark_id).eq("user_id", uid).execute()
    return {"status": "deleted", "id": bookmark_id, "updated_at": current}
