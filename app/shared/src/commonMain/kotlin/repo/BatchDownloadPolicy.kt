package com.truyencloud.shared.repo

/**
 * Chính sách theo dõi tác vụ dịch hàng loạt trên máy chủ.
 *
 * Bản cũ gọi `GET /jobs/{id}` mỗi 2 giây NGOÀI try/catch: một lần mất mạng hay 401 là ném lỗi thẳng
 * ra coroutine không có bộ bắt và sập ứng dụng. Nay lỗi được đếm; chờ giãn dần (lùi theo cấp số
 * nhân) và dừng hẳn sau [MAX_CONSECUTIVE_FAILURES] lần liên tiếp.
 */
object BatchPollPolicy {
    const val BASE_INTERVAL_MS = 2_000L
    const val MAX_BACKOFF_MS = 30_000L
    const val MAX_CONSECUTIVE_FAILURES = 6

    /** Tổng thời gian tối đa theo dõi một tác vụ. */
    const val MAX_TOTAL_MS = 30L * 60 * 1000

    /** Chờ bao lâu trước lần hỏi kế tiếp, biết đã thất bại liên tiếp [consecutiveFailures] lần. */
    fun nextDelayMs(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= 0) return BASE_INTERVAL_MS
        val shift = consecutiveFailures.coerceAtMost(10)
        return (BASE_INTERVAL_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    fun shouldGiveUp(consecutiveFailures: Int): Boolean = consecutiveFailures >= MAX_CONSECUTIVE_FAILURES
}

/** Câu thông báo kết quả tải hàng loạt. */
object BatchMessages {
    fun downloadSummary(result: OfflineDownloadResult, loggedIn: Boolean): String {
        if (result.total > 0 && result.failed == result.total) {
            return "Tải về máy thất bại: không chương nào tải được — hãy kiểm tra kết nối mạng."
        }
        val head = if (result.usable > 0) {
            "Đã tải ${result.usable}/${result.total} chương về thiết bị."
        } else {
            "Chưa có chương nào đọc offline được bằng tiếng Việt (0/${result.total})."
        }
        val parts = StringBuilder(head)
        if (result.originalOnly > 0) {
            parts.append(" ${result.originalOnly} chương chỉ có bản gốc (tiếng Trung) — chưa có bản dịch.")
        }
        if (result.provisional > 0) {
            parts.append(" ${result.provisional} chương mới có bản dịch tạm, sẽ được nâng cấp khi máy chủ dịch xong.")
        }
        if (result.failed > 0) {
            parts.append(" ${result.failed} chương không tải được.")
        }
        if (!loggedIn) {
            parts.append(" Đăng nhập để dịch hàng loạt trên máy chủ.")
        }
        return parts.toString()
    }
}
