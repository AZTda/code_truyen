"""Bộ giả dùng chung cho test: DB trong bộ nhớ + chuyển mọi kho đĩa sang thư mục tạm.

Các test mới KHÔNG được ghi vào server/truyencloud.db hay server/data thật.
"""

import os
from types import SimpleNamespace
from typing import Any, Dict, List, Optional

import pytest


class _Resp:
    def __init__(self, data: Any):
        self.data = data


class _Query:
    def __init__(self, table: "FakeTable", action: str = "select", data: Optional[Dict] = None):
        self.table = table
        self.action = action
        self.data = data or {}
        self.filters: Dict[str, Any] = {}
        self.is_single = False

    def select(self, *args, **kwargs) -> "_Query":
        return self

    def eq(self, column: str, value: Any) -> "_Query":
        self.filters[column] = value
        return self

    def single(self) -> "_Query":
        self.is_single = True
        return self

    def execute(self) -> _Resp:
        rows = [r for r in self.table.rows if all(r.get(k) == v for k, v in self.filters.items())]
        if self.action == "update":
            for r in rows:
                r.update(self.data)
            return _Resp([])
        if self.is_single:
            return _Resp(dict(rows[0]) if rows else None)
        return _Resp([dict(r) for r in rows])


class FakeTable:
    def __init__(self):
        self.rows: List[Dict[str, Any]] = []

    def select(self, *args, **kwargs) -> _Query:
        return _Query(self)

    def eq(self, column: str, value: Any) -> _Query:
        return _Query(self).eq(column, value)

    def update(self, data: Dict[str, Any]) -> _Query:
        return _Query(self, "update", data)

    def upsert(self, data: Dict[str, Any], **kwargs) -> "FakeTable":
        key = data.get("id") or (data.get("novel_id"), data.get("chapter_number"))
        for row in self.rows:
            if (row.get("id") or (row.get("novel_id"), row.get("chapter_number"))) == key:
                row.update(data)
                return self
        self.rows.append(dict(data))
        return self

    def execute(self) -> _Resp:
        return _Resp(None)


class FakeDb:
    def __init__(self):
        self.tables: Dict[str, FakeTable] = {}

    def table(self, name: str) -> FakeTable:
        return self.tables.setdefault(name, FakeTable())


@pytest.fixture
def isolated(tmp_path, monkeypatch):
    """DB giả + storage/glossary/dictionary_cache ở thư mục tạm + tắt lịch auto-glossary."""
    from database import db
    from services import rate_limit
    from services.storage_service import storage_service
    from services.glossary_store import glossary_store
    from services.dictionary_cache import dictionary_cache
    from services.auto_glossary import auto_glossary
    from services import chapter_translation_service

    fake_db = FakeDb()
    monkeypatch.setattr(db, "table", fake_db.table)

    novels_dir = tmp_path / "novels"
    gloss_dir = tmp_path / "glossaries"
    os.makedirs(novels_dir)
    os.makedirs(gloss_dir)
    monkeypatch.setattr(storage_service, "base_dir", str(novels_dir))
    monkeypatch.setattr(glossary_store, "base_dir", str(gloss_dir))
    monkeypatch.setattr(dictionary_cache, "db_path", str(tmp_path / "dict.db"))
    dictionary_cache._init_db()

    scheduled: List[tuple] = []
    monkeypatch.setattr(auto_glossary, "schedule", lambda *a, **k: scheduled.append(a) or True)
    chapter_translation_service._ai_retry_after.clear()
    rate_limit.reset_all()
    yield SimpleNamespace(db=fake_db, tmp=tmp_path, scheduled=scheduled)
    chapter_translation_service._ai_retry_after.clear()
    rate_limit.reset_all()
