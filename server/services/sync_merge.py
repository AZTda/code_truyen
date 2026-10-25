"""Shared last-write-wins helpers for the cross-device sync routes.

Progress, bookmarks and vocabulary all merge by the CLIENT's edit time (epoch
ms) and keep deletions as tombstones so an offline device cannot resurrect a
row another device already deleted.
"""

import threading
import time
from datetime import datetime, timezone
from typing import Any, Dict, Mapping, Optional

# Tombstone được trả về cho client trong 90 ngày; sau đó vẫn được GIỮ trong DB
# để chặn thiết bị offline lâu ngày đẩy lại bản cũ, chỉ là không gửi xuống nữa.
TOMBSTONE_TTL_MS = 90 * 24 * 3600 * 1000

# Giờ máy khách được phép NHANH hơn giờ server tối đa chừng này; lớn hơn thì bị kẹp lại (không từ
# chối: điện thoại lệch giờ vẫn phải đồng bộ được). Không kẹp thì một giờ sai năm 2031 sẽ thắng
# vĩnh viễn mọi lần sửa sau của mọi thiết bị khác.
MAX_FUTURE_SKEW_MS = 5 * 60 * 1000

# 9999-12-31T23:59:59.999Z — thời điểm lớn nhất `datetime` còn định dạng được.
MAX_EPOCH_MS = 253_402_300_799_999


def now_ms() -> int:
    return int(time.time() * 1000)


def iso_from_ms(ms: Any) -> str:
    # Không để giá trị ngoài miền (năm >= 10000, inf...) làm request sập 500.
    try:
        seconds = min(max(int(ms), 0), MAX_EPOCH_MS) / 1000
        return datetime.fromtimestamp(seconds, tz=timezone.utc).isoformat()
    except (TypeError, ValueError, OverflowError, OSError):
        return datetime.fromtimestamp(0, tz=timezone.utc).isoformat()


def ms_from_iso(value: Any) -> int:
    """Parse an ISO-8601 timestamp written by older server versions (0 if unknown)."""
    if not value or not isinstance(value, str):
        return 0
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return int(parsed.timestamp() * 1000)
    except (ValueError, OverflowError, OSError):
        return 0


def as_ms(value: Any) -> int:
    """Coerce a client-supplied millisecond value to an int in [0, MAX_EPOCH_MS]."""
    try:
        return min(max(0, int(value or 0)), MAX_EPOCH_MS)
    except (TypeError, ValueError, OverflowError):  # OverflowError: float('inf')
        return 0


def clamp_future(value: Any, received_ms: int) -> int:
    """Giờ do máy khách gửi, kẹp tối đa `received_ms + MAX_FUTURE_SKEW_MS`."""
    return min(as_ms(value), received_ms + MAX_FUTURE_SKEW_MS)


def incoming_wins(incoming_ts: int, incoming_deleted: bool,
                  stored_ts: int, stored_deleted: bool) -> bool:
    """Last-write-wins by client time; at equal timestamps a tombstone wins."""
    if incoming_ts != stored_ts:
        return incoming_ts > stored_ts
    return incoming_deleted or not stored_deleted


_BOOKKEEPING_KEYS = frozenset({"server_updated_at", "synced_at"})


def same_content(stored: Dict[str, Any], record: Dict[str, Any]) -> bool:
    # App gửi lại TOÀN BỘ dữ liệu mỗi lượt sync; ghi đè bản y hệt sẽ đẩy
    # server_updated_at lên và làm mọi lượt GET ?since= trả lại tất cả.
    return all(stored.get(k) == v for k, v in record.items() if k not in _BOOKKEEPING_KEYS)


def server_changed_ms(row: Mapping[str, Any]) -> int:
    """When the server last accepted a write for this row."""
    return as_ms(row.get("server_updated_at")) or ms_from_iso(row.get("synced_at"))


def trusted_ts(ts: int, row: Mapping[str, Any]) -> int:
    """Giờ sửa đã lưu của một dòng, không vượt quá lúc server nhận + dung sai.

    Dòng ghi từ bản cũ chưa có phép kẹp có thể mang giờ năm 2031; không giới hạn thì nó chặn mãi mọi
    lần ghi sau. Dòng mới luôn thoả `ts <= server_changed + dung sai` nên phép này không đổi gì với chúng.
    """
    received = server_changed_ms(row)
    return min(ts, received + MAX_FUTURE_SKEW_MS) if received else ts


class SyncClock:
    """Đồng hồ đơn điệu cho mốc đồng bộ phía server (`server_updated_at` và con trỏ `since`).

    Giờ tường (ms) có thể lặp lại: một lượt GET phát con trỏ T rồi một POST xử lý trong cùng mili giây
    đóng dấu T, và lượt kéo sau bị bỏ sót dòng đó nếu so `> T`. Ở đây mỗi lượt ghi nhận một mốc DUY NHẤT, tăng
    chặt, và con trỏ là mốc nhỏ nhất mà lượt ghi kế tiếp có thể nhận. Lượt kéo lấy `mốc >= con trỏ`: dòng ghi
    trước con trỏ có mốc < con trỏ (không trả lặp), dòng ghi sau có mốc >= con trỏ (không bỏ sót).
    """

    def __init__(self) -> None:
        self._last = 0
        self._lock = threading.Lock()

    def stamp(self) -> int:
        """Mốc cho MỘT lượt ghi (dùng chung cho mọi dòng của request đó)."""
        with self._lock:
            self._last = max(self._last + 1, now_ms())
            return self._last

    def cursor(self) -> int:
        """Con trỏ cho một lượt đọc = mốc nhỏ nhất mà lượt ghi tiếp theo có thể nhận (không đổi trạng thái)."""
        with self._lock:
            return max(self._last + 1, now_ms())


sync_clock = SyncClock()


def changed_since(row: Dict[str, Any], client_ts: int, since: Optional[int]) -> bool:
    # Lọc theo CẢ giờ client lẫn giờ server nhận: bản sửa offline (giờ client cũ
    # hơn `since`) nhưng tới server sau `since` vẫn phải được gửi cho máy khác.
    # `>=`: con trỏ là giá trị ĐẦU TIÊN chưa thấy, nên dòng đóng dấu đúng bằng con trỏ (ghi cùng mili giây
    # sau lượt kéo) vẫn được giao. Hợp nhất là lũy đẳng nên nếu có trả lặp một dòng thì cũng vô hại.
    if since is None:
        return True
    return trusted_ts(client_ts, row) >= since or server_changed_ms(row) >= since


def tombstone_expired(row: Dict[str, Any], deleted_at: int, current_ms: int) -> bool:
    last_change = max(
        trusted_ts(deleted_at, row), trusted_ts(as_ms(row.get("updated_at")), row), server_changed_ms(row)
    )
    return current_ms - last_change > TOMBSTONE_TTL_MS
