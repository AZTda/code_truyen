"""Tests for GET /v1/novels/discover (public server library feed)."""

import pytest
from fastapi.testclient import TestClient

from database import db, SqliteBackedTable
from server import app
from services.library_index import library_index
from services.novel_catalog import reader_counter

client = TestClient(app)

SUMMARY_KEYS = {"id", "title", "title_vi", "author", "cover_url", "total_chapters",
                "source_id", "source_url", "description", "status",
                "category", "overnight_enabled", "translated_chapters"}


def _entry(nid, updated_day, source_id="69shuba", **extra):
    novel = {"id": nid, "title": f"T {nid}", "title_vi": f"Truyện {nid}", "author": "A",
             "source_id": source_id, "source_url": f"https://x/{nid}", "total_chapters": 3,
             "updated_at": f"2026-09-{updated_day:02d}T00:00:00+00:00"}
    novel.update(extra)
    return {"novel": novel, "id": nid, "title_vi": novel["title_vi"]}


@pytest.fixture
def library(tmp_path, monkeypatch):
    tables = {}

    def table(name):
        if name not in tables:
            tables[name] = SqliteBackedTable(name, db_path=str(tmp_path / "discover.db"))
        return tables[name]

    entries = [
        _entry("old", 1),
        _entry("new", 20),
        _entry("mid", 10),
        _entry("import_abcd1234", 25, source_id="local_import"),
    ]
    monkeypatch.setattr(db, "table", table)
    monkeypatch.setattr(library_index, "get_entries", lambda: entries)
    reader_counter.invalidate()
    yield table, entries
    reader_counter.invalidate()


def _read(table, uid, nid):
    table("reading_progress_backend").upsert(
        {"id": f"progress_{uid}_{nid}", "user_id": uid, "novel_id": nid, "current_chapter": 1})


def test_discover_is_public_and_not_captured_as_novel_id(library):
    res = client.get("/v1/novels/discover")
    assert res.status_code == 200
    body = res.json()
    assert set(body) == {"novels", "total"}
    assert set(body["novels"][0]) == SUMMARY_KEYS


def test_discover_recent_orders_by_updated_and_hides_private_imports(library):
    body = client.get("/v1/novels/discover?sort=recent").json()
    assert [n["id"] for n in body["novels"]] == ["new", "mid", "old"]
    assert body["total"] == 3


def test_discover_popular_counts_distinct_readers_with_recent_tiebreak(library):
    table, _ = library
    _read(table, "u1", "old")
    _read(table, "u2", "old")
    _read(table, "u1", "mid")
    _read(table, "u1", "import_abcd1234")
    body = client.get("/v1/novels/discover?sort=popular").json()
    # old: 2 người, mid: 1, new: 0 ; truyện import riêng không bao giờ lộ ra.
    assert [n["id"] for n in body["novels"]] == ["old", "mid", "new"]


def test_discover_limit_is_capped_at_100(library):
    _, entries = library
    entries.extend(_entry(f"bulk{i}", 5) for i in range(520))
    body = client.get("/v1/novels/discover?limit=600").json()
    assert len(body["novels"]) == 500
    assert body["total"] == 523
    assert len(client.get("/v1/novels/discover?limit=2").json()["novels"]) == 2


def test_discover_rejects_unknown_sort(library):
    assert client.get("/v1/novels/discover?sort=random").status_code == 422
