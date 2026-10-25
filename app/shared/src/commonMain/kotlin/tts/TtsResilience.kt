package com.truyencloud.shared.tts

/**
 * Cầu dao giọng online -> giọng máy.
 *
 * Trước đây mỗi đoạn đều thử online lại, lỗi thì đọc offline, đoạn sau lại thử
 * online... giọng đổi qua đổi lại từng câu. Nay một lần lỗi là ở hẳn offline
 * trong [cooldownMs]; hết hạn thì thử online lại một lần (thành công mới đóng cầu dao).
 */
class OnlineTtsCircuitBreaker(private val cooldownMs: Long = DEFAULT_COOLDOWN_MS) {
    private var trippedAtMs: Long? = null

    val isTripped: Boolean get() = trippedAtMs != null

    fun allowOnline(nowMs: Long): Boolean {
        val tripped = trippedAtMs ?: return true
        return nowMs - tripped >= cooldownMs
    }

    fun recordFailure(nowMs: Long) {
        trippedAtMs = nowMs
    }

    fun recordSuccess() {
        trippedAtMs = null
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 3 * 60 * 1000L
    }
}

/** Đếm lỗi LIÊN TIẾP của bộ máy đọc; đủ [maxFailures] lần thì phải dừng và báo lỗi. */
class ConsecutiveFailureTracker(private val maxFailures: Int = 3) {
    var count: Int = 0
        private set

    /** Ghi một lần lỗi; trả về true nếu đã chạm ngưỡng phải bỏ cuộc. */
    fun recordFailure(): Boolean {
        count++
        return count >= maxFailures
    }

    fun reset() {
        count = 0
    }
}

/**
 * Địa chỉ máy chủ giọng online đang dùng, dùng chung cho Android và iOS. Đổi sang địa chỉ KHÁC thì lỗi online của máy
 * chủ cũ không còn liên quan: mở lại cầu dao ngay, kẻo người dùng vừa sửa địa chỉ xong vẫn nghe giọng máy ~3 phút.
 */
class TtsServerEndpoint(
    initialUrl: String,
    private val breaker: OnlineTtsCircuitBreaker,
    private val onChanged: (url: String) -> Unit = {}
) {
    var url: String = initialUrl
        private set

    /** Trả true nếu địa chỉ thật sự đổi (rỗng hoặc trùng địa chỉ hiện tại thì bỏ qua). */
    fun update(raw: String): Boolean {
        val clean = raw.trim().trimEnd('/')
        if (clean.isBlank() || clean == url) return false
        url = clean
        breaker.recordSuccess()
        onChanged(clean)
        return true
    }
}

/**
 * Đồng hồ chờ tải của mảnh online ở đầu hàng đợi: CHỈ chạy khi người dùng đang muốn nghe. Bản cũ tính từ lúc mảnh vào
 * hàng đợi, nên tạm dừng lâu rồi bấm Phát bị coi là "treo mạng", mảnh khoẻ bị bỏ và giọng online bị tắt oan 3 phút.
 */
class TtsLoadWatchdog(private val timeoutMs: Long) {
    private var waitedMs = 0L
    private var runningSince: Long? = null

    /** Mảnh mới lên đầu hàng đợi: đếm lại từ 0, chạy luôn nếu [running]. */
    fun restart(nowMs: Long, running: Boolean) {
        waitedMs = 0L
        runningSince = if (running) nowMs else null
    }

    /** Người dùng bấm Phát ([running] = true) hay Tạm dừng; thời gian lúc đang dừng không được tính. */
    fun setRunning(nowMs: Long, running: Boolean) {
        val since = runningSince
        if (running) {
            if (since == null) runningSince = nowMs
        } else if (since != null) {
            waitedMs += nowMs - since
            runningSince = null
        }
    }

    fun isExpired(nowMs: Long): Boolean = waitedMs + (runningSince?.let { nowMs - it } ?: 0L) > timeoutMs
}
