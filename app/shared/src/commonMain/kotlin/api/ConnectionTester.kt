package com.truyencloud.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ConnectionResult(val ok: Boolean, val message: String)

/**
 * "Kiểm tra kết nối" trong Cài đặt: gọi GET /v1/health của địa chỉ đang nhập (chưa lưu).
 *
 * Dùng client RIÊNG, không đính kèm token: địa chỉ người dùng vừa gõ có thể sai hoặc không đáng tin,
 * không được gửi token đăng nhập sang đó.
 */
object ConnectionTester {
    private const val TIMEOUT_MS = 8_000L

    suspend fun check(baseUrl: String): ConnectionResult {
        val client = HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = TIMEOUT_MS
                connectTimeoutMillis = TIMEOUT_MS
                socketTimeoutMillis = TIMEOUT_MS
            }
        }
        return try {
            val response = client.get(baseUrl.trimEnd('/') + "/v1/health")
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                ConnectionResult(
                    ok = false,
                    message = FriendlyErrors.forStatus(
                        response.status.value,
                        FriendlyErrors.parseDetail(body),
                        response.headers[HttpHeaders.RetryAfter]?.toIntOrNull(),
                        ErrorContext.CONNECTION_TEST
                    )
                )
            } else {
                interpretHealthBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionResult(false, FriendlyErrors.of(e, ErrorContext.CONNECTION_TEST))
        } finally {
            client.close()
        }
    }

    /** `{"status":"healthy","version":"1.0.0"}` → thành công; mọi thứ khác không phải máy chủ TruyenCloud. */
    fun interpretHealthBody(body: String): ConnectionResult {
        val notReady = ConnectionResult(false, "Địa chỉ này không phải máy chủ TruyenCloud hoặc máy chủ chưa sẵn sàng.")
        val json = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return notReady
        if ((json["status"] as? JsonPrimitive)?.content != "healthy") return notReady
        val version = (json["version"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        return ConnectionResult(true, "Kết nối thành công" + (version?.let { " — máy chủ v$it" } ?: "") + ".")
    }
}
