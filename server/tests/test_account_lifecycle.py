"""Xoá / tạo lại tài khoản: phiên cũ phải chết, tạo lại cùng tên là danh tính mới, dữ liệu cũ bị dọn.

Dùng `strict_accounts` nên token chỉ hợp lệ khi tài khoản còn trong users.json (tạm) — đúng như production.
"""

import json
import re

import pytest
from fastapi.testclient import TestClient

import manage_users
import routes.auth as auth_routes
from database import db
from server import app
from services import auth_token
from services.auth_token import issue_token
from services.password import hash_password
from services.sync_store import scoped_key
from services.user_data import SYNC_TABLES

ADMIN_KEY = "lifecycle-admin-key-for-pytest-only-0123456789abcdef"
ADMIN = {"X-Admin-Key": ADMIN_KEY}
client = TestClient(app, raise_server_exceptions=False)


@pytest.fixture(autouse=True)
def _strict_env(strict_accounts, monkeypatch):
    monkeypatch.setenv("ADMIN_KEY", ADMIN_KEY)
    monkeypatch.delenv("ADMIN_SECRET", raising=False)


def create_account(username, password="SecretPass123"):
    res = client.post("/v1/admin/users", headers=ADMIN, json={"username": username, "password": password})
    assert res.status_code == 201, res.text
    return res.json()


def login(username, password="SecretPass123"):
    res = client.post("/v1/auth/login", json={"email": username, "password": password})
    assert res.status_code == 200, res.text
    body = res.json()
    return {"Authorization": f"Bearer {body['token']}"}, body["user"]["id"]


def seed_sync_rows(headers, tag):
    client.post("/v1/progress/sync", headers=headers, json={"novel_id": f"n_{tag}", "current_chapter": 42, "client_updated_at": 1000})
    client.post("/v1/bookmarks/sync", headers=headers, json={"bookmarks": [
        {"id": f"b_{tag}", "novel_id": "n", "chapter_number": 1, "paragraph_index": 0, "created_at": 1, "updated_at": 5}]})
    client.post("/v1/vocabulary/sync", headers=headers, json={
        "words": [{"id": f"w_{tag}", "word": f"w_{tag}", "updatedAt": 5}],
        "review_logs": [{"wordId": f"w_{tag}", "reviewedAt": 7}]})


def rows_of(table, uid):
    return db.table(table).select("*").eq("user_id", uid).execute().data


# ----------------------------------------------------------------------------- phiên của tài khoản đã xoá

def test_deleted_account_token_is_rejected_on_every_authenticated_endpoint():
    create_account("victim1")
    token, _ = login("victim1")
    assert client.get("/v1/progress", headers=token).status_code == 200

    assert client.delete("/v1/admin/users/victim1", headers=ADMIN).status_code == 200
    probes = [
        ("GET", "/v1/progress", None),
        ("POST", "/v1/progress/sync", {"novel_id": "nv", "current_chapter": 43, "client_updated_at": 2000}),
        ("GET", "/v1/bookmarks?since=0", None),
        ("POST", "/v1/bookmarks/sync", {"bookmarks": []}),
        ("GET", "/v1/vocabulary/sync?since=0", None),
        ("POST", "/v1/vocabulary/sync", {"words": [], "review_logs": []}),
        ("POST", "/v1/settings/api-key", {"api_key": "k"}),
        ("GET", "/v1/jobs/whatever", None),
        ("GET", "/v1/auth/me", None),
        ("POST", "/v1/auth/refresh", None),
    ]
    for method, path, body in probes:
        res = client.request(method, path, headers=token, json=body) if body is not None else client.request(method, path, headers=token)
        assert res.status_code == 401, f"{method} {path} -> {res.status_code}"


def test_recreated_username_is_a_new_identity_without_the_old_session_or_data():
    first = create_account("former")
    old_token, old_id = login("former")
    seed_sync_rows(old_token, "old")
    assert client.delete("/v1/admin/users/former", headers=ADMIN).status_code == 200

    second = create_account("former", "NewOwnerPass1")
    assert second["id"] != first["id"]
    for method, path in [("GET", "/v1/auth/me"), ("POST", "/v1/auth/refresh"), ("GET", "/v1/progress")]:
        assert client.request(method, path, headers=old_token).status_code == 401, path

    new_token, new_id = login("former", "NewOwnerPass1")
    assert new_id == second["id"] != old_id
    assert client.get("/v1/progress", headers=new_token).json() == []  # dữ liệu cũ đã bị dọn, không thừa hưởng
    assert client.get("/v1/auth/me", headers=new_token).status_code == 200


def test_optional_user_treats_a_deleted_account_as_a_guest():
    import asyncio

    users_file = auth_routes.USERS_FILE
    users_file.write_text(json.dumps({"alice": {"id": "user_alice", "username": "alice"}}), encoding="utf-8")
    auth_routes.reset_users_cache()

    def resolve(authorization):
        return asyncio.run(auth_token.optional_user(authorization=authorization, x_auth_token=None))

    assert resolve(f"Bearer {issue_token('user_alice', 'alice')}")["sub"] == "user_alice"
    assert resolve(f"Bearer {issue_token('user_ghost', 'ghost')}") is None
    assert resolve(None) is None
    # Token cũ mang usr=alice nhưng id khác (tài khoản được tạo lại) cũng không còn hiệu lực.
    assert resolve(f"Bearer {issue_token('user_alice_old', 'alice')}") is None


def test_account_check_fails_closed_when_the_registry_cannot_be_read(monkeypatch):
    def boom():
        raise OSError("disk gone")

    monkeypatch.setattr(auth_routes, "load_users_cached", boom)
    assert auth_token.account_exists({"sub": "user_x", "usr": "x"}) is False


def test_users_cache_notices_edits_made_by_another_process(tmp_path):
    users_file = auth_routes.USERS_FILE
    users_file.write_text(json.dumps({"a": {"id": "user_a"}}), encoding="utf-8")
    assert set(auth_routes.load_users_cached()) == {"a"}
    replacement = tmp_path / "replacement.json"  # như manage_users.py: ghi tệp tạm rồi os.replace
    replacement.write_text(json.dumps({"a": {"id": "user_a"}, "b": {"id": "user_b"}}), encoding="utf-8")
    replacement.replace(users_file)
    assert set(auth_routes.load_users_cached()) == {"a", "b"}


# ----------------------------------------------------------------------------- id mới không đoán được, id cũ giữ nguyên

def test_new_accounts_get_unguessable_ids_in_admin_and_cli():
    ids = [create_account("fresh1")["id"], create_account("fresh2")["id"]]
    manage_users.cmd_add("cli_user", "CliPass1234")
    ids.append(manage_users.load_users()["cli_user"]["id"])
    assert len(set(ids)) == 3
    assert all(re.fullmatch(r"user_[0-9a-f]{32}", i) for i in ids)
    assert "user_fresh1" not in ids and "user_cli_user" not in ids


def test_existing_accounts_keep_their_ids_and_their_sessions_keep_working():
    auth_routes.USERS_FILE.write_text(json.dumps({
        "oldie": {"id": "user_oldie", "username": "oldie", "password": hash_password("OldiePass123")},
        "noid": {"username": "noid", "password": hash_password("NoidPass1234")},  # bản ghi rất cũ, chưa có id
    }), encoding="utf-8")
    auth_routes.reset_users_cache()
    oldie, oldie_id = login("oldie", "OldiePass123")
    noid, noid_id = login("noid", "NoidPass1234")
    assert (oldie_id, noid_id) == ("user_oldie", "user_internal")
    for headers in (oldie, noid):
        assert client.get("/v1/progress", headers=headers).status_code == 200
        assert client.get("/v1/auth/me", headers=headers).status_code == 200


# ----------------------------------------------------------------------------- dọn dữ liệu khi xoá tài khoản

def test_admin_delete_purges_only_the_deleted_accounts_sync_rows():
    create_account("leaving")
    create_account("staying")
    leaving, leaving_id = login("leaving")
    staying, staying_id = login("staying")
    seed_sync_rows(leaving, "l")
    seed_sync_rows(staying, "s")
    assert all(rows_of(table, leaving_id) for table in SYNC_TABLES)

    res = client.delete("/v1/admin/users/leaving", headers=ADMIN)
    assert res.status_code == 200
    assert all(count >= 1 for count in res.json()["purged_rows"].values())
    assert not any(rows_of(table, leaving_id) for table in SYNC_TABLES)
    assert all(rows_of(table, staying_id) for table in SYNC_TABLES)
    assert [p["novel_id"] for p in client.get("/v1/progress", headers=staying).json()] == ["n_s"]


def test_cli_delete_purges_rows_and_keep_data_flag_leaves_them(capsys):
    manage_users.cmd_add("gone_user", "GonePass1234")
    manage_users.cmd_add("kept_user", "KeptPass1234")
    users = manage_users.load_users()
    for name in ("gone_user", "kept_user"):
        uid = users[name]["id"]
        db.table("bookmarks").upsert({"id": scoped_key(uid, "b"), "client_id": "b", "user_id": uid, "novel_id": "n",
                                      "chapter_number": 1, "paragraph_index": 0, "created_at": 1})
    manage_users.cmd_delete("gone_user")
    manage_users.cmd_delete("kept_user", keep_data=True)
    assert rows_of("bookmarks", users["gone_user"]["id"]) == []
    assert len(rows_of("bookmarks", users["kept_user"]["id"])) == 1
    assert "gone_user" not in manage_users.load_users() and "kept_user" not in manage_users.load_users()
    assert "bookmarks=1" in capsys.readouterr().out
