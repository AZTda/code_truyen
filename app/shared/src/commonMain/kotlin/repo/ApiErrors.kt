package com.truyencloud.shared.repo

import com.truyencloud.shared.api.ApiHttpException

/**
 * Phân loại lỗi gọi máy chủ.
 *
 * Các API cũ (TranslateApi, ChapterApi) ném `Exception` thuần chỉ chứa câu thông báo, không
 * kèm mã HTTP, nên ngoài [ApiHttpException] còn phải nhận dạng theo nội dung thông báo.
 */
object ApiErrors {
    /** Hết phiên / chưa đăng nhập (401). */
    fun isAuthError(t: Throwable): Boolean {
        if (t is ApiHttpException) return t.statusCode == 401
        val m = t.message?.lowercase() ?: return false
        return m.contains("(401)") ||
            m.contains("mã lỗi 401") ||
            m.contains("mã 401") ||
            m.contains("unauthorized") ||
            m.contains("yêu cầu đăng nhập") ||
            m.contains("token không hợp lệ")
    }

    /**
     * Máy chủ đang quá tải hoặc chặn do vượt tần suất (429 / 503) — nơi gọi nền nên lùi lại một lúc thay vì gửi tiếp.
     * Nhận dạng cả theo câu `detail` tiếng Việt của máy chủ vì các API cũ chỉ ném `Exception(detail)` không kèm mã.
     */
    fun isBusyOrRateLimited(t: Throwable): Boolean {
        if (t is ApiHttpException) return t.statusCode == 429 || t.statusCode == 503
        val m = t.message?.lowercase() ?: return false
        return m.contains("(429)") || m.contains("(503)") ||
            m.contains("mã lỗi 429") || m.contains("mã lỗi 503") ||
            m.contains("mã 429") || m.contains("mã 503") ||
            m.contains("thao tác quá nhanh") ||
            m.contains("tạm thời chưa dịch được") ||
            m.contains("lỗi dịch antigravity")
    }

    /**
     * Máy chủ từ chối chính DÒNG dữ liệu này (400 / 409 / 413 / 422): gửi lại y nguyên sẽ không bao giờ được chấp nhận,
     * khác với 401 / 408 / 429 / 5xx (thử lại sau là được). Cố ý KHÔNG gồm 404 / 405: tuyến sai hay proxy cấu hình hỏng
     * làm hỏng mọi lời gọi, không phải riêng dòng này.
     */
    fun isRowRejected(t: Throwable): Boolean =
        t is ApiHttpException && (t.statusCode == 400 || t.statusCode == 409 || t.statusCode == 413 || t.statusCode == 422)

    /** Tài nguyên không tồn tại (404) — vd. chương vượt quá cuối truyện. */
    fun isNotFound(t: Throwable): Boolean {
        if (t is ApiHttpException) return t.statusCode == 404
        val m = t.message?.lowercase() ?: return false
        return m.contains("(404)") ||
            m.contains("mã lỗi 404") ||
            m.contains("mã 404") ||
            m.contains("không tìm thấy chương") ||
            m.contains("không tìm thấy tác vụ")
    }

    /** Câu báo lỗi ngắn gọn để hiện cho người dùng / ghi vào [SyncStatus]. */
    fun describe(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t::class.simpleName ?: "Lỗi không rõ"
}
