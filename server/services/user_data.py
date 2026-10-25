"""Dữ liệu đồng bộ gắn với một tài khoản — dùng khi xoá tài khoản."""

import logging
from typing import Dict

logger = logging.getLogger("truyencloud.user_data")

SYNC_TABLES = ("reading_progress_backend", "bookmarks", "saved_words", "review_logs")

# Id dùng chung / dự phòng: xoá theo các id này sẽ xoá dữ liệu của người khác.
_UNSAFE_IDS = frozenset({"", "local", "user_internal"})


def purge_user_data(user_id: str) -> Dict[str, int]:
    """Xoá mọi dòng đồng bộ (tiến độ, bookmark, từ vựng, log ôn tập) của `user_id`.

    Tài khoản bị xoá rồi tạo lại cùng tên là một danh tính MỚI (id ngẫu nhiên) nên dòng cũ sẽ mồ côi
    vĩnh viễn nếu không dọn. Trả về số dòng đã xoá theo bảng (-1 nếu bảng đó lỗi).
    """
    uid = str(user_id or "")
    if uid in _UNSAFE_IDS:
        return {}
    from database import db  # import muộn: tránh kéo cả lớp DB khi chỉ cần hằng số ở trên

    purged: Dict[str, int] = {}
    for name in SYNC_TABLES:
        try:
            result = db.table(name).delete().eq("user_id", uid).execute()
            purged[name] = int(getattr(result, "count", 0) or 0)
        except Exception:
            logger.exception("Không xoá được dữ liệu %s của %s", name, uid)
            purged[name] = -1
    try:
        from services.novel_catalog import reader_counter
        reader_counter.invalidate()
    except Exception:
        pass
    return purged
