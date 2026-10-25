"""Tài khoản quản trị / thường giả cho test: users.json nằm ở thư mục tạm.

Route kiểm tra quyền quản trị bằng routes.auth.load_users(); test không được đọc (và càng không được
tạo) server/data/users.json thật. Fixture này trỏ routes.auth sang một users.json tạm có đúng hai tài khoản.
"""

import json

import pytest

import routes.auth as auth_routes
from services.auth_token import issue_token

ADMIN_HEADERS = {"Authorization": f"Bearer {issue_token('user_boss', 'boss')}"}
USER_HEADERS = {"Authorization": f"Bearer {issue_token('user_reader', 'reader')}"}


@pytest.fixture
def two_accounts(tmp_path, monkeypatch):
    users_file = tmp_path / "users.json"
    users_file.write_text(json.dumps({
        "boss": {"id": "user_boss", "username": "boss", "password": "x", "is_admin": True},
        "reader": {"id": "user_reader", "username": "reader", "password": "x"},
    }), encoding="utf-8")
    monkeypatch.setattr(auth_routes, "DATA_DIR", tmp_path)
    monkeypatch.setattr(auth_routes, "USERS_FILE", users_file)
    monkeypatch.delenv("ADMIN_KEY", raising=False)
    monkeypatch.delenv("ADMIN_SECRET", raising=False)
    reset = getattr(auth_routes, "reset_users_cache", None)   # có từ khi đăng nhập kiểm tra tài khoản qua sổ đệm
    if reset:
        reset()
    return users_file
