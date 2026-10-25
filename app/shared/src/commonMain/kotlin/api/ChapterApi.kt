package com.truyencloud.shared.api

import io.ktor.client.call.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable
data class ChapterRemoteResponse(
    val novel_id: String,
    val chapter_number: Int,
    val title: String,
    val title_vi: String? = "",
    val original_content: String,
    val translated_content: String? = null,
    val translation_engine: String? = null,
    /** true: bản dịch đang là bản TẠM dự phòng của Google (máy chủ sẽ thử AI lại ở lần yêu cầu sau). */
    val is_fallback: Boolean = false,
    val cached: Boolean = false
)

@Serializable
data class ChapterRecapResponse(
    val novel_id: String,
    val chapter_number: Int,
    val novel_title: String = "",
    val chapter_title: String = "",
    val bullets: List<String> = emptyList(),
    val characters: List<String> = emptyList(),
    val context: String = "",
    /** true: nội dung KHÔNG do AI tóm tắt (chương 1 trả nội dung tĩnh, hoặc AI lỗi) — không nên hiện như tóm tắt thật. */
    val is_fallback: Boolean = false,
    /** Lý do AI chưa tóm tắt được (null khi thành công). */
    val error: String? = null,
    /** Số thứ tự các chương trước thực sự đã được tóm tắt vào bản recap này (rỗng khi không có/không tóm tắt được). */
    val chapters_covered: List<Int> = emptyList()
)

@Serializable
data class LoreExplainRequest(
    val term: String,
    val context: String? = null
)

@Serializable
data class LoreExplainResponse(
    val term: String,
    val hanzi: String = "",
    val pinyin: String = "",
    val sino_vietnamese: String = "",
    val definition: String = "",
    val lore_context: String = "",
    /** true: máy chủ KHÔNG giải nghĩa được (định nghĩa trống) — phải hiện lỗi, không bao giờ lưu vào trí nhớ thuật ngữ. */
    val is_fallback: Boolean = false,
    /** Lý do không giải nghĩa được (null khi thành công). */
    val error: String? = null
)

class ChapterApi {
    private val client = TruyenCloudApi.httpClient
    private val base: String get() = TruyenCloudApi.baseUrl

    suspend fun getChapter(novelId: String, chapterNumber: Int, recrawl: Boolean = false): ChapterRemoteResponse {
        val url = if (recrawl) "$base/v1/novels/$novelId/chapters/$chapterNumber?recrawl=true" else "$base/v1/novels/$novelId/chapters/$chapterNumber"
        val resp = client.get(url)
        if (!resp.status.isSuccess()) {
            val errText = resp.bodyAsText()
            throw IllegalStateException("API error (${resp.status.value}): $errText")
        }
        return resp.body()
    }

    suspend fun getChapterRecap(novelId: String, chapterNumber: Int): ChapterRecapResponse {
        val url = "$base/v1/novels/$novelId/chapters/$chapterNumber/recap"
        val resp = client.get(url)
        if (!resp.status.isSuccess()) {
            val errText = resp.bodyAsText()
            throw IllegalStateException("API error (${resp.status.value}): $errText")
        }
        return resp.body()
    }

    suspend fun explainLore(term: String, context: String? = null): LoreExplainResponse {
        val url = "$base/v1/lore/explain"
        val resp = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(LoreExplainRequest(term = term, context = context))
        }
        if (!resp.status.isSuccess()) {
            val errText = resp.bodyAsText()
            throw IllegalStateException("API error (${resp.status.value}): $errText")
        }
        return resp.body()
    }

    suspend fun recrawlChapter(
        novelId: String,
        chapterNumber: Int,
        autoTranslate: Boolean = true,
        customPrompt: String? = null
    ): ChapterRemoteResponse {
        val url = buildString {
            append("$base/v1/novels/$novelId/chapters/$chapterNumber/recrawl?auto_translate=$autoTranslate")
            if (!customPrompt.isNullOrBlank()) {
                append("&custom_prompt=${customPrompt.encodeURLParameter()}")
            }
        }
        // Cào lại + dịch AI chạy lâu như một lượt dịch chương; 45 giây mặc định cắt ngang khi máy chủ vẫn đang dịch.
        val timeoutMs = if (autoTranslate) TruyenCloudApi.TRANSLATE_TIMEOUT_MS else TruyenCloudApi.AI_REQUEST_TIMEOUT_MS
        val resp = client.post(url) {
            timeout {
                requestTimeoutMillis = timeoutMs
                socketTimeoutMillis = timeoutMs
            }
        }
        if (!resp.status.isSuccess()) {
            val errText = resp.bodyAsText()
            throw IllegalStateException("API error (${resp.status.value}): $errText")
        }
        return resp.body()
    }
}
