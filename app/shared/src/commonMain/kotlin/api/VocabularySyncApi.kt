package com.truyencloud.shared.api

import com.truyencloud.shared.data.ReviewLog
import com.truyencloud.shared.data.SavedWord
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Yêu cầu đẩy: máy chủ giới hạn <= 500 từ và <= 2000 log mỗi lần (vượt thì HTTP 413) nên nơi gọi PHẢI chia lô. */
@Serializable
data class SyncVocabularyRemoteRequest(
    val words: List<SavedWord> = emptyList(),
    val review_logs: List<ReviewLog> = emptyList()
)

@Serializable
data class SyncVocabularyRemoteResponse(
    val status: String = "success",
    val words_synced: Int = 0,
    /** Máy chủ đã có bản MỚI hơn của các từ này -> phải kéo về thay vì giữ bản cục bộ. */
    val words_stale: Int = 0,
    val stale_word_ids: List<String> = emptyList(),
    val logs_synced: Int = 0,
    val logs_skipped: Int = 0,
    val synced_at: String = "",
    val server_time_ms: Long = 0L
)

/**
 * Phản hồi kéo về. Từng phần tử được giữ ở dạng JSON thô để bản ghi hỏng chỉ bị bỏ qua chứ không làm
 * hỏng cả lượt đồng bộ. [words] gồm cả "bia mộ" (deletedAt > 0) khi yêu cầu có `since`.
 */
@Serializable
data class VocabularySyncDataResponse(
    val words: List<JsonElement> = emptyList(),
    val review_logs: List<JsonElement> = emptyList(),
    val synced_at: String = "",
    val server_time_ms: Long = 0L
)

/** Lỗi HTTP từ máy chủ đồng bộ (4xx / 5xx) — trước đây bị coi là thành công vì không ai kiểm tra mã trạng thái. */
class VocabularySyncHttpException(val status: Int, message: String) : Exception(message)

/** Mặt cắt mạng của đồng bộ từ vựng — tách thành interface để kiểm thử bộ điều phối mà không cần máy chủ thật. */
interface VocabularySyncRemote {
    suspend fun push(words: List<SavedWord>, reviewLogs: List<ReviewLog>): SyncVocabularyRemoteResponse
    suspend fun pull(sinceMs: Long): VocabularySyncDataResponse
}

class VocabularySyncApi(private val client: HttpClient = TruyenCloudApi.httpClient) : VocabularySyncRemote {
    private val base: String get() = TruyenCloudApi.baseUrl.trimEnd('/')

    /** Đẩy một lô thay đổi. Ném [VocabularySyncHttpException] nếu máy chủ không trả 2xx. */
    override suspend fun push(words: List<SavedWord>, reviewLogs: List<ReviewLog>): SyncVocabularyRemoteResponse {
        val resp = client.post("$base/v1/vocabulary/sync") {
            contentType(ContentType.Application.Json)
            setBody(SyncVocabularyRemoteRequest(words = words, review_logs = reviewLogs))
            timeout {
                requestTimeoutMillis = SYNC_TIMEOUT_MS
                socketTimeoutMillis = SYNC_TIMEOUT_MS
            }
        }
        requireSuccess(resp)
        return resp.body()
    }

    /**
     * Kéo thay đổi sau mốc [sinceMs] (lấy từ `server_time_ms` của lần trước, 0 ở lần đầu).
     * LUÔN gửi `since`: máy chủ chỉ trả bia mộ cho client có tham số này.
     */
    override suspend fun pull(sinceMs: Long): VocabularySyncDataResponse {
        val resp = client.get("$base/v1/vocabulary/sync") {
            parameter("since", sinceMs.coerceAtLeast(0L))
            timeout {
                requestTimeoutMillis = SYNC_TIMEOUT_MS
                socketTimeoutMillis = SYNC_TIMEOUT_MS
            }
        }
        requireSuccess(resp)
        return resp.body()
    }

    private suspend fun requireSuccess(resp: HttpResponse) {
        if (resp.status.isSuccess()) return
        throw VocabularySyncHttpException(resp.status.value, serverMessage(resp))
    }

    /** FastAPI trả `{"detail": "..."}` — lấy câu tiếng Việt của máy chủ nếu có. */
    private suspend fun serverMessage(resp: HttpResponse): String {
        val raw = runCatching { resp.bodyAsText() }.getOrDefault("")
        val detail = runCatching {
            val obj = Json.parseToJsonElement(raw) as? JsonObject
            (obj?.get("detail") as? JsonPrimitive)?.content
        }.getOrNull()
        val text = (detail ?: raw).trim().take(240)
        return if (text.isNotBlank()) text else "Máy chủ trả lỗi ${resp.status.value}"
    }

    companion object {
        private const val SYNC_TIMEOUT_MS = 90_000L
    }
}
