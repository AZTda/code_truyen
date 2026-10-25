"""Regression tests for authentication boundaries and durable sync behavior."""

from types import SimpleNamespace

from fastapi.testclient import TestClient

from database import db
from server import app
from services.auth_token import issue_token
from services.job_registry import job_registry
from services.url_guard import UnsafeUrlError, assert_safe_redirect_chain


client = TestClient(app)


def test_guest_cannot_write_sync_data():
    cases = [
        ("/v1/progress/sync", {"novel_id": "guest-book", "current_chapter": 1}),
        ("/v1/bookmarks/sync", {"bookmarks": []}),
        ("/v1/vocabulary/sync", {"words": [], "review_logs": []}),
    ]
    for path, body in cases:
        response = client.post(path, json=body)
        assert response.status_code == 401, path


def test_job_status_is_visible_only_to_owner():
    owner_job = job_registry.create("test", "owner-a")
    owner_token = issue_token("owner-a", "owner")
    other_token = issue_token("owner-b", "other")

    owner_response = client.get(
        f"/v1/jobs/{owner_job.id}",
        headers={"Authorization": f"Bearer {owner_token}"},
    )
    other_response = client.get(
        f"/v1/jobs/{owner_job.id}",
        headers={"Authorization": f"Bearer {other_token}"},
    )
    assert owner_response.status_code == 200
    assert owner_response.json()["id"] == owner_job.id
    assert other_response.status_code == 404


def test_progress_is_written_to_backend_table_with_user_namespace():
    token = issue_token("progress-owner", "owner")
    response = client.post(
        "/v1/progress/sync",
        json={"novel_id": "backend-table-book", "current_chapter": 9},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200

    stored = (
        db.table("reading_progress_backend")
        .select("*")
        .eq("user_id", "progress-owner")
        .eq("novel_id", "backend-table-book")
        .execute()
    )
    assert stored.data
    assert stored.data[0]["current_chapter"] == 9


def test_redirect_chain_rejects_private_ip():
    response = SimpleNamespace(
        history=[],
        url=SimpleNamespace(host="127.0.0.1"),
    )
    try:
        assert_safe_redirect_chain(response)
    except UnsafeUrlError:
        return
    raise AssertionError("Redirect tới IP private phải bị chặn")
