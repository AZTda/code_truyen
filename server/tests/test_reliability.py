"""Regression tests for durable jobs, safe imports, and trusted client identity."""

import io
import json
import zipfile
from types import SimpleNamespace

import pytest

from services.job_registry import JobRegistry
from services.rate_limit import client_key
from services import import_service as import_module


def test_job_registry_survives_a_new_process_instance(tmp_path):
    db_path = str(tmp_path / "jobs.db")
    first = JobRegistry(db_path=db_path)
    created = first.create("translation", "owner-a")
    first.update(created.id, status="running", progress=0.4, message="Đang dịch")

    second = JobRegistry(db_path=db_path)
    status = second.get_for_owner(created.id, "owner-a")
    assert status is not None
    assert status["status"] == "running"
    assert status["progress"] == pytest.approx(0.4)
    assert second.get_for_owner(created.id, "owner-b") is None


def test_job_registry_purges_expired_records(tmp_path):
    registry = JobRegistry(ttl_seconds=-1, db_path=str(tmp_path / "jobs.db"))
    created = registry.create("test", "owner")
    assert registry.get_for_owner(created.id, "owner") is None


def test_users_file_write_is_atomic_and_corruption_does_not_bootstrap_test_user(tmp_path, monkeypatch):
    from routes import auth

    users_file = tmp_path / "users.json"
    monkeypatch.setattr(auth, "DATA_DIR", tmp_path)
    monkeypatch.setattr(auth, "USERS_FILE", users_file)
    users_file.write_text("{not-json", encoding="utf-8")

    assert auth.load_users() == {}
    auth.save_users({"reader": {"id": "user_reader"}})
    assert json.loads(users_file.read_text(encoding="utf-8"))["reader"]["id"] == "user_reader"


def test_untrusted_forwarded_for_cannot_rotate_rate_limit_key(monkeypatch):
    from config import settings

    monkeypatch.setattr(settings, "TRUSTED_PROXY_IPS", "")
    request = SimpleNamespace(
        client=SimpleNamespace(host="10.0.0.5"),
        headers={"x-forwarded-for": "198.51.100.99"},
    )
    assert client_key(request) == "10.0.0.5"

    monkeypatch.setattr(settings, "TRUSTED_PROXY_IPS", "10.0.0.5")
    assert client_key(request) == "198.51.100.99"


def test_epub_rejects_excessive_entry_count(monkeypatch):
    monkeypatch.setattr(import_module, "MAX_EPUB_ENTRIES", 1)
    payload = io.BytesIO()
    with zipfile.ZipFile(payload, "w") as archive:
        archive.writestr("META-INF/container.xml", "<container />")
        archive.writestr("content.opf", "<package />")

    with pytest.raises(ValueError, match="quá nhiều mục nén"):
        import_module.ImportService().parse_epub(payload.getvalue())
