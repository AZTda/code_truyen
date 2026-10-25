package com.truyencloud.shared.api

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable
data class SyncProgressRequest(
    val novel_id: String,
    val current_chapter: Int,
    val scroll_position: Float = 0f,
    val shelf_name: String = "reading",
    val is_favorite: Boolean = false,
    /** Giờ sửa trên máy này (epoch ms). Máy chủ hợp nhất theo giờ này, nên LUÔN gửi. */
    val client_updated_at: Long? = null
)

/** Metadata truyện kèm theo dòng tiến độ — để máy MỚI dựng lại tủ sách từ máy chủ. */
@Serializable
data class ProgressNovelSummary(
    val id: String = "",
    val title: String = "",
    val title_vi: String = "",
    val author: String = "",
    val cover_url: String = "",
    val total_chapters: Int = 0,
    val source_id: String = "",
    val source_url: String = "",
    val description: String = "",
    val status: String = "",
    val translated_chapters: Int = 0,
    val overnight_enabled: Boolean = false,
    val category: String = ""
)

@Serializable
data class ProgressRemoteResponse(
    val novel_id: String,
    val current_chapter: Int = 0,
    val scroll_position: Float = 0f,
    val shelf_name: String = "reading",
    val is_favorite: Boolean = false,
    val last_read_at: String? = null,
    val updated_at_ms: Long = 0L,
    val novel: ProgressNovelSummary? = null
)

@Serializable
data class SyncProgressResponse(
    /** "success" hoặc "stale" (máy chủ đang giữ bản MỚI hơn — dùng [progress] thay cho bản gửi lên). */
    val status: String = "success",
    val synced_at: String = "",
    val updated_at_ms: Long = 0L,
    val progress: ProgressRemoteResponse? = null
) {
    val isStale: Boolean get() = status == "stale"
}

/** Phía máy chủ của đồng bộ tiến độ — tách thành giao diện để kiểm thử thuật toán đồng bộ mà không cần mạng. */
interface ProgressRemote {
    suspend fun syncProgress(
        novelId: String,
        currentChapter: Int,
        scrollPosition: Float,
        shelfName: String = "reading",
        isFavorite: Boolean = false,
        clientUpdatedAt: Long? = null
    ): SyncProgressResponse

    suspend fun getProgress(novelId: String): ProgressRemoteResponse
    suspend fun getAllProgress(): List<ProgressRemoteResponse>
}

class ProgressApi : ProgressRemote {
    private val client = TruyenCloudApi.httpClient
    private val base: String get() = TruyenCloudApi.baseUrl

    override suspend fun syncProgress(
        novelId: String,
        currentChapter: Int,
        scrollPosition: Float,
        shelfName: String,
        isFavorite: Boolean,
        clientUpdatedAt: Long?
    ): SyncProgressResponse {
        val resp = client.post("$base/v1/progress/sync") {
            contentType(ContentType.Application.Json)
            setBody(
                SyncProgressRequest(
                    novel_id = novelId,
                    current_chapter = currentChapter,
                    scroll_position = scrollPosition,
                    shelf_name = shelfName,
                    is_favorite = isFavorite,
                    client_updated_at = clientUpdatedAt
                )
            )
        }
        resp.requireSuccess("Đồng bộ tiến độ")
        return resp.body()
    }

    override suspend fun getProgress(novelId: String): ProgressRemoteResponse {
        val resp = client.get("$base/v1/progress/$novelId")
        resp.requireSuccess("Lấy tiến độ")
        return resp.body()
    }

    override suspend fun getAllProgress(): List<ProgressRemoteResponse> {
        val resp = client.get("$base/v1/progress")
        resp.requireSuccess("Lấy danh sách tiến độ")
        return resp.body()
    }
}
