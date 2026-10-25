"""Ghi hàng loạt chương xuống đĩa + database trong một lượt.

Dùng chung cho luồng cào truyện (routes/scrape.py), luồng tự phân giải danh mục
(routes/novels.py) và luồng nhập tệp (services/import_service.py) — một
transaction SQLite duy nhất thay vì mỗi chương một transaction.
"""

from typing import Any, Dict, List, Optional
from database import db
from services.storage_service import storage_service


def persist_chapters(
    novel_id: str,
    ch_records: List[Dict[str, Any]],
    chapters_index: Optional[List[Dict[str, Any]]] = None,
) -> None:
    """Lưu ch_records xuống đĩa + DB; chapters_index (nếu có) ghi vào chapters_index.json."""
    if ch_records:
        storage_service.save_chapters_bulk(novel_id, ch_records)
        table = db.table("chapters")
        if hasattr(table, "batch_upsert"):
            table.batch_upsert(ch_records)
        else:
            for record in ch_records:
                table.upsert(record).execute()
    if chapters_index is not None:
        storage_service.save_chapters_index(novel_id, chapters_index)
