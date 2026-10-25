package com.truyencloud.shared.library

import com.truyencloud.shared.data.Novel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Phản hồi của `POST /v1/novels/import`. */
@Serializable
private data class ImportResponseDto(
    val status: String = "",
    val novel_id: String = "",
    val title: String = "",
    val author: String = "",
    val total_chapters: Int = 0,
    val cover_url: String = ""
)

/**
 * Biến phản hồi nạp tệp của máy chủ thành [Novel] để thêm vào tủ sách. Máy chủ chỉ lưu truyện nạp riêng
 * trên kho của nó; nếu ứng dụng không tự thêm hàng vào tủ, "Đã nạp thành công" mà Tủ sách vẫn không có
 * truyện nào (tủ sách = các hàng trong bảng `novels`).
 */
object ImportedBook {
    /** Nguồn riêng tư mà máy chủ gán cho truyện nạp từ tệp. */
    const val SOURCE_ID = "local_import"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** null nếu phản hồi không phải JSON hợp lệ hoặc không có `novel_id`. */
    fun parse(body: String, fallbackTitle: String, fallbackAuthor: String): Novel? {
        val dto = try {
            json.decodeFromString(ImportResponseDto.serializer(), body)
        } catch (_: Exception) {
            return null
        }
        val id = dto.novel_id.trim()
        if (id.isEmpty()) return null
        val title = dto.title.trim().ifBlank { fallbackTitle.trim() }.ifBlank { id }
        return Novel(
            id = id,
            title = title,
            titleVi = title,
            author = dto.author.trim().ifBlank { fallbackAuthor.trim() },
            coverUrl = dto.cover_url.trim(),
            sourceId = SOURCE_ID,
            totalChapters = dto.total_chapters.coerceAtLeast(0),
            status = "completed"
        )
    }
}
