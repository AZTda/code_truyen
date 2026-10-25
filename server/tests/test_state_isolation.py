"""Bộ test không được ghi vào dữ liệu thật của server (truyencloud.db, data/novels, models_cache.json, users.json...)."""

import subprocess
import sys

import pytest

import database
import manage_users
import routes.app_update as app_update
import routes.auth as auth_routes
import routes.images as images_routes
import routes.settings as settings_routes
import routes.tts as tts_routes
from database import db
from services import model_catalog, tts_cache, tts_voice_service
from services.glossary_store import glossary_store
from services.storage_service import storage_service
from tests._real_data_fingerprint import SERVER_DIR, changed, fingerprint


def under(tmp_path, value):
    return str(value).startswith(str(tmp_path))


def test_every_persistent_path_points_into_this_tests_tmp_dir(tmp_path):
    assert under(tmp_path, database.DB_PATH)
    assert under(tmp_path, db.table("probe_isolated").db_path)
    assert db.is_mock and db.client is None
    assert under(tmp_path, storage_service.base_dir) and under(tmp_path, glossary_store.base_dir)
    assert under(tmp_path, model_catalog.CATALOG_FILE) and under(tmp_path, model_catalog.DATA_DIR)
    assert under(tmp_path, auth_routes.USERS_FILE) and under(tmp_path, auth_routes.DATA_DIR)
    assert under(tmp_path, manage_users.USERS_FILE)
    assert under(tmp_path, settings_routes.PROFILE_FILE) and under(tmp_path, settings_routes.DATA_DIR)
    assert under(tmp_path, app_update.VERSION_FILE) and under(tmp_path, app_update.DATA_DIR)
    for cache_dir in (tts_routes.CACHE_DIR, tts_voice_service.CACHE_DIR, tts_cache.CACHE_DIR, images_routes.CACHE_DIR):
        assert under(tmp_path, cache_dir)


def test_default_db_is_resolved_when_used_not_when_the_function_is_defined(tmp_path, monkeypatch):
    other = str(tmp_path / "elsewhere.db")
    monkeypatch.setattr(database, "DB_PATH", other)
    assert database.get_db_connection().execute("PRAGMA database_list").fetchall()[0][2] == other
    assert database.SqliteBackedTable("late_bound").db_path == other
    database.close_all_connections(other)


def test_fingerprint_detects_added_changed_and_removed_files(tmp_path):
    (tmp_path / "data" / "novels").mkdir(parents=True)
    (tmp_path / "data" / "users.json").write_text("{}", encoding="utf-8")
    base = fingerprint(tmp_path)
    assert changed(base, fingerprint(tmp_path)) == []
    (tmp_path / "data" / "novels" / "leak").write_text("x", encoding="utf-8")
    assert changed(base, fingerprint(tmp_path)) == ["data/novels/"]
    (tmp_path / "data" / "users.json").write_text('{"a": 1}', encoding="utf-8")
    assert changed(base, fingerprint(tmp_path)) == ["data/novels/", "data/users.json"]
    (tmp_path / "data" / "users.json").unlink()
    assert "data/users.json" in changed(base, fingerprint(tmp_path))


def _run_probe():
    result = subprocess.run(
        [sys.executable, "-m", "pytest", "tests/_state_probe.py", "-q", "-p", "no:cacheprovider"],
        cwd=SERVER_DIR, capture_output=True, text=True, timeout=180,
    )
    assert result.returncode == 0 and "1 passed" in result.stdout, result.stdout + result.stderr


def test_a_sample_run_that_touches_every_store_leaves_the_real_data_untouched():
    # Máy chủ thật (hoặc tiến trình khác) có thể ghi vào các tệp này đúng lúc ta đo: chỉ kết luận là rò rỉ khi
    # CẢ HAI lượt chạy liên tiếp đều làm đổi dữ liệu thật (probe ghi lại cùng dòng nên rò rỉ thật đổi mtime/nội dung mỗi lượt).
    leaked = []
    for _ in range(2):
        before = fingerprint()
        _run_probe()
        leaked = changed(before, fingerprint())
        if not leaked:
            return
    pytest.fail(
        f"Test đã ghi vào dữ liệu thật: {leaked}. Một kho dữ liệu mới có thể chưa được tests/_isolation.py cách ly."
    )
