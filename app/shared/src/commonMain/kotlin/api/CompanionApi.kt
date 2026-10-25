package com.truyencloud.shared.api

import io.ktor.client.call.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Tên trợ lý mặc định của máy chủ (không nhập vai nhân vật nào). */
const val DEFAULT_COMPANION_NAME = "Trợ Lý Bách Khoa"

@Serializable
data class CompanionChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class CompanionChatRequest(
    val novel_title: String,
    val chapter_title: String = "",
    /** Chương độc giả đang đọc — mốc chống tiết lộ của máy chủ; null khi chưa biết. */
    val chapter_number: Int? = null,
    val user_message: String,
    val context_text: String? = null,
    val character_name: String? = null,
    val chat_history: List<CompanionChatMessage>? = null,
    val model: String? = null
)

@Serializable
data class CompanionChatResponse(
    val reply: String = "",
    val character_name: String = DEFAULT_COMPANION_NAME,
    val suggested_questions: List<String> = emptyList(),
    /** true: [reply] là câu xin lỗi dự phòng của máy chủ, KHÔNG phải câu trả lời của AI. */
    val is_fallback: Boolean = false,
    /** Lý do AI không trả lời được (null khi thành công). */
    val error: String? = null,
    /**
     * true: luồng SSE kết thúc mà KHÔNG có sự kiện `done` (mạng đứt giữa chừng, máy chủ đóng sớm) nên [reply] mới chỉ là một phần
     * câu trả lời. Chỉ do ứng dụng đặt khi đọc luồng (không đọc từ JSON của máy chủ).
     */
    @Transient val is_incomplete: Boolean = false
)

/**
 * Một lượt stream: gom các dòng SSE thành MỘT [CompanionChatResponse] và nhớ xem có nhận được sự kiện `done` không.
 *
 * Luồng đứt giữa chừng có thể đã mang một phần câu trả lời nhưng không có `done`: [toResponse] đánh dấu
 * [CompanionChatResponse.is_incomplete] để nơi gọi hiện thành lỗi có nút thử lại, thay vì lưu nửa câu như một câu trả lời trọn vẹn.
 */
class CompanionStreamRun(requestedCharacter: String?) {
    private val acc = CompanionStreamAccumulator(requestedCharacter)
    private var sawDone = false

    val hasContent: Boolean get() = acc.hasContent

    /** Nhận một dòng của luồng; trả về mảnh chữ mới (để hiện dần) hoặc null. */
    fun accept(line: String): String? {
        val event = CompanionSse.parseLine(line)
        if (event is CompanionSseEvent.Done) sawDone = true
        return acc.accept(event)?.content
    }

    /** null khi luồng không mang gì (không chữ, không `done`) — nơi gọi nên thử đường JSON. */
    fun toResponse(): CompanionChatResponse? {
        val res = acc.toResponse() ?: return null
        return if (sawDone) res else res.copy(is_incomplete = true)
    }
}

class CompanionApi {
    private val client = TruyenCloudApi.httpClient
    private val base: String get() = TruyenCloudApi.baseUrl

    private fun request(
        novelTitle: String,
        chapterTitle: String,
        chapterNumber: Int?,
        userMessage: String,
        contextText: String?,
        characterName: String?,
        chatHistory: List<CompanionChatMessage>,
        model: String?
    ) = CompanionChatRequest(
        novel_title = novelTitle,
        chapter_title = chapterTitle,
        chapter_number = chapterNumber,
        user_message = userMessage,
        context_text = contextText,
        character_name = characterName,
        chat_history = chatHistory.ifEmpty { null },
        model = model
    )

    /**
     * Một lượt trả lời trọn vẹn. Lỗi mạng / HTTP được ném ra (người gọi hiện bong bóng lỗi + nút thử lại) — không còn
     * bịa một câu trả lời kèm câu hỏi gợi ý cố định như bản cũ.
     */
    suspend fun chat(
        novelTitle: String,
        chapterTitle: String,
        chapterNumber: Int?,
        userMessage: String,
        contextText: String? = null,
        characterName: String? = null,
        chatHistory: List<CompanionChatMessage> = emptyList(),
        model: String? = null
    ): CompanionChatResponse {
        val resp = client.post("$base/v1/companion/chat") {
            contentType(ContentType.Application.Json)
            timeout {
                requestTimeoutMillis = TruyenCloudApi.AI_REQUEST_TIMEOUT_MS
                socketTimeoutMillis = TruyenCloudApi.AI_REQUEST_TIMEOUT_MS
            }
            setBody(request(novelTitle, chapterTitle, chapterNumber, userMessage, contextText, characterName, chatHistory, model))
        }
        resp.requireSuccess("Trò chuyện cùng AI")
        return resp.body()
    }

    /**
     * Trả lời theo luồng SSE: [onChunk] nhận từng mảnh chữ NGAY khi máy chủ gửi. (Bản cũ dùng `client.post(...)` — Ktor đọc
     * hết thân phản hồi vào bộ nhớ rồi mới trả về — nên mọi mảnh chữ chỉ hiện cùng một lúc khi đã xong.)
     *
     * Kết quả mang `is_fallback` / `error` đọc từ sự kiện `done`. Luồng hỏng hẳn (không chữ, không `done`) thì thử đường
     * JSON một lần; lỗi HTTP (401/429/5xx) được ném ra luôn vì đường JSON cũng sẽ hỏng y như vậy. Luồng có chữ nhưng
     * KHÔNG có `done` trả về kết quả `is_incomplete` (xem [CompanionStreamRun]).
     */
    suspend fun chatStream(
        novelTitle: String,
        chapterTitle: String,
        chapterNumber: Int?,
        userMessage: String,
        contextText: String? = null,
        characterName: String? = null,
        chatHistory: List<CompanionChatMessage> = emptyList(),
        model: String? = null,
        onChunk: (String) -> Unit
    ): CompanionChatResponse {
        val body = request(novelTitle, chapterTitle, chapterNumber, userMessage, contextText, characterName, chatHistory, model)
        val stream = CompanionStreamRun(characterName)
        try {
            client.preparePost("$base/v1/companion/chat/stream") {
                contentType(ContentType.Application.Json)
                timeout {
                    requestTimeoutMillis = TruyenCloudApi.AI_REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = TruyenCloudApi.AI_REQUEST_TIMEOUT_MS
                }
                setBody(body)
            }.execute { response ->
                response.requireSuccess("Trò chuyện cùng AI")
                val channel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    stream.accept(line)?.let(onChunk)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiHttpException) {
            throw e
        } catch (e: Exception) {
            // Mạng đứt giữa chừng: đã có chữ thì giữ phần đã nhận (đánh dấu chưa trọn vẹn); chưa có gì thì thử đường JSON bên dưới
            if (!stream.hasContent) {
                return stream.toResponse() ?: chat(novelTitle, chapterTitle, chapterNumber, userMessage, contextText, characterName, chatHistory, model)
            }
        }
        return stream.toResponse()
            ?: chat(novelTitle, chapterTitle, chapterNumber, userMessage, contextText, characterName, chatHistory, model)
    }
}
