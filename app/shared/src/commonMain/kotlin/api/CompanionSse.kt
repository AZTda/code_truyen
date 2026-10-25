package com.truyencloud.shared.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Một sự kiện của luồng SSE `/v1/companion/chat/stream`. */
sealed interface CompanionSseEvent {
    data class Start(val characterName: String?) : CompanionSseEvent
    data class Chunk(val content: String) : CompanionSseEvent

    /** Sự kiện kết thúc: mang cờ dự phòng (`is_fallback`) và lý do (`error`). Luồng SSE không có câu hỏi gợi ý (rỗng). */
    data class Done(
        val characterName: String?,
        val suggestedQuestions: List<String>,
        val isFallback: Boolean,
        val error: String?
    ) : CompanionSseEvent
}

object CompanionSse {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Đọc một dòng của luồng; trả null với dòng không phải `data: {...}` hợp lệ (bình luận, dòng trống, JSON hỏng). */
    fun parseLine(line: String): CompanionSseEvent? {
        if (!line.startsWith("data:")) return null
        val raw = line.removePrefix("data:").trim()
        if (raw.isEmpty()) return null
        val obj = try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        return when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
            "start" -> CompanionSseEvent.Start(text(obj["character_name"]))
            "chunk" -> CompanionSseEvent.Chunk(text(obj["content"]).orEmpty())
            "done" -> CompanionSseEvent.Done(
                characterName = text(obj["character_name"]),
                suggestedQuestions = (obj["suggested_questions"] as? JsonArray)
                    ?.mapNotNull { text(it)?.takeIf { q -> q.isNotBlank() } }
                    .orEmpty(),
                isFallback = (obj["is_fallback"] as? JsonPrimitive)?.booleanOrNull ?: false,
                error = text(obj["error"])?.takeIf { it.isNotBlank() }
            )
            else -> null
        }
    }

    private fun text(element: kotlinx.serialization.json.JsonElement?): String? =
        if (element == null || element is JsonNull) null else (element as? JsonPrimitive)?.contentOrNull
}

/**
 * Gom các sự kiện của luồng thành MỘT [CompanionChatResponse].
 *
 * Máy chủ gửi câu xin lỗi dự phòng như một mảnh chữ bình thường rồi mới báo `done` với `is_fallback = true`; nếu không
 * đọc cờ đó thì câu xin lỗi hiện ra như một câu trả lời thật.
 */
class CompanionStreamAccumulator(private val requestedCharacter: String?) {
    private val reply = StringBuilder()
    private var started: CompanionSseEvent.Start? = null
    private var done: CompanionSseEvent.Done? = null

    val hasContent: Boolean get() = reply.isNotBlank()

    /** Nhận một sự kiện; trả về mảnh chữ mới (để hiện dần) hoặc null. */
    fun accept(event: CompanionSseEvent?): CompanionSseEvent.Chunk? {
        when (event) {
            is CompanionSseEvent.Start -> started = event
            is CompanionSseEvent.Chunk -> {
                reply.append(event.content)
                return event
            }
            is CompanionSseEvent.Done -> done = event
            null -> Unit
        }
        return null
    }

    /** null khi luồng không mang gì (không chữ, không `done`) — nơi gọi nên thử đường JSON. */
    fun toResponse(): CompanionChatResponse? {
        val finished = done
        if (finished == null && !hasContent) return null
        return CompanionChatResponse(
            reply = reply.toString().trim(),
            character_name = finished?.characterName ?: started?.characterName ?: requestedCharacter ?: DEFAULT_COMPANION_NAME,
            suggested_questions = finished?.suggestedQuestions.orEmpty(),
            is_fallback = finished?.isFallback ?: false,
            error = finished?.error
        )
    }
}
