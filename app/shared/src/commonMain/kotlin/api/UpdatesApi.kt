package com.truyencloud.shared.api

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable
data class ShelfNovelState(
    val novel_id: String,
    /** Số chương ứng dụng đang biết. Khác với trên máy chủ nghĩa là có chương mới. */
    val known_chapters: Int = 0
)

@Serializable
data class CheckUpdatesBatchBody(
    val novels: List<ShelfNovelState> = emptyList()
)

@Serializable
data class NovelUpdateInfo(
    val novel_id: String,
    val title: String = "",
    val title_vi: String = "",
    val total_chapters: Int = 0,
    val new_chapters: Int = 0
) {
    val displayTitle: String get() = title_vi.ifBlank { title }.ifBlank { novel_id }
}

@Serializable
data class CheckUpdatesBatchResponse(
    val updates: List<NovelUpdateInfo> = emptyList(),
    val checked: Int = 0
)

class UpdatesApi {
    private val client = TruyenCloudApi.httpClient
    private val base: String get() = TruyenCloudApi.baseUrl

    /**
     * Hỏi chương mới cho cả tủ sách trong MỘT lần gọi.
     *
     * Gọi từng truyện một sẽ thành hàng chục lời gọi mỗi lần tiến trình nền thức dậy —
     * tốn pin của máy và tốn lượt tải của trang nguồn.
     */
    suspend fun checkBatch(novels: List<ShelfNovelState>): CheckUpdatesBatchResponse {
        if (novels.isEmpty()) return CheckUpdatesBatchResponse()
        val resp = client.post("$base/v1/novels/updates/check-batch") {
            contentType(ContentType.Application.Json)
            setBody(CheckUpdatesBatchBody(novels))
        }
        resp.requireSuccess("Kiểm tra chương mới")
        return resp.body()
    }
}
