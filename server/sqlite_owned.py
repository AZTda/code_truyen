"""Ghi có kiểm tra chủ sở hữu cho các bảng đồng bộ theo người dùng.

VÌ SAO CẦN: khoá dòng (pkey) ghép từ id do máy khách tự chọn, nên hai người dùng khác nhau
có thể sinh ra cùng một pkey. `INSERT ... ON CONFLICT DO UPDATE` trần sẽ để người này ghi đè
(và đổi chủ) dòng của người kia. Ở đây phép ghi chỉ thành công khi pkey chưa tồn tại hoặc dòng
đang có CÙNG chủ — kiểm tra nằm ngay trong câu SQL nên không có khe hở giữa đọc và ghi.
"""

import json
import re
from typing import Any, Dict, List, Optional, Sequence

_FIELD_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,63}$")


class OwnedWritesMixin:
    """Trộn vào SqliteBackedTable (cần `name`, `_get_connection`, `_extract_pkey`, `_cache_put`, `_index`)."""

    def upsert_owned_many(
        self,
        records: Sequence[Dict[str, Any]],
        supersedes: Optional[Sequence[Sequence[str]]] = None,
        owner_field: str = "user_id",
    ) -> List[bool]:
        """Ghi nhiều dòng trong MỘT giao dịch; trả về từng dòng đã được ghi hay bị từ chối.

        `supersedes[i]` là các pkey kiểu cũ của chính chủ dòng i: xoá cùng giao dịch khi dòng i được
        ghi (di trú khoá), và chỉ xoá nếu dòng đó cũng thuộc cùng chủ.
        Lỗi cơ sở dữ liệu được ném lên (khác `upsert`) để route trả 5xx thay vì báo đã lưu.
        """
        if not _FIELD_RE.match(owner_field):
            raise ValueError(f"Tên trường chủ sở hữu không hợp lệ: {owner_field!r}")
        if not records:
            return []
        older = [tuple(keys) for keys in supersedes] if supersedes is not None else [() for _ in records]
        if len(older) != len(records):
            raise ValueError("supersedes phải cùng độ dài với records")

        upsert_sql = (
            f"INSERT INTO {self.name} (pkey, data) VALUES (?, ?) "
            f"ON CONFLICT(pkey) DO UPDATE SET data = excluded.data, updated_at = CURRENT_TIMESTAMP "
            f"WHERE json_extract({self.name}.data, '$.{owner_field}') IS json_extract(excluded.data, '$.{owner_field}')"
        )
        delete_sql = f"DELETE FROM {self.name} WHERE pkey = ? AND json_extract(data, '$.{owner_field}') IS ?"

        keys = [self._extract_pkey(record) for record in records]
        applied: List[bool] = []
        conn = self._get_connection()
        with conn:
            cursor = conn.cursor()
            for pkey, record, old_keys in zip(keys, records, older):
                cursor.execute(upsert_sql, (pkey, json.dumps(record, ensure_ascii=False)))
                written = cursor.rowcount > 0
                applied.append(written)
                if written:
                    for old in old_keys:
                        if old != pkey:
                            cursor.execute(delete_sql, (old, record.get(owner_field)))

        # Cache chỉ cập nhật SAU khi commit và chỉ cho dòng đã được ghi: dòng bị từ chối
        # mà lọt vào cache sẽ bị trả về cho lượt đọc điểm kế tiếp.
        for pkey, record, old_keys, written in zip(keys, records, older, applied):
            if written:
                self._cache_put(pkey, dict(record))
                for old in old_keys:
                    self._index.pop(old, None)
        return applied

    def upsert_owned(
        self,
        record: Dict[str, Any],
        supersedes: Sequence[str] = (),
        owner_field: str = "user_id",
    ) -> bool:
        return self.upsert_owned_many([record], [tuple(supersedes)], owner_field)[0]
