package com.truyencloud.shared.api

import io.ktor.client.call.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class TermRemote(
    val source: String,
    val target: String
)

@Serializable
data class TranslateRequestBody(
    val novel_id: String,
    val chapter_number: Int,
    val content: String? = null,
    val glossary: List<TermRemote> = emptyList(),
    val force: Boolean = false,
    /** true: dịch TRƯỚC chương kế tiếp ở làn nền của máy chủ, không tranh chỗ với chương đang đọc. */
    val prefetch: Boolean = false,
    val custom_prompt: String? = null,
    val target_language: String = "vi",
    val english_level: String = "c1"
)

@Serializable
data class TranslateRemoteResponse(
    val novel_id: String,
    val chapter_number: Int,
    val translated_content: String,
    val title_vi: String = "",
    val cached: Boolean,
    val engine: String = "ANTIGRAVITY",
    val translation_engine: String = "ANTIGRAVITY",
    /** true: văn bản KHÔNG do AI dịch (dự phòng Google) — lần mở sau máy chủ sẽ thử lại bằng AI. */
    val is_fallback: Boolean = false
)

@Serializable
data class BatchTranslateBody(
    @SerialName("novel_id") val novel_id: String,
    @SerialName("from_chapter") val from_chapter: Int,
    @SerialName("to_chapter") val to_chapter: Int,
    @SerialName("glossary") val glossary: List<TermRemote> = emptyList(),
    @SerialName("overnight") val overnight: Boolean = false
)

@Serializable
data class BatchTranslateResponse(
    @SerialName("status") val status: String,
    @SerialName("novel_id") val novel_id: String,
    @SerialName("from_chapter") val from_chapter: Int,
    @SerialName("to_chapter") val to_chapter: Int,
    @SerialName("message") val message: String,
    @SerialName("job_id") val job_id: String = ""
)

@Serializable
data class JobStatusResponse(
    val id: String = "",
    val kind: String = "",
    val status: String = "queued",
    val progress: Float = 0f,
    val message: String = "",
    val error: String = ""
)

@Serializable
data class OvernightNovelSummary(
    val id: String = "",
    val title: String = "",
    val total_chapters: Int = 0,
    val translated_chapters: Int = 0
)

@Serializable
data class OvernightStatusResponse(
    val running: Boolean = false,
    val current_novel_id: String = "",
    val current_batch: List<Int> = emptyList(),
    val translated_total: Int = 0,
    val models: List<String> = emptyList(),
    val enabled_novels: List<OvernightNovelSummary> = emptyList()
)

class TranslateApi {
    private val client = TruyenCloudApi.httpClient
    private val base: String get() = TruyenCloudApi.baseUrl

    private suspend fun parseErrorMessage(resp: HttpResponse): String {
        val rawText = try { resp.bodyAsText() } catch (_: Exception) { "" }
        if (rawText.isBlank()) return "Máy chủ trả về mã lỗi ${resp.status.value}"
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val element = json.parseToJsonElement(rawText)
            if (element is JsonObject) {
                val detail = element["detail"]
                when {
                    detail is JsonPrimitive -> detail.content
                    detail is JsonArray -> {
                        detail.mapNotNull { item ->
                            if (item is JsonObject) {
                                val loc = (item["loc"] as? JsonArray)?.joinToString(".") { (it as? JsonPrimitive)?.content ?: it.toString() } ?: ""
                                val msg = item["msg"]?.let { m -> if (m is JsonPrimitive) m.content else null } ?: ""
                                if (loc.isNotBlank() && msg.isNotBlank()) "$loc: $msg"
                                else msg.ifBlank { loc }
                            } else null
                        }.joinToString("; ").ifBlank { rawText }
                    }
                    else -> element["message"]?.let { m -> if (m is JsonPrimitive) m.content else null } ?: rawText
                }
            } else {
                rawText
            }
        } catch (_: Exception) {
            rawText
        }
    }

    suspend fun translateChapter(
        novelId: String,
        chapterNumber: Int,
        content: String? = null,
        glossary: List<TermRemote> = emptyList(),
        force: Boolean = false,
        customPrompt: String? = null,
        targetLanguage: String = "vi",
        englishLevel: String = "c1",
        prefetch: Boolean = false
    ): TranslateRemoteResponse {
        val resp = client.post("$base/v1/translate") {
            contentType(ContentType.Application.Json)
            timeout {
                requestTimeoutMillis = TruyenCloudApi.TRANSLATE_TIMEOUT_MS
                socketTimeoutMillis = TruyenCloudApi.TRANSLATE_TIMEOUT_MS
            }
            setBody(TranslateRequestBody(
                novel_id = novelId,
                chapter_number = chapterNumber,
                content = content,
                glossary = glossary,
                force = force,
                prefetch = prefetch,
                custom_prompt = customPrompt,
                target_language = targetLanguage,
                english_level = englishLevel
            ))
        }
        if (!resp.status.isSuccess()) {
            throw Exception(parseErrorMessage(resp))
        }
        return resp.body()
    }

    suspend fun batchTranslate(
        novelId: String,
        fromChapter: Int,
        toChapter: Int,
        glossary: List<TermRemote> = emptyList(),
        overnight: Boolean = false
    ): BatchTranslateResponse {
        val resp = client.post("$base/v1/batch-translate") {
            contentType(ContentType.Application.Json)
            setBody(BatchTranslateBody(novelId, fromChapter, toChapter, glossary, overnight))
        }
        if (!resp.status.isSuccess()) {
            throw Exception(parseErrorMessage(resp))
        }
        return resp.body()
    }

    suspend fun getJobStatus(jobId: String): JobStatusResponse {
        val resp = client.get("$base/v1/jobs/$jobId")
        if (!resp.status.isSuccess()) {
            throw Exception(parseErrorMessage(resp))
        }
        return resp.body()
    }

    suspend fun stopOvernightTranslation(novelId: String): BatchTranslateResponse {
        val resp = client.post("$base/v1/overnight/$novelId/stop")
        if (!resp.status.isSuccess()) {
            throw Exception(parseErrorMessage(resp))
        }
        return resp.body()
    }

    suspend fun getOvernightStatus(): OvernightStatusResponse {
        val resp = client.get("$base/v1/overnight/status")
        if (!resp.status.isSuccess()) {
            throw Exception(parseErrorMessage(resp))
        }
        return resp.body()
    }
}
