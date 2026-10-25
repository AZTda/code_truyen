"""Truyện từ nguồn đã gỡ không vào chỉ mục thư viện, nên không lộ ở Khám phá hay tìm kiếm trong thư viện."""

from services.library_index import library_index
from services.storage_service import storage_service


def test_library_index_skips_removed_source_novels(monkeypatch):
    rows = [
        {"id": "jj_1", "title": "测试书", "source_id": "jjwxc"},
        {"id": "pq_1", "title": "剑来", "source_id": "piaotia"},
    ]
    monkeypatch.setattr(storage_service, "list_all_novels", lambda: rows)
    library_index._rebuild()
    try:
        ids = [entry["id"] for entry in library_index._entries]
    finally:
        library_index.invalidate()
    assert "pq_1" in ids
    assert "jj_1" not in ids
