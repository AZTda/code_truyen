package com.truyencloud.shared.update

import com.truyencloud.shared.api.ServerUrlValidator

/**
 * Chỉ tải APK từ chính máy chủ đang cấu hình.
 *
 * Bản cũ tải BẤT KỲ URL nào máy chủ (hoặc kẻ giả mạo máy chủ) trả về rồi mở trình cài đặt —
 * một đường dẫn thẳng tới việc cài mã độc.
 */
object UpdateUrlPolicy {

    /**
     * Trả URL tuyệt đối hợp lệ, hoặc null nếu phải từ chối.
     *
     * - Đường dẫn tương đối chỉ nhận dạng `/static/...` và được ghép vào địa chỉ máy chủ.
     * - URL tuyệt đối phải là http(s), CÙNG host với máy chủ; máy chủ dùng HTTPS thì URL cũng phải HTTPS.
     */
    fun resolve(baseUrl: String, downloadUrl: String): String? {
        val url = downloadUrl.trim()
        if (url.isEmpty() || url.any { it.isWhitespace() || it == '\\' || it.code < 32 } || ".." in url) return null

        val base = ServerUrlValidator.parse(baseUrl) ?: return null
        val cleanBase = baseUrl.trim().trimEnd('/')

        if ("://" !in url) {
            val path = if (url.startsWith("/")) url else "/$url"
            return if (path.startsWith("/static/")) cleanBase + path else null
        }

        val target = ServerUrlValidator.parse(url.substringBefore('?').substringBefore('#')) ?: return null
        if (!target.host.equals(base.host, ignoreCase = true)) return null
        if (base.scheme == "https" && target.scheme != "https") return null
        return url
    }
}
