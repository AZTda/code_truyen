package com.truyencloud.shared.repo

/**
 * Bộ giãn cách cho các lượt đồng bộ: tối đa một lượt mỗi [minIntervalMs], trừ khi ép chạy
 * hoặc tài khoản vừa đổi (vừa đăng nhập).
 *
 * Trước đây mỗi lần mở chương, mỗi lần lưu dấu trang và CẢ onPause lẫn onStop đều chạy một
 * lượt đẩy + kéo đầy đủ toàn bộ bookmark.
 *
 * Lớp thuần (đồng hồ được truyền vào) nên kiểm thử được mà không cần coroutine.
 */
class SyncThrottle(
    private val minIntervalMs: Long,
    private val clock: () -> Long
) {
    private var lastStartedAt: Long? = null
    private var lastAccount: String? = null

    /** Có nên chạy một lượt ngay bây giờ không. */
    fun shouldRun(force: Boolean = false, accountKey: String = ""): Boolean {
        if (force) return true
        val last = lastStartedAt ?: return true
        if (lastAccount != accountKey) return true
        val elapsed = clock() - last
        // Đồng hồ máy bị chỉnh lùi (elapsed < 0): đừng chặn đến khi nó "đuổi kịp" mốc cũ.
        return elapsed < 0 || elapsed >= minIntervalMs
    }

    /** Ghi nhận lượt vừa bắt đầu (tính cả lượt thất bại, để không dồn dập gọi khi máy chủ đang lỗi). */
    fun markStarted(accountKey: String = "") {
        lastStartedAt = clock()
        lastAccount = accountKey
    }

    /** Còn bao lâu nữa được chạy lượt không ép buộc kế tiếp (0 = chạy được ngay). */
    fun millisUntilNext(accountKey: String = ""): Long {
        val last = lastStartedAt ?: return 0L
        if (lastAccount != accountKey) return 0L
        val elapsed = clock() - last
        if (elapsed < 0) return 0L
        return (minIntervalMs - elapsed).coerceIn(0L, minIntervalMs)
    }

    fun reset() {
        lastStartedAt = null
        lastAccount = null
    }
}
