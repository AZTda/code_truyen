package com.truyencloud.shared.repo

import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.getCurrentEpochMillis
import kotlin.concurrent.Volatile

/**
 * Cổng cho việc dịch trước các chương kế tiếp ("translate-ahead").
 *
 * - Token bị máy chủ từ chối (401) thì thôi dịch trước CHỈ với token đó: đăng nhập lại ra token mới nên tự bật lại.
 *   Bản cũ đặt một cờ vĩnh viễn trong suốt vòng đời tiến trình — sau khi hết phiên rồi đăng nhập lại, chương N+1 / N+2
 *   vẫn không được dịch trước cho tới khi tắt hẳn ứng dụng.
 * - Máy chủ báo bận / vượt tần suất (429 / 503) thì nghỉ [COOL_DOWN_MS]: yêu cầu dịch trước dùng chung xô "ai"
 *   (12 lượt / phút) với bản dịch người đọc đang chờ, nên dồn dập thêm chỉ làm chương đang đọc bị chặn theo.
 */
internal class TranslateAheadGate(
    private val currentToken: () -> String = { TruyenCloudApi.authToken },
    private val clock: () -> Long = { getCurrentEpochMillis() }
) {
    @Volatile
    private var rejectedToken: String? = null

    @Volatile
    private var coolDownUntil = 0L

    /** Có nên gửi yêu cầu dịch trước lúc này không (đã đăng nhập, token chưa bị từ chối, không đang nghỉ)? */
    fun canTranslate(): Boolean {
        val token = currentToken()
        if (token.isBlank() || token == rejectedToken) return false
        val now = clock()
        // Đồng hồ máy chỉnh lùi quá xa thì đừng nghỉ vô hạn.
        return now >= coolDownUntil || coolDownUntil - now > COOL_DOWN_MS
    }

    /** [token] là token đã dùng cho yêu cầu bị từ chối (không phải token hiện tại, nếu người dùng vừa đăng nhập lại). */
    fun onAuthRejected(token: String) {
        rejectedToken = token
    }

    fun onBusy() {
        coolDownUntil = clock() + COOL_DOWN_MS
    }

    companion object {
        const val COOL_DOWN_MS = 60_000L
    }
}
