"""Contract tests for last-write-wins progress, bookmark and vocabulary sync."""

import pytest
from fastapi.testclient import TestClient

from database import db, SqliteBackedTable
from server import app
from services.auth_token import issue_token
from services.storage_service import storage_service
from services.sync_merge import TOMBSTONE_TTL_MS, iso_from_ms, now_ms

client = TestClient(app)
H = {"Authorization": f"Bearer {issue_token('sync_contract_user', 'sync')}"}


@pytest.fixture(autouse=True)
def isolated_db(tmp_path, monkeypatch):
    tables = {}

    def table(name):
        if name not in tables:
            tables[name] = SqliteBackedTable(name, db_path=str(tmp_path / "sync.db"))
        return tables[name]

    monkeypatch.setattr(db, "table", table)
    monkeypatch.setattr(storage_service, "get_novel_info", lambda novel_id: None)
    return table


def _bm(bid="bm1", **kw):
    item = {"id": bid, "novel_id": "n1", "chapter_number": 2, "paragraph_index": 3,
            "created_at": 1, "note": "a"}
    item.update(kw)
    return item


# ---------------------------------------------------------------- progress

def test_progress_last_write_wins_by_client_time():
    ok = client.post("/v1/progress/sync", headers=H, json={
        "novel_id": "n1", "current_chapter": 5, "client_updated_at": 2_000_000})
    assert ok.json()["status"] == "success"
    assert ok.json()["updated_at_ms"] == 2_000_000

    stale = client.post("/v1/progress/sync", headers=H, json={
        "novel_id": "n1", "current_chapter": 3, "client_updated_at": 1_000_000})
    assert stale.status_code == 200
    assert stale.json()["status"] == "stale"
    assert stale.json()["progress"]["current_chapter"] == 5

    items = client.get("/v1/progress", headers=H).json()
    assert items[0]["current_chapter"] == 5
    assert items[0]["updated_at_ms"] == 2_000_000
    assert items[0]["last_read_at"] == iso_from_ms(2_000_000)

    newer = client.post("/v1/progress/sync", headers=H, json={
        "novel_id": "n1", "current_chapter": 7, "client_updated_at": 3_000_000})
    assert newer.json()["status"] == "success"
    assert client.get("/v1/progress/n1", headers=H).json()["current_chapter"] == 7


def test_progress_legacy_client_without_timestamp_still_syncs():
    before = now_ms()
    client.post("/v1/progress/sync", headers=H, json={"novel_id": "n2", "current_chapter": 4})
    row = client.get("/v1/progress/n2", headers=H).json()
    assert row["current_chapter"] == 4
    assert row["updated_at_ms"] >= before


def test_progress_list_carries_novel_metadata(monkeypatch):
    info = {"id": "known", "title": "Phàm Nhân", "title_vi": "Pham Nhan", "author": "A",
            "cover_url": "c", "total_chapters": 12, "source_id": "69shuba",
            "source_url": "https://x", "description": "d", "status": "ongoing"}
    monkeypatch.setattr(storage_service, "get_novel_info",
                        lambda novel_id: info if novel_id == "known" else None)
    for nid in ("known", "unknown"):
        client.post("/v1/progress/sync", headers=H, json={"novel_id": nid, "current_chapter": 1})
    by_id = {it["novel_id"]: it for it in client.get("/v1/progress", headers=H).json()}
    assert {k: by_id["known"]["novel"][k] for k in info} == info
    assert by_id["unknown"]["novel"] is None


# --------------------------------------------------------------- bookmarks

def test_bookmark_older_write_is_ignored_and_tombstone_wins_ties():
    client.post("/v1/bookmarks/sync", headers=H, json={"bookmarks": [_bm(updated_at=100, note="new")]})
    res = client.post("/v1/bookmarks/sync", headers=H, json={"bookmarks": [_bm(updated_at=50, note="old")]})
    assert res.json()["stale_ids"] == ["bm1"]
    assert client.get("/v1/bookmarks?since=0", headers=H).json()[0]["note"] == "new"

    client.post("/v1/bookmarks/sync", headers=H,
                json={"bookmarks": [_bm(updated_at=100, deleted_at=100)]})
    res = client.post("/v1/bookmarks/sync", headers=H, json={"bookmarks": [_bm(updated_at=100)]})
    assert res.json()["stale_ids"] == ["bm1"]
    row = client.get("/v1/bookmarks?since=0", headers=H).json()[0]
    assert row["deleted_at"] == 100


def test_bookmark_delete_writes_tombstone_that_legacy_clients_cannot_undo():
    client.post("/v1/bookmarks/sync", headers=H, json={"bookmarks": [_bm()]})
    assert client.delete("/v1/bookmarks/bm1", headers=H).json()["status"] == "deleted"
    # App cũ gửi lại bản cục bộ không có updated_at -> không được hồi sinh.
    client.post("/v1/bookmarks/sync", headers=H, json={"bookmarks": [_bm()]})
    rows = client.get("/v1/bookmarks?since=0", headers=H).json()
    assert len(rows) == 1 and rows[0]["deleted_at"] > 0
    assert rows[0]["updated_at"] == rows[0]["deleted_at"]
    assert rows[0]["novel_id"] == "n1"


def test_bookmark_delete_of_unknown_id_still_returns_valid_tombstone():
    client.delete("/v1/bookmarks/never_synced", headers=H)
    rows = client.get("/v1/bookmarks?since=0", headers=H).json()
    assert rows[0]["id"] == "never_synced" and rows[0]["deleted_at"] > 0


def test_bookmark_since_returns_only_changes(isolated_db):
    first = client.post("/v1/bookmarks/sync", headers=H,
                        json={"bookmarks": [_bm("a", updated_at=10), _bm("b", updated_at=10)]})
    cursor = first.json()["server_time_ms"]
    # Gửi lại y hệt không được tính là thay đổi mới.
    again = client.post("/v1/bookmarks/sync", headers=H,
                        json={"bookmarks": [_bm("a", updated_at=10), _bm("b", updated_at=10)]})
    assert again.json()["unchanged"] == 2
    assert client.get(f"/v1/bookmarks?since={cursor}", headers=H).json() == []

    client.post("/v1/bookmarks/sync", headers=H, json={"bookmarks": [_bm("b", updated_at=cursor + 5)]})
    changed = client.get(f"/v1/bookmarks?since={cursor}", headers=H)
    assert [b["id"] for b in changed.json()] == ["b"]
    assert int(changed.headers["X-Server-Time-Ms"]) >= cursor


def test_bookmark_old_tombstones_are_not_returned(isolated_db):
    old = now_ms() - TOMBSTONE_TTL_MS - 1000
    isolated_db("bookmarks").upsert({
        "id": "sync_contract_user_gone", "client_id": "gone", "user_id": "sync_contract_user",
        "novel_id": "n1", "chapter_number": 1, "paragraph_index": 0, "created_at": 1,
        "updated_at": old, "deleted_at": old, "server_updated_at": old,
    })
    assert client.get("/v1/bookmarks?since=0", headers=H).json() == []


# -------------------------------------------------------------- vocabulary

def _word(wid="w1", updated=100, **kw):
    word = {"id": wid, "word": wid, "contextMeaningVi": "nghĩa", "updatedAt": updated}
    word.update(kw)
    return word


def test_vocabulary_merge_keeps_newest_and_tombstone_wins_ties():
    client.post("/v1/vocabulary/sync", headers=H, json={"words": [_word(updated=200, reps=5)]})
    res = client.post("/v1/vocabulary/sync", headers=H, json={"words": [_word(updated=100, reps=1)]})
    assert res.json()["words_stale"] == 1
    assert client.get("/v1/vocabulary/sync?since=0", headers=H).json()["words"][0]["reps"] == 5

    # Tombstone chỉ mang id/deletedAt vẫn giữ các trường cũ (app cũ bắt buộc có `word`).
    client.post("/v1/vocabulary/sync", headers=H,
                json={"words": [{"id": "w1", "updatedAt": 200, "deletedAt": 200}]})
    res = client.post("/v1/vocabulary/sync", headers=H, json={"words": [_word(updated=200)]})
    assert res.json()["stale_word_ids"] == ["w1"]
    word = client.get("/v1/vocabulary/sync?since=0", headers=H).json()["words"][0]
    assert word["deletedAt"] == 200 and word["word"] == "w1" and word["reps"] == 5


def test_review_logs_ignore_client_autoincrement_id():
    logs = [
        {"id": 1, "wordId": "w1", "reviewedAt": 1000, "rating": 3},
        {"id": 1, "wordId": "w2", "reviewedAt": 2000, "rating": 4},  # thiết bị khác, cùng id
    ]
    client.post("/v1/vocabulary/sync", headers=H, json={"review_logs": logs})
    # Máy khác gửi lại log thứ nhất với id cục bộ khác -> không sinh bản trùng.
    client.post("/v1/vocabulary/sync", headers=H,
                json={"review_logs": [{"id": 77, "wordId": "w1", "reviewedAt": 1000, "rating": 3}]})
    got = client.get("/v1/vocabulary/sync?since=0", headers=H).json()["review_logs"]
    assert sorted((g["wordId"], g["reviewedAt"]) for g in got) == [("w1", 1000), ("w2", 2000)]


def test_vocabulary_since_uses_server_time_cursor():
    first = client.post("/v1/vocabulary/sync", headers=H, json={
        "words": [_word("w1"), _word("w2")],
        "review_logs": [{"id": 1, "wordId": "w1", "reviewedAt": 500, "rating": 3}]})
    cursor = client.get("/v1/vocabulary/sync?since=0", headers=H).json()["server_time_ms"]
    assert cursor >= first.json()["server_time_ms"]
    empty = client.get(f"/v1/vocabulary/sync?since={cursor}", headers=H).json()
    assert empty["words"] == [] and empty["review_logs"] == []

    # Sửa offline (updatedAt cũ hơn cursor) tới server sau cursor vẫn phải xuống máy khác.
    client.post("/v1/vocabulary/sync", headers=H, json={
        "words": [_word("w2", updated=150)],
        "review_logs": [{"id": 9, "wordId": "w2", "reviewedAt": 600, "rating": 2}]})
    delta = client.get(f"/v1/vocabulary/sync?since={cursor}", headers=H).json()
    assert [w["id"] for w in delta["words"]] == ["w2"]
    assert [log["reviewedAt"] for log in delta["review_logs"]] == [600]


def test_vocabulary_oversized_payload_gets_clear_413():
    res = client.post("/v1/vocabulary/sync", headers=H,
                      json={"words": [_word(f"w{i}") for i in range(501)]})
    assert res.status_code == 413
    assert "500" in res.json()["detail"] and "chia nhỏ" in res.json()["detail"]


def test_legacy_clients_without_since_never_receive_tombstones():
    """App bản cũ không gửi `since` và không hiểu tombstone — không được nhận chúng."""
    client.post("/v1/bookmarks/sync", headers=H, json={"bookmarks": [
        {"id": "legacy1", "novel_id": "n1", "chapter_number": 1, "paragraph_index": 0,
         "created_at": 1, "updated_at": 10, "deleted_at": 10},
    ]})
    assert client.get("/v1/bookmarks", headers=H).json() == []
    assert any(b["id"] == "legacy1" for b in client.get("/v1/bookmarks?since=0", headers=H).json())

    client.post("/v1/vocabulary/sync", headers=H, json={"words": [
        {"id": "w_legacy", "word": "legacy", "updatedAt": 20, "deletedAt": 20},
    ], "review_logs": []})
    assert all(w.get("id") != "w_legacy" for w in client.get("/v1/vocabulary/sync", headers=H).json()["words"])
    assert any(w.get("id") == "w_legacy" for w in client.get("/v1/vocabulary/sync?since=0", headers=H).json()["words"])
