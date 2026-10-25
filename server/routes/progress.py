from typing import Optional, List, Dict, Any
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from database import db
from services.auth_token import current_user, user_id_of
from services.novel_catalog import REMOVED_SHELF, lookup_novel_summary, reader_counter
from services.sync_merge import now_ms, iso_from_ms, ms_from_iso, as_ms, clamp_future, trusted_ts
from services.sync_store import scoped_key, legacy_progress_key, write_owned

router = APIRouter(prefix="/progress", tags=["Reading Progress"])
PROGRESS_TABLE = "reading_progress_backend"


class SyncProgressRequest(BaseModel):
    novel_id: str
    current_chapter: int
    # NaN/Infinity vào được JSON của Python nhưng làm mọi lượt GET danh sách sau đó trả 500.
    scroll_position: float = Field(default=0.0, allow_inf_nan=False)
    shelf_name: str = "reading"
    is_favorite: bool = False
    # Giờ sửa trên thiết bị (epoch ms). App cũ không gửi -> dùng giờ server nhận.
    # Giờ vượt server + 5 phút bị kẹp lại (xem sync_merge.clamp_future).
    client_updated_at: Optional[int] = Field(default=None, ge=0)


class NovelSummary(BaseModel):
    id: str = ""
    title: str = ""
    title_vi: str = ""
    author: str = ""
    cover_url: str = ""
    total_chapters: int = 0
    source_id: str = ""
    source_url: str = ""
    description: str = ""
    status: str = ""
    translated_chapters: int = 0
    overnight_enabled: bool = False
    category: str = ""


class ProgressResponse(BaseModel):
    novel_id: str
    current_chapter: int = 0
    scroll_position: float = 0.0
    shelf_name: str = "reading"
    is_favorite: bool = False
    last_read_at: Optional[str] = None
    updated_at_ms: int = 0
    novel: Optional[NovelSummary] = None


def _row_updated_ms(row: Dict[str, Any]) -> int:
    raw = as_ms(row.get("updated_at_ms")) or ms_from_iso(row.get("last_read_at"))
    # Dòng cũ mang giờ tương lai (trước khi có phép kẹp) không được chặn mãi các lần ghi sau.
    return trusted_ts(raw, row)


def _load_row(uid: str, novel_id: str) -> Optional[Dict[str, Any]]:
    res = (
        db.table(PROGRESS_TABLE)
        .select("*")
        .eq("user_id", uid)
        .eq("novel_id", novel_id)
        .execute()
    )
    rows = res.data or []
    return rows[0] if isinstance(rows, list) and rows else None


def _to_response(novel_id: str, row: Dict[str, Any]) -> ProgressResponse:
    return ProgressResponse(
        novel_id=row.get("novel_id") or novel_id,
        current_chapter=row.get("current_chapter", 0),
        scroll_position=row.get("scroll_position", 0.0),
        shelf_name=row.get("shelf_name", "reading"),
        is_favorite=row.get("is_favorite", False),
        last_read_at=row.get("last_read_at"),
        updated_at_ms=_row_updated_ms(row),
        novel=lookup_novel_summary(row.get("novel_id") or novel_id),
    )


@router.post("/sync")
async def sync_progress(req: SyncProgressRequest, auth: dict = Depends(current_user)):
    """Đồng bộ tiến độ đọc từ client lên máy chủ (last-write-wins theo giờ client)."""
    uid = user_id_of(auth)
    received_ms = now_ms()
    now = iso_from_ms(received_ms)
    updated_ms = clamp_future(req.client_updated_at, received_ms) if req.client_updated_at else received_ms

    # Không có await giữa đọc và ghi nên cặp kiểm tra/ghi này không bị xen ngang
    # bởi request khác trên cùng event loop.
    stored = _load_row(uid, req.novel_id)
    if stored and _row_updated_ms(stored) > updated_ms:
        return {
            "status": "stale",
            "synced_at": now,
            "updated_at_ms": _row_updated_ms(stored),
            "progress": _to_response(req.novel_id, stored).model_dump(exclude={"novel"}),
        }

    key = scoped_key(uid, req.novel_id)
    record = {
        "id": key,
        "novel_id": req.novel_id,
        # Lay tu TOKEN, khong lay tu body — client khong duoc tu xung la nguoi khac
        "user_id": uid,
        "current_chapter": req.current_chapter,
        "scroll_position": req.scroll_position,
        "shelf_name": req.shelf_name,
        "is_favorite": req.is_favorite,
        "last_read_at": iso_from_ms(updated_ms),
        "updated_at_ms": updated_ms,
        "synced_at": now,
    }
    # Dòng khoá kiểu cũ của chính người này được di trú (xoá) cùng giao dịch ghi dòng mới.
    older = {legacy_progress_key(uid, req.novel_id), str((stored or {}).get("id") or "")} - {key, ""}
    if not write_owned(db.table(PROGRESS_TABLE), [record], [sorted(older)], on_conflict="user_id,novel_id")[0]:
        raise HTTPException(status_code=409, detail="Không ghi được tiến độ: khoá đang thuộc người dùng khác.")
    # Tủ sách của người này vừa đổi (thêm hoặc gỡ truyện): để "Dành cho bạn" thấy ngay ở lượt đọc kế tiếp thay vì
    # chờ hết bộ đệm 60 giây. Lật chương không đổi tủ nên không làm mất bộ đệm.
    if stored is None or (stored.get("shelf_name") == REMOVED_SHELF) != (req.shelf_name == REMOVED_SHELF):
        reader_counter.invalidate()
    return {"status": "success", "synced_at": now, "updated_at_ms": updated_ms}


@router.get("", response_model=List[ProgressResponse])
async def get_all_progress(auth: dict = Depends(current_user)):
    """Lấy toàn bộ tiến độ đọc của user kèm metadata truyện để dựng lại tủ sách."""
    uid = user_id_of(auth)
    res = (
        db.table(PROGRESS_TABLE)
        .select("*")
        .eq("user_id", uid)
        .execute()
    )
    return [_to_response(row.get("novel_id", ""), row) for row in (res.data or [])]


@router.get("/{novel_id}", response_model=ProgressResponse)
async def get_progress(novel_id: str, auth: dict = Depends(current_user)):
    """Lấy tiến độ đọc đã lưu trên máy chủ cho một truyện."""
    stored = _load_row(user_id_of(auth), novel_id)
    if stored:
        return _to_response(novel_id, stored)
    return ProgressResponse(novel_id=novel_id)
