import math
from typing import List, Dict, Any, Optional
from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field
from database import db
from services.auth_token import current_user, user_id_of
from services.sync_merge import (
    now_ms, iso_from_ms, as_ms, clamp_future, incoming_wins, changed_since, tombstone_expired,
    same_content, trusted_ts, sync_clock,
)
from services.sync_store import scoped_key, legacy_key, write_owned

router = APIRouter(prefix="/vocabulary", tags=["Vocabulary & FSRS Sync"])

MAX_WORDS_PER_SYNC = 500
MAX_LOGS_PER_SYNC = 2_000


class SyncVocabularyRequest(BaseModel):
    # Giới hạn kiểm tra trong handler để trả 413 kèm thông báo rõ ràng.
    words: List[Dict[str, Any]] = Field(default_factory=list)
    review_logs: List[Dict[str, Any]] = Field(default_factory=list)


class VocabularySyncResponse(BaseModel):
    words: List[Dict[str, Any]]
    review_logs: List[Dict[str, Any]]
    synced_at: str
    server_time_ms: int = 0


def _user_rows(table: str, uid: str) -> List[Dict[str, Any]]:
    res = db.table(table).select("*").eq("user_id", uid).execute()
    return [r for r in (res.data or []) if isinstance(r, dict)]


def _row_data(row: Dict[str, Any]) -> Dict[str, Any]:
    data = row.get("data")
    return data if isinstance(data, dict) else row


def _word_deleted_at(row: Dict[str, Any]) -> int:
    return as_ms(row.get("deleted_at")) or as_ms(_row_data(row).get("deletedAt"))


def _has_non_finite(value: Any) -> bool:
    """Có số NaN/Infinity ở bất kỳ đâu trong cấu trúc JSON (duyệt bằng stack, không đệ quy)."""
    stack = [value]
    while stack:
        item = stack.pop()
        if isinstance(item, float):
            if not math.isfinite(item):
                return True
        elif isinstance(item, dict):
            stack.extend(item.values())
        elif isinstance(item, list):
            stack.extend(item)
    return False


def _scalar_id(raw: Any) -> str:
    """Id dạng chuỗi/số -> chuỗi; rỗng nếu thiếu. Ném TypeError với giá trị lồng nhau (dict/list)."""
    if raw is None or raw == "":
        return ""
    if isinstance(raw, (dict, list)):
        raise TypeError("id phải là chuỗi hoặc số")
    return str(raw)


def _validate_payload(req: SyncVocabularyRequest) -> None:
    """Kiểm tra TOÀN BỘ payload trước khi ghi dòng đầu tiên: một từ hỏng không được để lại request ghi dở."""
    for kind, items in (("words", req.words), ("review_logs", req.review_logs)):
        for index, item in enumerate(items):
            if _has_non_finite(item):
                raise HTTPException(
                    status_code=422,
                    detail=f"{kind}[{index}] chứa số NaN/Infinity; không dòng nào được lưu.",
                )
            raw_id = (item.get("id") or item.get("word")) if kind == "words" else item.get("wordId")
            try:
                _scalar_id(raw_id)
            except TypeError:
                raise HTTPException(
                    status_code=422,
                    detail=f"{kind}[{index}] có id không hợp lệ (cần chuỗi hoặc số); không dòng nào được lưu.",
                )


@router.get("/sync", response_model=VocabularySyncResponse)
async def get_vocabulary_sync(
    since: Optional[int] = Query(None, ge=0, description="Chỉ trả thay đổi sau mốc này (epoch ms, lấy từ server_time_ms)"),
    auth: dict = Depends(current_user),
):
    """Lấy từ vựng (kèm tombstone < 90 ngày) và lịch sử ôn tập FSRS của người dùng."""
    uid = user_id_of(auth)
    # Chụp con trỏ TRƯỚC khi đọc: bản ghi tới sau đó có mốc >= con trỏ nên nằm trong lượt sau (xem SyncClock).
    cursor = sync_clock.cursor()
    current = now_ms()

    words = []
    for row in _user_rows("saved_words", uid):
        deleted_at = _word_deleted_at(row)
        # App bản cũ không gửi `since` và không hiểu deletedAt — chỉ client mới nhận tombstone.
        if deleted_at and (since is None or tombstone_expired(row, deleted_at, current)):
            continue
        if changed_since(row, as_ms(row.get("updated_at")), since):
            words.append(_row_data(row))

    logs, seen = [], set()
    for row in _user_rows("review_logs", uid):
        data = _row_data(row)
        # Dòng cũ khoá theo id tự tăng của client nên có thể trùng bản khoá mới.
        dedupe_key = (str(data.get("wordId")), as_ms(data.get("reviewedAt")))
        if dedupe_key in seen or not changed_since(row, as_ms(row.get("reviewed_at")), since):
            continue
        seen.add(dedupe_key)
        logs.append(data)

    return VocabularySyncResponse(
        words=words,
        review_logs=logs,
        synced_at=iso_from_ms(current),
        server_time_ms=cursor,
    )


def _plan_words(req: SyncVocabularyRequest, uid: str, received: int, stamp: int, now: str):
    """Quyết định (chưa ghi gì) từng từ: bản ghi cần ghi, khoá cũ cần di trú, id bị từ chối vì cũ hơn."""
    stored_words = {r.get("id"): r for r in _user_rows("saved_words", uid)}
    records, supersedes, stale_ids, counted = [], [], [], 0
    for word_dict in req.words:
        w_id = _scalar_id(word_dict.get("id") or word_dict.get("word"))
        if not w_id:
            continue
        key, old_key = scoped_key(uid, w_id), legacy_key(uid, w_id)
        stored = stored_words.get(key) or stored_words.get(old_key)
        deleted_at = clamp_future(word_dict.get("deletedAt"), received)
        updated_at = clamp_future(word_dict.get("updatedAt"), received)
        incoming_ts = max(updated_at, deleted_at)
        if stored and not incoming_wins(
            incoming_ts, deleted_at > 0,
            trusted_ts(as_ms(stored.get("updated_at")), stored), _word_deleted_at(stored) > 0,
        ):
            stale_ids.append(w_id)
            continue
        data = dict(word_dict)
        if deleted_at and stored:
            # Tombstone có thể chỉ mang id/deletedAt; giữ lại các trường cũ để
            # app đời cũ (bắt buộc có `word`) vẫn đọc được.
            data = {**_row_data(stored), **word_dict}
        # Client so sánh theo CHÍNH các trường này khi kéo về, nên giờ bị kẹp phải nằm cả trong bản ghi.
        for field, clamped in (("updatedAt", updated_at), ("deletedAt", deleted_at)):
            if as_ms(data.get(field)) > clamped:
                data[field] = clamped
        data.setdefault("id", w_id)
        data.setdefault("word", w_id)
        record = {
            "id": key,
            "user_id": uid,
            "word_id": w_id,
            "word": data.get("word", ""),
            "data": data,
            "updated_at": incoming_ts,
            "deleted_at": deleted_at,
            "server_updated_at": stamp,
            "synced_at": now,
        }
        counted += 1
        if stored and same_content(stored, record):
            continue
        records.append(record)
        supersedes.append([old_key] if old_key in stored_words else [])
        stored_words[key] = record
    return records, supersedes, stale_ids, counted


def _plan_logs(req: SyncVocabularyRequest, uid: str, stamp: int, now: str):
    # Khoá log theo (wordId, reviewedAt): `id` tự tăng của client trùng nhau giữa
    # các thiết bị. Log là bất biến nên log đã có thì bỏ qua, không ghi lại.
    known_logs = {r.get("id") for r in _user_rows("review_logs", uid)}
    records, log_count, skipped = [], 0, 0
    for log_dict in req.review_logs:
        word_id = _scalar_id(log_dict.get("wordId"))
        reviewed_at = as_ms(log_dict.get("reviewedAt"))
        if not word_id or not reviewed_at:
            skipped += 1
            continue
        item = f"{word_id}_{reviewed_at}"
        key = scoped_key(uid, item)
        log_count += 1
        if key in known_logs or legacy_key(uid, item) in known_logs:
            continue
        records.append({
            "id": key,
            "user_id": uid,
            "word_id": word_id,
            "data": log_dict,
            "reviewed_at": reviewed_at,
            "server_updated_at": stamp,
            "synced_at": now,
        })
        known_logs.add(key)
    return records, log_count, skipped


@router.post("/sync")
async def sync_vocabulary(req: SyncVocabularyRequest, auth: dict = Depends(current_user)):
    """Hợp nhất từ vựng theo updatedAt (last-write-wins, giữ tombstone) và lưu log ôn tập."""
    if len(req.words) > MAX_WORDS_PER_SYNC or len(req.review_logs) > MAX_LOGS_PER_SYNC:
        raise HTTPException(
            status_code=413,
            detail=(
                f"Quá nhiều dữ liệu trong một lần đồng bộ: nhận {len(req.words)} từ và "
                f"{len(req.review_logs)} log; tối đa {MAX_WORDS_PER_SYNC} từ và "
                f"{MAX_LOGS_PER_SYNC} log mỗi request. Hãy chia nhỏ rồi gửi lại."
            ),
        )
    _validate_payload(req)
    uid = user_id_of(auth)
    received = now_ms()
    now = iso_from_ms(received)
    stamp = sync_clock.stamp()

    word_records, word_supersedes, stale_ids, saved_count = _plan_words(req, uid, received, stamp, now)
    log_records, log_count, skipped_logs = _plan_logs(req, uid, stamp, now)

    for record, written in zip(word_records, write_owned(db.table("saved_words"), word_records, word_supersedes)):
        if not written:  # khoá đang thuộc người khác: không ghi đè, báo là cũ hơn
            stale_ids.append(record["word_id"])
            saved_count -= 1
    write_owned(db.table("review_logs"), log_records)

    return {
        "status": "success",
        "words_synced": saved_count,
        "words_stale": len(stale_ids),
        "stale_word_ids": stale_ids,
        "logs_synced": log_count,
        "logs_skipped": skipped_logs,
        "synced_at": now,
        "server_time_ms": sync_clock.cursor(),
    }
