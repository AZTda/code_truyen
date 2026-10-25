"""Tests for bookmarks and vocabulary synchronization."""
import pytest
from fastapi.testclient import TestClient
from database import db, SqliteBackedTable
from server import app
from services.auth_token import issue_token

client = TestClient(app)


@pytest.fixture(autouse=True)
def isolated_db(tmp_path, monkeypatch):
    """Keep sync tests out of the real truyencloud.db."""
    tables = {}

    def table(name):
        if name not in tables:
            tables[name] = SqliteBackedTable(name, db_path=str(tmp_path / "sync.db"))
        return tables[name]

    monkeypatch.setattr(db, "table", table)


def test_bookmarks_sync_isolated_by_user():
    token1 = issue_token("user_bm_1", "bm1@test.com")
    headers1 = {"Authorization": f"Bearer {token1}"}

    token2 = issue_token("user_bm_2", "bm2@test.com")
    headers2 = {"Authorization": f"Bearer {token2}"}

    # User 1 syncs 2 bookmarks
    payload1 = {
        "bookmarks": [
            {
                "id": "bm_u1_1",
                "novel_id": "novel_100",
                "chapter_number": 3,
                "chapter_title": "Chương 3: Khởi đầu",
                "paragraph_index": 12,
                "selected_text": "Cụm từ đáng nhớ",
                "note": "Ghi chú học tập",
                "color_hex": "#FFD54F",
                "created_at": 1720000000000
            },
            {
                "id": "bm_u1_2",
                "novel_id": "novel_100",
                "chapter_number": 4,
                "chapter_title": "Chương 4: Thử thách",
                "paragraph_index": 5,
                "selected_text": "Thành ngữ hay",
                "note": "Học thuộc",
                "color_hex": "#4DD0E1",
                "created_at": 1720000001000
            }
        ]
    }
    res = client.post("/v1/bookmarks/sync", json=payload1, headers=headers1)
    assert res.status_code == 200
    assert res.json()["count"] == 2

    # User 2 syncs 1 bookmark
    payload2 = {
        "bookmarks": [
            {
                "id": "bm_u2_1",
                "novel_id": "novel_200",
                "chapter_number": 1,
                "chapter_title": "Chương 1",
                "paragraph_index": 2,
                "selected_text": "Bookmark của user 2",
                "note": "",
                "color_hex": "#F06292",
                "created_at": 1720000002000
            }
        ]
    }
    res = client.post("/v1/bookmarks/sync", json=payload2, headers=headers2)
    assert res.status_code == 200

    # Verify User 1 gets 2 bookmarks, User 2 gets 1 bookmark
    list1 = client.get("/v1/bookmarks", headers=headers1).json()
    ids1 = {b["id"] for b in list1}
    assert "bm_u1_1" in ids1
    assert "bm_u1_2" in ids1
    assert "bm_u2_1" not in ids1

    list2 = client.get("/v1/bookmarks", headers=headers2).json()
    ids2 = {b["id"] for b in list2}
    assert "bm_u2_1" in ids2
    assert "bm_u1_1" not in ids2

    # Delete 1 bookmark for User 1
    del_res = client.delete("/v1/bookmarks/bm_u1_1", headers=headers1)
    assert del_res.status_code == 200

    # Xoá = tombstone: GET vẫn trả bm_u1_1 (deleted_at > 0) để máy khác xoá theo.
    remaining1 = {b["id"]: b for b in client.get("/v1/bookmarks?since=0", headers=headers1).json()}
    assert remaining1["bm_u1_1"]["deleted_at"] > 0
    assert remaining1["bm_u1_2"]["deleted_at"] == 0


def test_vocabulary_sync_and_review_logs():
    token = issue_token("user_vocab_1", "vocab1@test.com")
    headers = {"Authorization": f"Bearer {token}"}

    payload = {
        "words": [
            {
                "id": "serendipity",
                "word": "serendipity",
                "ipaUs": "/ˌser.ənˈdɪp.ə.t̬i/",
                "contextMeaningVi": "Sự tình cờ may mắn",
                "cefrLevel": "C2",
                "stability": 4.5,
                "difficulty": 3.2,
                "fsrsState": 2,
                "reps": 3
            }
        ],
        "review_logs": [
            {
                "id": "log_1",
                "wordId": "serendipity",
                "rating": 3,
                "reviewedAt": 1720001000000,
                "elapsedDays": 1.0,
                "scheduledDays": 3.0
            }
        ]
    }

    sync_res = client.post("/v1/vocabulary/sync", json=payload, headers=headers)
    assert sync_res.status_code == 200
    assert sync_res.json()["words_synced"] == 1
    assert sync_res.json()["logs_synced"] == 1

    get_res = client.get("/v1/vocabulary/sync", headers=headers)
    assert get_res.status_code == 200
    data = get_res.json()
    assert len(data["words"]) >= 1
    assert any(w.get("word") == "serendipity" for w in data["words"])
    assert len(data["review_logs"]) >= 1
