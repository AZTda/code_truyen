package com.truyencloud.shared.api

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequestBody(
    val email: String,
    val password: String
)

@Serializable
data class ChangePasswordRequestBody(
    val old_password: String,
    val new_password: String
)

@Serializable
data class AuthRemoteUser(
    val id: String = "",
    val email: String = "",
    val display_name: String = "",
    val bio: String = "",
    val vip_tier: String = "",
    val avatar_url: String = "",
    val banner_url: String = "",
    val auth_provider: String = "guest",
    val is_logged_in: Boolean = true,
    val is_admin: Boolean = false
)

@Serializable
data class AuthRemoteResponse(
    val status: String = "success",
    val message: String = "",
    val token: String = "",
    val user: AuthRemoteUser? = null
)

/** Phản hồi của POST /v1/auth/refresh. `expires_at` là epoch GIÂY. */
@Serializable
data class RefreshRemoteResponse(
    val token: String = "",
    val expires_at: Long = 0L,
    val user: AuthRemoteUser? = null
)

/**
 * Đăng ký và đăng nhập mạng xã hội đã bị đóng ở máy chủ (403) nên không còn ở client.
 * Mọi hàm ném [ServerErrorException] khi máy chủ trả mã lỗi.
 */
class AuthApi {
    private val client = TruyenCloudApi.httpClient
    private val base: String get() = TruyenCloudApi.baseUrl

    suspend fun login(email: String, password: String): AuthRemoteResponse {
        return client.post("$base/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequestBody(email, password))
        }.throwIfError().body()
    }

    /** Đổi token còn hạn lấy token mới 30 ngày. 401 nếu token hết hạn hoặc tài khoản đã bị xoá. */
    suspend fun refresh(): RefreshRemoteResponse {
        return client.post("$base/v1/auth/refresh").throwIfError().body()
    }

    suspend fun changePassword(oldPassword: String, newPassword: String) {
        client.post("$base/v1/auth/change-password") {
            contentType(ContentType.Application.Json)
            setBody(ChangePasswordRequestBody(oldPassword, newPassword))
        }.throwIfError()
    }

    suspend fun me(): AuthRemoteUser {
        return client.get("$base/v1/auth/me").throwIfError().body()
    }
}
