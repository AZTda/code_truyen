package com.truyencloud.shared.api

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable
data class BookmarkRemoteItem(
    val id: String,
    val novel_id: String,
    val chapter_number: Int,
    val chapter_title: String = "",
    val paragraph_index: Int = 0,
    val selected_text: String = "",
    val note: String = "",
    val color_hex: String = "#FFD54F",
    val created_at: Long = 0L,
    /** Giờ sửa (epoch ms); máy chủ hợp nhất theo giờ này. 0 = bản của app cũ, luôn thua bản có giờ thật. */
    val updated_at: Long = 0L,
    /** > 0 nghĩa là bia mộ: bookmark đã bị xoá lúc này. */
    val deleted_at: Long = 0L
) {
    val isTombstone: Boolean get() = deleted_at > 0L

    /** Mốc so sánh với máy chủ: lúc xoá cũng là một lần sửa. */
    val stamp: Long get() = maxOf(updated_at, deleted_at)
}

@Serializable
data class SyncBookmarksRemoteRequest(
    val bookmarks: List<BookmarkRemoteItem>
)

@Serializable
data class SyncBookmarksRemoteResponse(
    val status: String = "success",
    val count: Int = 0,
    val applied: Int = 0,
    val unchanged: Int = 0,
    /** id mà máy chủ đang giữ bản MỚI hơn bản vừa gửi — cần kéo về. */
    val stale_ids: List<String> = emptyList(),
    val synced_at: String = "",
    val server_time_ms: Long = 0L
)

/** Một lượt kéo bookmark: danh sách kèm mốc giờ máy chủ (header X-Server-Time-Ms) làm con trỏ lần sau. */
data class BookmarkPullResult(
    val items: List<BookmarkRemoteItem>,
    val serverTimeMs: Long
)

/** Phía máy chủ của đồng bộ dấu trang — tách thành giao diện để kiểm thử thuật toán đồng bộ mà không cần mạng. */
interface BookmarkRemote {
    suspend fun pullBookmarks(since: Long): BookmarkPullResult
    suspend fun syncBookmarks(bookmarks: List<BookmarkRemoteItem>): SyncBookmarksRemoteResponse
}

class BookmarkApi : BookmarkRemote {
    private val client = TruyenCloudApi.httpClient
    private val base: String get() = TruyenCloudApi.baseUrl

    /**
     * Kéo bookmark đã đổi sau mốc [since] (epoch ms).
     *
     * LUÔN gửi `since` (0 ở lần đầu): máy chủ chỉ trả bia mộ khi có tham số này — bỏ qua thì
     * bookmark đã xoá trên máy khác sẽ không bao giờ bị xoá ở máy này.
     */
    override suspend fun pullBookmarks(since: Long): BookmarkPullResult {
        val resp = client.get("$base/v1/bookmarks") {
            parameter("since", since.coerceAtLeast(0L))
        }
        resp.requireSuccess("Kéo dấu trang")
        val serverTime = resp.headers[SERVER_TIME_HEADER]?.toLongOrNull() ?: 0L
        val items: List<BookmarkRemoteItem> = resp.body()
        return BookmarkPullResult(items, serverTime)
    }

    /** Đẩy tối đa 500 bookmark mỗi lần (giới hạn của máy chủ); gọi nhiều lần cho danh sách dài hơn. */
    override suspend fun syncBookmarks(bookmarks: List<BookmarkRemoteItem>): SyncBookmarksRemoteResponse {
        val resp = client.post("$base/v1/bookmarks/sync") {
            contentType(ContentType.Application.Json)
            setBody(SyncBookmarksRemoteRequest(bookmarks))
        }
        resp.requireSuccess("Đẩy dấu trang")
        return resp.body()
    }

    /** Xoá ngay một bookmark trên máy chủ (ghi bia mộ). Đồng bộ thường dùng bia mộ qua [syncBookmarks]. */
    suspend fun deleteBookmark(bookmarkId: String): String {
        val resp = client.delete("$base/v1/bookmarks/$bookmarkId")
        resp.requireSuccess("Xoá dấu trang")
        return resp.body()
    }

    private companion object {
        const val SERVER_TIME_HEADER = "X-Server-Time-Ms"
    }
}
