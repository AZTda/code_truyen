package com.truyencloud.shared.api

/** Trạng thái phiên đăng nhập của thiết bị. */
enum class SessionState { GUEST, ACTIVE, EXPIRED }

enum class SessionSignal { LOGGED_IN, LOGGED_OUT, TOKEN_REJECTED }

/** Máy trạng thái thuần: không chạm mạng, không chạm đĩa — kiểm thử trực tiếp được. */
object SessionStateMachine {
    fun reduce(current: SessionState, signal: SessionSignal): SessionState = when (signal) {
        SessionSignal.LOGGED_IN -> SessionState.ACTIVE
        SessionSignal.LOGGED_OUT -> SessionState.GUEST
        // Khách không có phiên để mất; nhiều 401 đồng thời chỉ tính một lần hết hạn.
        SessionSignal.TOKEN_REJECTED -> if (current == SessionState.ACTIVE) SessionState.EXPIRED else current
    }
}

object SessionExpiryPolicy {

    /** Đường dẫn đăng nhập: 401 ở đây nghĩa là sai mật khẩu, không phải phiên hết hạn. */
    fun isLoginPath(path: String): Boolean = path.trimEnd('/').endsWith("/auth/login")

    private fun isMePath(path: String): Boolean = path.trimEnd('/').endsWith("/auth/me")

    /**
     * Một phản hồi có phải dấu hiệu phiên đã hết hạn không?
     *
     * - 401 (token hết hạn, bị thu hồi, sai chữ ký), hoặc 404 của `/auth/me` (token còn hợp lệ nhưng
     *   tài khoản đã bị xoá: máy chủ trả 404 "Tài khoản không còn tồn tại." chứ không phải 401).
     * - Yêu cầu phải ĐÃ mang token: khách gọi endpoint cần đăng nhập nhận 401 là chuyện bình thường.
     * - Không phải lời gọi đăng nhập.
     * - Token trong yêu cầu phải còn là token hiện hành: phản hồi muộn của phiên cũ không được phép
     *   huỷ phiên mới vừa đăng nhập.
     */
    fun isExpiry(statusCode: Int, requestToken: String?, currentToken: String, path: String): Boolean {
        val rejected = statusCode == 401 || (statusCode == 404 && isMePath(path))
        if (!rejected) return false
        if (requestToken.isNullOrBlank() || currentToken.isBlank()) return false
        if (isLoginPath(path)) return false
        return requestToken == currentToken
    }

    /** Rút token từ giá trị header `Authorization: Bearer <token>`. */
    fun bearerOf(authorizationHeader: String?): String? {
        val value = authorizationHeader?.trim() ?: return null
        if (!value.startsWith("Bearer ", ignoreCase = true)) return null
        return value.substring(7).trim().ifEmpty { null }
    }
}

/** Khi nào cần gia hạn token? Token sống 30 ngày, gia hạn tối đa mỗi 24 giờ khi mở app. */
object SessionRefreshPolicy {
    const val INTERVAL_MILLIS: Long = 24L * 60 * 60 * 1000

    fun isDue(hasToken: Boolean, nowMillis: Long, lastRefreshMillis: Long): Boolean {
        if (!hasToken) return false
        if (lastRefreshMillis <= 0L) return true
        // Đồng hồ máy bị chỉnh lùi: coi như đã đến hạn thay vì chờ vô hạn
        if (nowMillis < lastRefreshMillis) return true
        return nowMillis - lastRefreshMillis >= INTERVAL_MILLIS
    }
}
