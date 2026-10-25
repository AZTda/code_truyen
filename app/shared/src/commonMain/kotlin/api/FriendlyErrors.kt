package com.truyencloud.shared.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lỗi HTTP từ máy chủ cho các API tài khoản/cài đặt/cập nhật. Giữ nguyên mã trạng thái, `detail`
 * (FastAPI đã trả tiếng Việt) và `Retry-After` để giao diện chọn thông báo phù hợp, thay vì hiện chuỗi
 * thô kiểu "HTTP 429". (Các API đồng bộ dùng [ApiHttpException] riêng — [FriendlyErrors] hiểu cả hai.)
 */
class ServerErrorException(
    val status: Int,
    val serverDetail: String? = null,
    val retryAfterSeconds: Int? = null
) : Exception("HTTP $status" + (serverDetail?.let { ": $it" } ?: ""))

/** Lỗi do chính ứng dụng tạo ra với thông báo ĐÃ là tiếng Việt thân thiện — hiển thị nguyên văn. */
class UserFacingException(message: String) : Exception(message)

/** Cùng một mã lỗi nhưng ý nghĩa khác nhau tuỳ nơi gọi (401 lúc đăng nhập ≠ 401 lúc đồng bộ). */
enum class ErrorContext { GENERIC, LOGIN, CHANGE_PASSWORD, ADMIN_ACTION, UPDATE_CHECK, CONNECTION_TEST }

/**
 * Đổi mọi lỗi kỹ thuật (Unable to resolve host, timeout, HTTP 429/401/400/5xx...) thành câu
 * tiếng Việt người dùng hiểu được. Hàm thuần — kiểm thử không cần mạng.
 */
object FriendlyErrors {

    const val MSG_OFFLINE = "Không có kết nối mạng. Hãy kiểm tra Wi-Fi hoặc dữ liệu di động."
    const val MSG_UNKNOWN_HOST = "Không tìm thấy máy chủ. Hãy kiểm tra kết nối mạng hoặc địa chỉ máy chủ."
    const val MSG_TIMEOUT = "Máy chủ phản hồi quá chậm. Vui lòng thử lại sau ít phút."
    const val MSG_INTERRUPTED = "Kết nối bị gián đoạn giữa chừng. Vui lòng thử lại."
    const val MSG_REFUSED = "Không kết nối được tới máy chủ. Máy chủ có thể đang tắt hoặc địa chỉ chưa đúng."
    const val MSG_CLEARTEXT = "Android không cho phép kết nối HTTP không mã hoá tới địa chỉ này. Hãy dùng địa chỉ HTTPS."
    const val MSG_TLS = "Kết nối bảo mật (HTTPS) không thành công. Hãy kiểm tra địa chỉ hoặc chứng chỉ của máy chủ."
    const val MSG_BAD_PAYLOAD = "Máy chủ trả về dữ liệu không đọc được. Có thể địa chỉ máy chủ chưa đúng."
    const val MSG_GENERIC = "Đã xảy ra lỗi. Vui lòng thử lại."
    const val MSG_SESSION_EXPIRED = "Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại."
    const val MSG_ADMIN_ONLY = "Chỉ quản trị viên mới được thực hiện thao tác này."

    /** Điểm vào duy nhất: nhận bất kỳ Throwable nào và trả về một câu thân thiện. */
    fun of(error: Throwable, context: ErrorContext = ErrorContext.GENERIC): String {
        (error as? UserFacingException)?.let { return it.message.orEmpty() }
        val chain = generateSequence(error) { it.cause }.take(6).toList()
        chain.filterIsInstance<ServerErrorException>().firstOrNull()?.let {
            return forStatus(it.status, it.serverDetail, it.retryAfterSeconds, context)
        }
        chain.filterIsInstance<ApiHttpException>().firstOrNull()?.let {
            // Thông báo của ApiHttpException có dạng "<việc> thất bại (mã N): <thân phản hồi>"
            val body = it.message.orEmpty().substringAfter(": ", "")
            return forStatus(it.statusCode, parseDetail(body), null, context)
        }
        return forNetworkText(collectText(error))
    }

    fun forStatus(
        status: Int,
        serverDetail: String?,
        retryAfterSeconds: Int? = null,
        context: ErrorContext = ErrorContext.GENERIC
    ): String {
        val detail = serverDetail?.trim()?.takeIf { isPresentable(it) }
        return when {
            status == 429 -> if (context == ErrorContext.LOGIN) {
                "Bạn đã thử đăng nhập quá nhiều lần. Vui lòng đợi ${waitText(retryAfterSeconds ?: 300)} rồi thử lại."
            } else {
                "Máy chủ đang bận. Vui lòng thử lại sau ${waitText(retryAfterSeconds ?: 30)}."
            }
            status == 401 -> when (context) {
                ErrorContext.LOGIN -> detail ?: "Tên đăng nhập hoặc mật khẩu không chính xác."
                else -> MSG_SESSION_EXPIRED
            }
            status == 403 -> when (context) {
                ErrorContext.ADMIN_ACTION -> MSG_ADMIN_ONLY
                ErrorContext.LOGIN -> detail ?: "Tài khoản do quản trị viên cấp, không hỗ trợ tự đăng ký."
                else -> "Bạn không có quyền thực hiện thao tác này."
            }
            status == 400 || status == 422 -> detail ?: "Thông tin gửi lên chưa hợp lệ. Vui lòng kiểm tra lại."
            status == 404 -> "Không tìm thấy dữ liệu trên máy chủ."
            status == 408 -> MSG_TIMEOUT
            status == 502 || status == 503 || status == 504 ->
                "Máy chủ đang bảo trì hoặc khởi động lại (mã $status). Vui lòng thử lại sau ít phút."
            status in 500..599 -> "Máy chủ đang gặp sự cố (mã $status). Vui lòng thử lại sau ít phút."
            else -> "Có lỗi xảy ra (mã $status). Vui lòng thử lại."
        }
    }

    /** Nhận dạng lỗi mạng theo tên lớp và nội dung thông báo (Android/OkHttp lẫn iOS/NSURLError). */
    fun forNetworkText(text: String): String {
        val t = text.lowercase()
        return when {
            "cleartext" in t -> MSG_CLEARTEXT
            "unable to resolve host" in t || "unknownhost" in t || "nodename nor servname" in t ||
                "no address associated" in t || "hostname could not be found" in t || "-1003" in t -> MSG_UNKNOWN_HOST
            "timeout" in t || "timed out" in t || "-1001" in t -> MSG_TIMEOUT
            "javax.net.ssl" in t || "sslexception" in t || "sslhandshake" in t || "sslpeer" in t ||
                "certificate" in t || "handshake" in t || "trust anchor" in t ||
                "-1200" in t || "-1201" in t || "-1202" in t || "-1203" in t -> MSG_TLS
            "connection refused" in t || "failed to connect" in t || "econnrefused" in t ||
                "could not connect to the server" in t || "-1004" in t -> MSG_REFUSED
            "network is unreachable" in t || "no route to host" in t || "not connected to the internet" in t ||
                "internet connection appears to be offline" in t || "-1009" in t -> MSG_OFFLINE
            "connection abort" in t || "connection reset" in t || "software caused" in t ||
                "connection was lost" in t || "-1005" in t || "unexpected end of stream" in t -> MSG_INTERRUPTED
            "serializationexception" in t || "jsonconvertexception" in t || "notransformationfound" in t ||
                "unexpected json" in t || "jsonexception" in t -> MSG_BAD_PAYLOAD
            else -> MSG_GENERIC
        }
    }

    /** Lấy trường `detail` từ thân phản hồi lỗi FastAPI (chuỗi hoặc danh sách lỗi kiểm tra đầu vào). */
    fun parseDetail(body: String): String? {
        if (body.isBlank()) return null
        return try {
            val root = Json.parseToJsonElement(body) as? JsonObject ?: return null
            when (val detail = root["detail"]) {
                is JsonNull -> null
                is JsonPrimitive -> detail.content.takeIf { it.isNotBlank() }
                is JsonArray -> ((detail.firstOrNull() as? JsonObject)?.get("msg") as? JsonPrimitive)?.content
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** "8 phút", "45 giây"... Làm tròn lên để không hẹn giờ non. */
    fun waitText(seconds: Int): String {
        val s = seconds.coerceAtLeast(1)
        return when {
            s < 60 -> "$s giây"
            s < 3600 -> "${(s + 59) / 60} phút"
            else -> "${(s + 3599) / 3600} giờ"
        }
    }

    private fun collectText(error: Throwable): String = generateSequence(error) { it.cause }
        .take(6)
        .joinToString(" | ") { "${it::class.simpleName.orEmpty()} ${it.message.orEmpty()}" }

    /** Chỉ hiện lại `detail` của máy chủ khi nó ngắn và không giống HTML/ngăn xếp lỗi. */
    private fun isPresentable(text: String): Boolean =
        text.length in 1..200 && '<' !in text && "Traceback" !in text
}
