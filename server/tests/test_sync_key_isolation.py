"""Vá lỗi đồng bộ: khoá lưu trữ `{uid}_{id}` mơ hồ cho phép người này ghi đè / chiếm / xoá dòng của người kia.

Mỗi test dựng kho riêng trong tmp_path (tests/_isolation.py) — không chạm truyencloud.db thật.
"""

from database import db
from services.sync_merge import iso_from_ms
from services.sync_store import legacy_key, legacy_progress_key, scoped_key
from tests._sync_support import bearer, bm, client, pull_bookmarks, pull_vocab, push_bookmarks, push_words

# `alice` + "_" + "bob_abc" == "alice_bob" + "_" + "abc": hai tài khoản có tên tiền tố của nhau.
ATTACKER = bearer("user_alice", "alice")
VICTIM = bearer("user_alice_bob", "alice_bob")


def test_bookmark_prefix_collision_cannot_take_over_victim_row():
    push_bookmarks(VICTIM, bm("abc", note="VICTIM PRIVATE NOTE", updated_at=1000))
    res = push_bookmarks(ATTACKER, bm("bob_abc", note="attacker", updated_at=2000))
    assert res.json()["applied"] == 1  # id lạ của kẻ tấn công vẫn lưu được — nhưng thành dòng RIÊNG

    assert [b["note"] for b in pull_bookmarks(VICTIM).json()] == ["VICTIM PRIVATE NOTE"]
    assert [(b["id"], b["note"]) for b in pull_bookmarks(ATTACKER).json()] == [("bob_abc", "attacker")]


def test_bookmark_delete_of_colliding_id_never_tombstones_victim():
    push_bookmarks(VICTIM, bm("abc", note="keep me", updated_at=1000))
    assert client.delete("/v1/bookmarks/bob_abc", headers=ATTACKER).status_code == 200
    rows = pull_bookmarks(VICTIM).json()
    assert [(b["note"], b["deleted_at"]) for b in rows] == [("keep me", 0)]


def test_progress_prefix_collision_keeps_both_rows():
    client.post("/v1/progress/sync", headers=VICTIM, json={"novel_id": "x", "current_chapter": 99, "client_updated_at": 5000})
    client.post("/v1/progress/sync", headers=ATTACKER, json={"novel_id": "bob_x", "current_chapter": 1, "client_updated_at": 6000})
    victim = client.get("/v1/progress", headers=VICTIM).json()
    assert [(p["novel_id"], p["current_chapter"]) for p in victim] == [("x", 99)]
    attacker = client.get("/v1/progress", headers=ATTACKER).json()
    assert [(p["novel_id"], p["current_chapter"]) for p in attacker] == [("bob_x", 1)]


def test_vocabulary_word_and_review_log_collisions_keep_victim_data():
    push_words(VICTIM, [{"id": "hello", "word": "hello", "meaning": "VICTIM", "updatedAt": 5000}],
               [{"wordId": "w1", "reviewedAt": 1_700_000_000_000, "rating": 3, "secret": "victim"}])
    push_words(ATTACKER, [{"id": "bob_hello", "word": "bob_hello", "meaning": "ATTACKER", "updatedAt": 6000}],
               [{"wordId": "bob_w1", "reviewedAt": 1_700_000_000_000, "rating": 1, "secret": "attacker"}])

    victim = pull_vocab(VICTIM)
    assert [w["meaning"] for w in victim["words"]] == ["VICTIM"]
    assert [log["secret"] for log in victim["review_logs"]] == ["victim"]
    attacker = pull_vocab(ATTACKER)
    assert [w["meaning"] for w in attacker["words"]] == ["ATTACKER"]
    assert [log["secret"] for log in attacker["review_logs"]] == ["attacker"]


def test_owner_check_refuses_to_overwrite_a_row_that_belongs_to_someone_else():
    """Phòng thủ chiều sâu: dù có dòng lạ nằm đúng khoá, ghi KHÔNG được đổi chủ."""
    key = scoped_key("user_alice", "zzz")
    foreign = {"id": key, "client_id": "zzz", "user_id": "user_other", "novel_id": "n1", "chapter_number": 1,
               "paragraph_index": 0, "created_at": 1, "note": "theirs", "updated_at": 10, "deleted_at": 0}
    db.table("bookmarks").upsert(foreign)

    res = push_bookmarks(ATTACKER, bm("zzz", note="mine", updated_at=5000)).json()
    assert res["applied"] == 0 and res["stale_ids"] == ["zzz"]
    stored = db.table("bookmarks").select("*").eq("id", key).single().execute().data
    assert (stored["user_id"], stored["note"]) == ("user_other", "theirs")


# --------------------------------------------------------------------------- khoá kiểu cũ vẫn đọc được, được di trú

def test_legacy_bookmark_row_is_read_then_migrated_without_duplicates():
    uid = "user_legacy"
    legacy = bm("b1", note="old", updated_at=100, deleted_at=0)
    legacy.update(id=legacy_key(uid, "b1"), client_id="b1", user_id=uid, server_updated_at=100,
                  synced_at=iso_from_ms(100))
    db.table("bookmarks").upsert(legacy)
    headers = bearer(uid, "legacy")
    assert [b["note"] for b in pull_bookmarks(headers).json()] == ["old"]

    assert push_bookmarks(headers, bm("b1", note="new", updated_at=200)).json()["applied"] == 1
    assert [b["note"] for b in pull_bookmarks(headers).json()] == ["new"]
    rows = db.table("bookmarks").select("*").eq("user_id", uid).execute().data
    assert [r["id"] for r in rows] == [scoped_key(uid, "b1")]


def test_legacy_progress_row_is_read_then_migrated_without_duplicates():
    uid = "user_legacy"
    db.table("reading_progress_backend").upsert({
        "id": legacy_progress_key(uid, "n1"), "novel_id": "n1", "user_id": uid, "current_chapter": 3,
        "updated_at_ms": 100, "last_read_at": iso_from_ms(100), "synced_at": iso_from_ms(100)})
    headers = bearer(uid, "legacy")
    assert client.get("/v1/progress/n1", headers=headers).json()["current_chapter"] == 3

    res = client.post("/v1/progress/sync", headers=headers, json={"novel_id": "n1", "current_chapter": 8, "client_updated_at": 200})
    assert res.json()["status"] == "success"
    assert [p["current_chapter"] for p in client.get("/v1/progress", headers=headers).json()] == [8]
    rows = db.table("reading_progress_backend").select("*").eq("user_id", uid).execute().data
    assert [r["id"] for r in rows] == [scoped_key(uid, "n1")]


def test_legacy_vocabulary_rows_are_read_then_migrated_without_duplicates():
    uid = "user_legacy"
    word = {"id": "hello", "word": "hello", "meaning": "old", "updatedAt": 100}
    db.table("saved_words").upsert({
        "id": legacy_key(uid, "hello"), "user_id": uid, "word_id": "hello", "word": "hello", "data": word,
        "updated_at": 100, "deleted_at": 0, "server_updated_at": 100, "synced_at": iso_from_ms(100)})
    log = {"wordId": "hello", "reviewedAt": 5000, "rating": 3}
    db.table("review_logs").upsert({
        "id": legacy_key(uid, "hello_5000"), "user_id": uid, "word_id": "hello", "data": log,
        "reviewed_at": 5000, "server_updated_at": 100, "synced_at": iso_from_ms(100)})
    headers = bearer(uid, "legacy")
    assert [w["meaning"] for w in pull_vocab(headers)["words"]] == ["old"]

    res = push_words(headers, [dict(word, meaning="new", updatedAt=200)], [log])
    assert res.json()["words_synced"] == 1
    data = pull_vocab(headers)
    assert [w["meaning"] for w in data["words"]] == ["new"]
    assert len(data["review_logs"]) == 1
    assert [r["id"] for r in db.table("saved_words").select("*").eq("user_id", uid).execute().data] == [scoped_key(uid, "hello")]
    assert len(db.table("review_logs").select("*").eq("user_id", uid).execute().data) == 1


