"""Vá lỗi đồng bộ: giờ client không giới hạn (một giờ sai năm 2031 chặn mọi lần sửa sau, giờ khổng lồ gây 500)
và con trỏ `since` bỏ sót dòng ghi cùng mili giây với lượt kéo."""

import pytest

from database import db
from services.sync_merge import iso_from_ms
from services.sync_store import legacy_progress_key
from tests._sync_support import (  # noqa: F401  (clock: fixture)
    MINUTE_MS, YEAR_MS, bearer, bm, client, clock, pull_bookmarks, pull_vocab, push_bookmarks, push_words,
)


def test_progress_future_timestamp_is_clamped_and_blocks_for_minutes_not_forever(clock):
    headers = bearer("user_clock", "clock")
    res = client.post("/v1/progress/sync", headers=headers,
                      json={"novel_id": "n", "current_chapter": 10, "client_updated_at": clock() + 5 * YEAR_MS})
    assert res.json()["status"] == "success"
    assert res.json()["updated_at_ms"] <= clock() + 5 * MINUTE_MS

    clock.advance(6 * MINUTE_MS)  # qua cửa sổ dung sai: giờ thật phải thắng lại
    real = client.post("/v1/progress/sync", headers=headers,
                       json={"novel_id": "n", "current_chapter": 11, "client_updated_at": clock()})
    assert real.json()["status"] == "success"
    legacy = client.post("/v1/progress/sync", headers=headers, json={"novel_id": "n", "current_chapter": 12})
    assert legacy.json()["status"] == "success"  # app cũ không gửi giờ -> dùng giờ server
    assert client.get("/v1/progress/n", headers=headers).json()["current_chapter"] == 12


@pytest.mark.parametrize("absurd", [253_402_300_800_000, 2 ** 62, 10 ** 30])
def test_progress_absurd_timestamp_is_clamped_instead_of_500(absurd, clock):
    res = client.post("/v1/progress/sync", headers=bearer("user_clock", "clock"),
                      json={"novel_id": "n", "current_chapter": 1, "client_updated_at": absurd})
    assert res.status_code == 200
    assert res.json()["updated_at_ms"] <= clock() + 5 * MINUTE_MS


def test_progress_row_poisoned_by_an_older_build_stops_blocking(clock):
    uid = "user_poisoned"
    far = clock() + 5 * YEAR_MS
    db.table("reading_progress_backend").upsert({
        "id": legacy_progress_key(uid, "n"), "novel_id": "n", "user_id": uid, "current_chapter": 5,
        "updated_at_ms": far, "last_read_at": iso_from_ms(far), "synced_at": iso_from_ms(clock() - 30 * 24 * 3600 * 1000)})
    headers = bearer(uid, "poisoned")
    res = client.post("/v1/progress/sync", headers=headers, json={"novel_id": "n", "current_chapter": 6, "client_updated_at": clock()})
    assert res.json()["status"] == "success"
    assert client.get("/v1/progress/n", headers=headers).json()["updated_at_ms"] <= clock() + 5 * MINUTE_MS


def test_progress_rejects_nan_scroll_position_and_list_keeps_working():
    headers = bearer("user_nan", "nan")
    ok = client.post("/v1/progress/sync", headers=headers, json={"novel_id": "good", "current_chapter": 3, "client_updated_at": 10})
    assert ok.status_code == 200
    bad = client.post("/v1/progress/sync", headers={**headers, "content-type": "application/json"},
                      content='{"novel_id": "bad", "current_chapter": 1, "scroll_position": NaN}')
    assert bad.status_code == 422
    assert [p["novel_id"] for p in client.get("/v1/progress", headers=headers).json()] == ["good"]


def test_bookmark_far_future_tombstone_does_not_delete_forever(clock):
    headers = bearer("user_clock", "clock")
    push_bookmarks(headers, bm("b1", note="v1", updated_at=clock()))
    future = clock() + 50 * YEAR_MS
    res = push_bookmarks(headers, bm("b1", note="v1", updated_at=future, deleted_at=future)).json()
    assert res["applied"] == 1
    tomb = pull_bookmarks(headers).json()[0]
    assert tomb["deleted_at"] <= clock() + 5 * MINUTE_MS and tomb["updated_at"] <= clock() + 5 * MINUTE_MS

    clock.advance(6 * MINUTE_MS)
    live = push_bookmarks(headers, bm("b1", note="v2", updated_at=clock())).json()
    assert live["stale_ids"] == [] and live["applied"] == 1
    assert pull_bookmarks(headers).json()[0]["note"] == "v2"


def test_vocabulary_future_timestamp_is_clamped_in_stored_word_too(clock):
    headers = bearer("user_clock", "clock")
    push_words(headers, [{"id": "w", "word": "w", "updatedAt": clock() + 5 * YEAR_MS}])
    stored = pull_vocab(headers)["words"][0]
    assert stored["updatedAt"] <= clock() + 5 * MINUTE_MS  # máy khác so sánh theo chính trường này

    clock.advance(6 * MINUTE_MS)
    res = push_words(headers, [{"id": "w", "word": "w", "meaning": "later", "updatedAt": clock()}]).json()
    assert res["words_stale"] == 0
    assert pull_vocab(headers)["words"][0]["meaning"] == "later"


def test_vocabulary_invalid_payload_is_rejected_before_any_row_is_written():
    headers = {**bearer("user_atomic", "atomic"), "content-type": "application/json"}
    body = ('{"words": [{"id": "ok1", "word": "ok1", "updatedAt": 1000}, '
            '{"id": "boom", "word": "boom", "updatedAt": Infinity}, '
            '{"id": "ok3", "word": "ok3", "updatedAt": 1000}], "review_logs": []}')
    res = client.post("/v1/vocabulary/sync", headers=headers, content=body)
    assert res.status_code == 422
    assert pull_vocab(headers)["words"] == []  # trước đây: 500 và 'ok1' đã bị ghi dở

    nested = ('{"words": [{"id": "ok1", "word": "ok1"}, {"id": "deep", "word": "deep", "stability": {"x": [NaN]}}],'
              ' "review_logs": []}')
    assert client.post("/v1/vocabulary/sync", headers=headers, content=nested).status_code == 422
    bad_id = {"words": [{"id": "ok1", "word": "ok1"}, {"id": {"a": 1}, "word": "w"}], "review_logs": []}
    assert client.post("/v1/vocabulary/sync", headers=headers, json=bad_id).status_code == 422
    assert pull_vocab(headers)["words"] == []


def test_vocabulary_garbage_timestamps_do_not_crash():
    res = push_words(bearer("user_atomic", "atomic"),
                     [{"id": "w1", "word": "w1", "updatedAt": "abc"}, {"id": "w2", "word": "w2", "updatedAt": [1], "deletedAt": 1e300}])
    assert res.status_code == 200 and res.json()["words_synced"] == 2


# --------------------------------------------------------------------------- mốc `since` không bỏ sót

def test_bookmark_written_in_same_millisecond_as_a_pull_is_delivered_next_pull(clock):
    headers = bearer("user_cursor", "cursor")
    cursor = int(pull_bookmarks(headers).headers["X-Server-Time-Ms"])
    push_bookmarks(headers, bm("late", updated_at=1))  # cùng mili giây với lượt kéo, giờ client cũ
    again = pull_bookmarks(headers, since=cursor)
    assert [b["id"] for b in again.json()] == ["late"]
    # Con trỏ mới đã phủ dòng đó: lượt kéo sau nữa không trả lại.
    assert pull_bookmarks(headers, since=int(again.headers["X-Server-Time-Ms"])).json() == []


def test_vocabulary_written_in_same_millisecond_as_a_pull_is_delivered_next_pull(clock):
    headers = bearer("user_cursor", "cursor")
    cursor = pull_vocab(headers)["server_time_ms"]
    push_words(headers, [{"id": "late", "word": "late", "updatedAt": 1}], [{"wordId": "late", "reviewedAt": 7}])
    data = pull_vocab(headers, since=cursor)
    assert [w["id"] for w in data["words"]] == ["late"]
    assert [log["wordId"] for log in data["review_logs"]] == ["late"]
    assert pull_vocab(headers, since=data["server_time_ms"]) ["words"] == []
