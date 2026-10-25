package com.truyencloud.shared.api

import io.ktor.client.call.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
@Serializable
data class SearchResponse(
    val query: String,
    val total: Int,
    val results: List<NovelRemoteItem>
)

@Serializable
data class NovelRemoteItem(
    val id: String? = null,
    val title: String,
    val title_vi: String? = "",
    val author: String? = "",
    val url: String,
    val cover_url: String? = "",
    val source_id: String,
    val total_chapters: Int = 0
)

@Serializable
data class ChapterRemoteSummary(
    val chapter_number: Int,
    val id: String? = "",
    val title: String,
    val title_vi: String? = "",
    val url: String? = ""
)

@Serializable
data class NovelDetailResponse(
    val id: String,
    val title: String,
    val title_vi: String? = "",
    val author: String? = "",
    val source_url: String,
    val source_id: String,
    val cover_url: String? = "",
    val description: String? = "",
    val status: String = "ongoing",
    val total_chapters: Int = 0,
    val translated_chapters: Int = 0,
    val overnight_enabled: Boolean = false,
    val translated_chapter_numbers: List<Int> = emptyList(),
    val chapters: List<ChapterRemoteSummary> = emptyList()
)

@Serializable
data class ScrapeBody(val source_url: String)

/** GET /v1/novels/discover: truyện công khai lưu trên máy chủ (không gồm truyện tự nhập riêng tư). */
@Serializable
data class DiscoverResponse(
    val novels: List<DiscoverRemoteItem> = emptyList(),
    val total: Int = 0,
    /** Máy chủ đã xếp riêng cho người gọi (sort=for_you có token hợp lệ). Máy chủ cũ không gửi → false. */
    val personalized: Boolean = false
)

@Serializable
data class DiscoverRemoteItem(
    val id: String = "",
    val title: String = "",
    val title_vi: String? = "",
    val author: String? = "",
    val cover_url: String? = "",
    val total_chapters: Int = 0,
    val source_id: String = "",
    val source_url: String = "",
    val description: String? = "",
    val status: String? = "",
    val translated_chapters: Int = 0,
    val overnight_enabled: Boolean = false,
    val category: String? = "",
    /** Vì sao truyện này được gợi ý (chỉ có ở bảng cá nhân hoá); thiếu / null = không có lý do. */
    val reason: String? = null
)

@Serializable
data class RemoteSuggestItem(
    val label: String = "",
    val sub: String = "",
    val query: String = "",
    val kind: String = "keyword",
    val id: String? = null
)

@Serializable
data class SuggestResponse(
    val query: String = "",
    val suggestions: List<RemoteSuggestItem> = emptyList()
)

class NovelApi {
    private val client = TruyenCloudApi.httpClient
    private val base: String get() = TruyenCloudApi.baseUrl

    suspend fun suggest(query: String, limit: Int = 6): SuggestResponse {
        val resp = client.get("$base/v1/novels/suggest") {
            parameter("q", query)
            parameter("limit", limit)
        }
        if (!resp.status.isSuccess()) return SuggestResponse(query = query)
        return resp.body()
    }

    suspend fun search(query: String, source: String? = null): SearchResponse {
        val resp = client.get("$base/v1/novels/search") {
            parameter("q", query)
            if (!source.isNullOrBlank()) parameter("source", source)
        }
        // Giữ mã trạng thái (429/5xx...) để giao diện chọn được câu thông báo phù hợp
        resp.requireSuccess("Tìm kiếm")
        return resp.body()
    }

    /**
     * Khám phá: truyện công khai trên máy chủ, không cần đăng nhập.
     * @param sort "recent" (mới cập nhật), "popular" (nhiều người đọc) hoặc "for_you" (xếp riêng theo tủ sách của người
     * gọi; token được đính kèm tự động, máy chủ báo qua `personalized` có xếp riêng được hay không). Giá trị khác, và
     * "for_you" với máy chủ chưa hỗ trợ, bị từ chối (422).
     * @param limit số truyện tối đa (máy chủ giới hạn 1..100).
     */
    suspend fun discover(sort: String = "recent", limit: Int = 300): DiscoverResponse {
        val resp = client.get("$base/v1/novels/discover") {
            parameter("sort", sort)
            parameter("limit", limit)
            // URL của bảng cá nhân hoá giống hệt nhau giữa các tài khoản: không để bộ nhớ đệm nào (URLCache của iOS...)
            // giữ lại rồi phát cho tài khoản kế tiếp trên máy.
            if (sort == "for_you") header(HttpHeaders.CacheControl, "no-store")
        }
        resp.requireSuccess("Tải danh sách khám phá")
        return resp.body()
    }

    suspend fun getNovelDetail(novelId: String, url: String? = null): NovelDetailResponse {
        val resp = client.get("$base/v1/novels/$novelId") {
            if (!url.isNullOrBlank()) parameter("url", url)
        }
        if (!resp.status.isSuccess()) {
            throw Exception("Không tìm thấy truyện ($novelId)")
        }
        return resp.body()
    }

    suspend fun scrapeNovel(sourceUrl: String): NovelDetailResponse {
        val resp = client.post("$base/v1/scrape") {
            contentType(ContentType.Application.Json)
            setBody(ScrapeBody(sourceUrl))
        }
        if (!resp.status.isSuccess()) {
            val err = try { resp.bodyAsText().take(300) } catch (_: Exception) { "" }
            // Giữ mã trạng thái (ApiHttpException) để FriendlyErrors chọn đúng câu cho 401/429/5xx
            throw ApiHttpException(
                resp.status.value,
                "Lỗi cào dữ liệu (mã ${resp.status.value})" + if (err.isBlank()) "" else ": $err"
            )
        }
        return resp.body()
    }

    suspend fun checkUpdates(novelId: String): String {
        val resp = client.post("$base/v1/novels/$novelId/check-updates")
        resp.requireSuccess("Kiểm tra chương mới")
        return resp.bodyAsText()
    }

    suspend fun downloadNovelEpub(novelId: String): Pair<String, ByteArray> {
        val resp = client.get("$base/v1/novels/$novelId/export.epub") {
            timeout {
                requestTimeoutMillis = TruyenCloudApi.AI_BATCH_TIMEOUT_MS
                socketTimeoutMillis = TruyenCloudApi.AI_BATCH_TIMEOUT_MS
            }
        }
        resp.requireSuccess("Xuất EPUB")
        val disp = resp.headers[HttpHeaders.ContentDisposition].orEmpty()
        val match = Regex("""filename="?([^";]+)"?""").find(disp)
        val fname = match?.groupValues?.getOrNull(1)?.ifBlank { null } ?: "truyencloud_${safeFileName(novelId)}.epub"
        return fname to resp.readRawBytes()
    }

    suspend fun importNovelText(title: String, author: String, textContent: String): String {
        return importNovelBytes(
            title = title,
            author = author,
            fileName = "${safeFileName(title)}.txt",
            bytes = textContent.encodeToByteArray(),
            mimeType = "text/plain; charset=utf-8"
        )
    }

    suspend fun importNovelFile(
        title: String,
        author: String,
        fileName: String,
        bytes: ByteArray,
        mimeType: String
    ): String {
        if (bytes.isEmpty()) throw UserFacingException("Tệp rỗng.")
        if (bytes.size > 25 * 1024 * 1024) throw UserFacingException("Tệp vượt quá giới hạn 25 MB.")
        return importNovelBytes(title, author, fileName, bytes, mimeType)
    }

    private suspend fun importNovelBytes(
        title: String,
        author: String,
        fileName: String,
        bytes: ByteArray,
        mimeType: String
    ): String {
        val resp = client.submitFormWithBinaryData(
            url = "$base/v1/novels/import",
            formData = io.ktor.client.request.forms.formData {
                append("title", title)
                append("author", author)
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, mimeType)
                    append(HttpHeaders.ContentDisposition, "filename=\"${safeFileName(fileName)}\"")
                })
            }
        ) {
            // Tải tệp sách lớn + máy chủ tách chương cần lâu hơn 45s mặc định
            timeout {
                requestTimeoutMillis = TruyenCloudApi.AI_BATCH_TIMEOUT_MS
                socketTimeoutMillis = TruyenCloudApi.AI_BATCH_TIMEOUT_MS
            }
        }
        resp.requireSuccess("Nạp tệp")
        return resp.bodyAsText()
    }

    private fun safeFileName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('_')
        .ifBlank { "truyencloud_book" }
}
