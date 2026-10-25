package com.truyencloud.shared.api

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/**
 * Lỗi HTTP kèm mã trạng thái, để tầng trên phân biệt 401 (hết phiên đăng nhập) với mất
 * mạng hay máy chủ bận.
 *
 * Ktor KHÔNG tự ném lỗi với mã 4xx/5xx, và JSON lỗi của FastAPI (`{"detail": "..."}`) lại
 * giải mã "thành công" vào các lớp phản hồi có giá trị mặc định (ví dụ `status = "success"`),
 * nên trước đây một phản hồi 401 bị coi là đồng bộ thành công và cờ "chưa đẩy" bị gỡ oan.
 */
class ApiHttpException(val statusCode: Int, message: String) : Exception(message) {
    val isUnauthorized: Boolean get() = statusCode == 401
    val isNotFound: Boolean get() = statusCode == 404
}

/** Ném [ApiHttpException] nếu phản hồi không phải 2xx. */
internal suspend fun HttpResponse.requireSuccess(what: String) {
    if (status.isSuccess()) return
    val detail = try { bodyAsText().take(300) } catch (_: Exception) { "" }
    val suffix = if (detail.isBlank()) "" else ": $detail"
    throw ApiHttpException(status.value, "$what thất bại (mã ${status.value})$suffix")
}
