"""Tests for full reading progress synchronization across devices."""
import pytest
from fastapi.testclient import TestClient
from server import app
from services.auth_token import issue_token

client = TestClient(app)


def test_get_all_progress_isolated_by_user():
    # User 1 tokens and sync
    token1 = issue_token("user_sync_1", "u1@test.com")
    headers1 = {"Authorization": f"Bearer {token1}"}

    client.post(
        "/v1/progress/sync",
        json={"novel_id": "novel_a", "current_chapter": 5, "scroll_position": 0.4},
        headers=headers1,
    )
    client.post(
        "/v1/progress/sync",
        json={"novel_id": "novel_b", "current_chapter": 12, "scroll_position": 0.8},
        headers=headers1,
    )

    # User 2 tokens and sync
    token2 = issue_token("user_sync_2", "u2@test.com")
    headers2 = {"Authorization": f"Bearer {token2}"}

    client.post(
        "/v1/progress/sync",
        json={"novel_id": "novel_c", "current_chapter": 20, "scroll_position": 0.1},
        headers=headers2,
    )

    # Verify user 1 gets both novel_a and novel_b, and not novel_c
    res1 = client.get("/v1/progress", headers=headers1)
    assert res1.status_code == 200
    items1 = res1.json()
    novel_ids1 = {it["novel_id"] for it in items1}
    assert "novel_a" in novel_ids1
    assert "novel_b" in novel_ids1
    assert "novel_c" not in novel_ids1

    # Verify user 2 gets only novel_c
    res2 = client.get("/v1/progress", headers=headers2)
    assert res2.status_code == 200
    items2 = res2.json()
    novel_ids2 = {it["novel_id"] for it in items2}
    assert "novel_c" in novel_ids2
    assert "novel_a" not in novel_ids2
    assert "novel_b" not in novel_ids2
