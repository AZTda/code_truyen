package com.truyencloud.shared.api

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

/**
 * Ném [ServerErrorException] khi máy chủ trả mã lỗi.
 *
 * Client Ktor ở đây không bật `expectSuccess`, nên nếu chỉ gọi `.body<T>()` thì phản hồi lỗi
 * `{"detail": "..."}` vẫn được giải mã "thành công" thành một đối tượng toàn giá trị mặc định
 * (ví dụ danh mục model rỗng) — lỗi bị nuốt và dữ liệu đang hiển thị bị ghi đè.
 */
internal suspend fun HttpResponse.throwIfError(): HttpResponse {
    if (status.isSuccess()) return this
    val detail = try {
        FriendlyErrors.parseDetail(bodyAsText())
    } catch (_: Exception) {
        null
    }
    val retryAfter = headers[HttpHeaders.RetryAfter]?.trim()?.toIntOrNull()
    throw ServerErrorException(status.value, detail, retryAfter)
}
