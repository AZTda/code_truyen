package com.truyencloud.shared.repo

enum class SyncTone { NEUTRAL, OK, WARNING, ERROR }

data class SyncLine(val title: String, val detail: String, val tone: SyncTone)

/** Biến [SyncStatus.State] thật thành dòng chữ cho Cài đặt, thay cho nhãn "Đã bật" cố định. */
object SyncStatusText {

    fun describe(isLoggedIn: Boolean, state: SyncStatus.State, nowMillis: Long): SyncLine {
        if (state.sessionExpired) {
            return SyncLine(
                title = "Phiên hết hạn — đăng nhập lại",
                detail = "Dữ liệu trên máy vẫn được giữ nguyên.",
                tone = SyncTone.ERROR
            )
        }
        if (!isLoggedIn) {
            return SyncLine(
                title = "Chưa đăng nhập",
                detail = "Đăng nhập để đồng bộ tiến độ đọc và tủ sách giữa các thiết bị.",
                tone = SyncTone.NEUTRAL
            )
        }
        val error = state.lastError
        if (error != null && state.lastErrorAtMillis >= state.lastSuccessAtMillis) {
            return SyncLine(
                title = "Đồng bộ chưa thành công",
                detail = "${error.take(120)} (${relativeTime(nowMillis - state.lastErrorAtMillis)})",
                tone = SyncTone.WARNING
            )
        }
        if (state.lastSuccessAtMillis > 0L) {
            return SyncLine(
                title = "Đã đồng bộ",
                detail = "Lần cuối: ${relativeTime(nowMillis - state.lastSuccessAtMillis)}",
                tone = SyncTone.OK
            )
        }
        return SyncLine(
            title = "Chưa đồng bộ lần nào",
            detail = "Bấm \"Đồng bộ ngay\" để đẩy và kéo dữ liệu.",
            tone = SyncTone.NEUTRAL
        )
    }

    /** "vừa xong", "5 phút trước", "3 giờ trước", "2 ngày trước". Độ lệch đồng hồ âm coi như vừa xong. */
    fun relativeTime(deltaMillis: Long): String {
        val seconds = deltaMillis / 1000
        return when {
            seconds < 45 -> "vừa xong"
            seconds < 3600 -> "${maxOf(1, (seconds + 30) / 60)} phút trước"
            seconds < 86_400 -> "${(seconds + 1800) / 3600} giờ trước"
            else -> "${seconds / 86_400} ngày trước"
        }
    }
}
