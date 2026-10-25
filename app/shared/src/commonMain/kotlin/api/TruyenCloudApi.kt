package com.truyencloud.shared.api

import com.truyencloud.shared.repo.SyncStatus
import io.ktor.client.*
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpCallValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.*
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.serialization.json.Json

/** Sự kiện phiên đăng nhập toàn ứng dụng. */
sealed interface SessionEvent {
    /** Máy chủ từ chối token đang dùng (hết hạn, bị thu hồi hoặc tài khoản đã bị xoá). */
    data object Expired : SessionEvent
}

object TruyenCloudApi {
    @Volatile
    var baseUrl: String = ServerUrls.DEFAULT

    @Volatile
    private var token: String = ""

    /**
     * Token phiên đã ký, do máy chủ cấp khi đăng nhập.
     *
     * Mọi endpoint GHI trên máy chủ nay đều yêu cầu token này. `UserRepository` đặt giá trị
     * sau khi đăng nhập và khôi phục lại từ kho an toàn lúc khởi động. Gán giá trị mới cũng
     * cập nhật [sessionState].
     */
    var authToken: String
        get() = token
        set(value) {
            token = value
            _sessionState.value = SessionStateMachine.reduce(
                _sessionState.value,
                if (value.isBlank()) SessionSignal.LOGGED_OUT else SessionSignal.LOGGED_IN
            )
        }

    private val _sessionState = MutableStateFlow(SessionState.GUEST)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _sessionEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 4)

    /** Phát đúng MỘT lần mỗi khi phiên chuyển ACTIVE → EXPIRED. */
    val sessionEvents: SharedFlow<SessionEvent> = _sessionEvents.asSharedFlow()

    /** `UserRepository` đăng ký để xoá token đã lưu và đưa hồ sơ về chế độ khách (giữ nguyên dữ liệu máy). */
    @Volatile
    var onSessionExpired: (() -> Unit)? = null

    /** `AccountSession` đăng ký để tự động gia hạn token ngầm (Silent Token Refresh) khi gặp 401 trước khi huỷ phiên. */
    @Volatile
    var onAttemptSilentRefresh: (suspend (String) -> Boolean)? = null

    internal suspend fun handleResponseAuthCheck(statusCode: Int, requestToken: String?, path: String) {
        if (!SessionExpiryPolicy.isExpiry(statusCode, requestToken, token, path)) return
        val isRefreshEndpoint = path.trimEnd('/').endsWith("/auth/refresh")
        if (statusCode == 401 && !isRefreshEndpoint && !requestToken.isNullOrBlank()) {
            val refreshed = try {
                onAttemptSilentRefresh?.invoke(requestToken) == true
            } catch (_: Exception) {
                false
            }
            if (refreshed) return
        }
        handleRejectedToken(statusCode, requestToken, path)
    }

    /**
     * Được gọi cho phản hồi 401 (và 404 của /auth/me). Chỉ huỷ phiên khi [SessionExpiryPolicy] xác nhận đây là
     * token của chính mình bị từ chối — xem chính sách để biết các trường hợp KHÔNG tính là hết hạn.
     */
    internal fun handleRejectedToken(statusCode: Int, requestToken: String?, path: String) {
        if (!SessionExpiryPolicy.isExpiry(statusCode, requestToken, token, path)) return
        val before = _sessionState.getAndUpdate { SessionStateMachine.reduce(it, SessionSignal.TOKEN_REJECTED) }
        // Token đã bị từ chối: các lời gọi sau đi với tư cách khách thay vì lặp lại 401
        token = ""
        if (before == SessionState.ACTIVE) {
            SyncStatus.reportSessionExpired()
            _sessionEvents.tryEmit(SessionEvent.Expired)
            onSessionExpired?.invoke()
        }
    }

    // -----------------------------------------------------------------------
    // Thời gian chờ
    // -----------------------------------------------------------------------
    // Máy chủ đặt timeout dịch tới 300-900 giây (xem models_cache.json), trong khi
    // client cũ chỉ chờ 90 giây cho MỌI yêu cầu. Kết quả: dịch nguyên một chương
    // gần như luôn báo lỗi ở client trong lúc máy chủ vẫn đang dịch và sẽ lưu thành
    // công — người dùng thấy "Lỗi dịch" rồi bấm lại, sinh thêm một tiến trình nữa.
    //
    // Nay tách theo loại yêu cầu: gọi thường nhanh, gọi AI chờ lâu hơn.

    /** Yêu cầu thường (danh sách truyện, mục lục, tiến độ...). */
    const val DEFAULT_REQUEST_TIMEOUT_MS: Long = 45_000L

    /** Gọi AI ngắn: tra từ, phân tích câu, sinh kịch bản. */
    const val AI_REQUEST_TIMEOUT_MS: Long = 120_000L

    /** Gọi AI theo lô: tra trước nhiều từ của cả chương. */
    const val AI_BATCH_TIMEOUT_MS: Long = 240_000L

    /** Dịch / chuyển thể nguyên chương — phải bao trùm timeout phía máy chủ. */
    const val TRANSLATE_TIMEOUT_MS: Long = 900_000L

    fun getImageProxyUrl(rawUrl: String): String {
        val clean = rawUrl.trim()
        if (clean.isBlank()) return "$baseUrl/static/fallback_cover.jpg"
        if (clean.startsWith(baseUrl)) return clean
        if (clean.startsWith("/static/") || clean.startsWith("static/")) {
            val path = if (clean.startsWith("/")) clean else "/$clean"
            return "$baseUrl$path"
        }
        if (clean.contains("/v1/images/proxy?url=")) return clean
        return "$baseUrl/v1/images/proxy?url=${clean.encodeURLParameter()}"
    }

    /**
     * URL phát âm.
     *
     * `lang` được truyền TƯỜNG MINH: heuristic đoán ngôn ngữ phía máy chủ cần ít nhất
     * hai từ tiếng Anh phổ biến trong mẫu, nên một từ đơn như "ephemeral" luôn bị
     * đoán nhầm thành tiếng Việt và đọc bằng giọng Việt.
     */
    fun getTtsUrl(
        text: String,
        voice: String = "mai_phuong",
        speed: Float = 1.0f,
        lang: String? = null
    ): String {
        val params = buildString {
            append("text=").append(text.trim().encodeURLParameter())
            append("&voice=").append(voice.encodeURLParameter())
            append("&speed=").append(speed)
            if (!lang.isNullOrBlank()) append("&lang=").append(lang.encodeURLParameter())
        }
        return "$baseUrl/v1/tts/stream?$params"
    }

    val httpClient: HttpClient by lazy {
        HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    // prettyPrint chỉ làm phình payload gửi đi, không có ích gì cho client
                    prettyPrint = false
                    isLenient = true
                    ignoreUnknownKeys = true
                    coerceInputValues = true
                    explicitNulls = false
                    encodeDefaults = true
                })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MS
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MS
            }
            // Tự động đính kèm token cho mọi yêu cầu, không phải sửa từng lời gọi
            install(DefaultRequest) {
                if (authToken.isNotBlank()) {
                    header(HttpHeaders.Authorization, "Bearer $authToken")
                }
            }
            // Chỉ QUAN SÁT phản hồi, không ném lỗi: từng API tự quyết định xử lý mã lỗi của nó.
            install(HttpCallValidator) {
                validateResponse { response ->
                    val code = response.status.value
                    if (code == 401 || code == 404) {
                        val request = response.request
                        val ownHost = ServerUrlValidator.parse(baseUrl)?.host
                        // Chỉ tính phản hồi từ chính máy chủ của mình
                        if (ownHost != null && request.url.host.equals(ownHost, ignoreCase = true)) {
                            handleResponseAuthCheck(
                                statusCode = code,
                                requestToken = SessionExpiryPolicy.bearerOf(request.headers[HttpHeaders.Authorization]),
                                path = request.url.encodedPath
                            )
                        }
                    }
                }
            }
        }
    }
}
