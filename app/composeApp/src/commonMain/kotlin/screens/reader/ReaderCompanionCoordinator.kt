package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.api.CompanionApi
import com.truyencloud.shared.api.DEFAULT_COMPANION_NAME
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.reader.AiErrors
import com.truyencloud.shared.reader.CompanionContext
import com.truyencloud.shared.reader.CompanionPolicy
import com.truyencloud.shared.reader.CompanionResult
import com.truyencloud.shared.reader.CompanionSessionOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Điều phối tính năng AI Companion Chat trong màn hình đọc sách.
 *
 *  - Ngữ cảnh gửi kèm là các đoạn QUANH chỗ đang đọc (không phải 8 đoạn đầu chương) cùng số chương làm mốc chống tiết lộ.
 *  - Câu hỏi hiện tại không nằm trong lịch sử gửi lên (bản cũ gửi nó hai lần).
 *  - Câu trả lời dự phòng của máy chủ (`is_fallback`) KHÔNG vào lịch sử: nó hiện thành bong bóng lỗi kèm nút "Thử lại" (lịch sử
 *    còn được gửi lại cho AI nên câu xin lỗi sẽ làm bẩn các lượt sau). Luồng đứt giữa chừng (không có sự kiện kết thúc) cũng vậy.
 *  - Hội thoại thuộc về MỘT truyện của MỘT tài khoản ([onReaderOpened]); ô nhập khoá tới tận lúc luồng thật sự kết thúc
 *    ([ReaderUiState.isCompanionStreaming]) để câu hỏi mới không cắt ngang và để lại nửa câu trả lời trước.
 */
class ReaderCompanionCoordinator(
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope,
    private val companionApi: CompanionApi = CompanionApi(),
    /** Vị trí cuộn gần nhất (0..1) của chương đang đọc, để chọn đoạn làm ngữ cảnh. */
    private val scrollFraction: () -> Float = { 0f },
    /** Mã tài khoản đang dùng (khách cũng có mã riêng): đổi tài khoản thì không được thấy lại hội thoại của người trước. */
    private val accountId: () -> String = { "" }
) {
    private var sendJob: Job? = null

    /** Tăng mỗi khi gửi câu mới hoặc xoá hội thoại: lượt đang bay của lần trước không được ghi vào hội thoại hiện tại. */
    private var generation = 0

    private val owner = CompanionSessionOwner()

    /**
     * Màn đọc vừa mở cho truyện [novelId]. Một ViewModel dùng chung cho mọi truyện và mọi tài khoản nên hội thoại cũ phải bỏ khi
     * truyện hoặc tài khoản đổi: bản cũ giữ nguyên lịch sử, vai, câu hỏi lỗi... và gửi lịch sử của truyện A kèm tên + nội dung truyện B
     * (AI thấy cả tiết lộ của cuốn kia); đăng xuất rồi đăng nhập tài khoản khác cũng thấy lại hội thoại của người trước.
     */
    fun onReaderOpened(novelId: String) {
        if (owner.claim(novelId, accountId())) resetConversation()
    }

    /** Bỏ hẳn hội thoại: huỷ lượt đang stream (và làm nó mồ côi), về vai mặc định, đóng bảng. */
    private fun resetConversation() {
        generation++
        sendJob?.cancel()
        sendJob = null
        uiStateFlow.value = uiStateFlow.value.copy(
            showCompanionChat = false,
            companionChatHistory = emptyList(),
            companionSelectedCharacter = DEFAULT_COMPANION_NAME,
            companionSuggestedQuestions = emptyList(),
            isCompanionTyping = false,
            isCompanionStreaming = false,
            companionError = null,
            companionFailedQuestion = null
        )
    }

    fun openCompanionChat(defaultQuery: String? = null, character: String? = null) {
        val s = uiStateFlow.value
        uiStateFlow.value = s.copy(
            showCompanionChat = true,
            companionSelectedCharacter = character ?: s.companionSelectedCharacter,
            companionSuggestedQuestions = s.companionSuggestedQuestions.ifEmpty { CompanionContext.DEFAULT_SUGGESTIONS }
        )
        if (!defaultQuery.isNullOrBlank()) sendMessage(defaultQuery)
    }

    fun closeCompanionChat() {
        uiStateFlow.value = uiStateFlow.value.copy(showCompanionChat = false)
    }

    fun selectCharacter(character: String) {
        uiStateFlow.value = uiStateFlow.value.copy(companionSelectedCharacter = character)
    }

    fun clearHistory() {
        generation++
        sendJob?.cancel()
        uiStateFlow.value = uiStateFlow.value.copy(
            companionChatHistory = emptyList(),
            isCompanionTyping = false,
            isCompanionStreaming = false,
            companionError = null,
            companionFailedQuestion = null,
            companionSuggestedQuestions = CompanionContext.DEFAULT_SUGGESTIONS
        )
    }

    fun sendMessage(messageText: String) = send(messageText.trim(), isRetry = false)

    /** Nút "Thử lại" của bong bóng lỗi: hỏi lại đúng câu vừa thất bại (không thêm câu hỏi vào hội thoại lần nữa). */
    fun retryLast() {
        val question = uiStateFlow.value.companionFailedQuestion ?: return
        send(question, isRetry = true)
    }

    private fun send(question: String, isRetry: Boolean) {
        if (question.isBlank()) return
        val s = uiStateFlow.value
        // Một câu trả lời còn đang được stream: không nhận câu mới. Cắt ngang sẽ để nửa câu trả lời lại làm "câu trả lời cuối" (rồi còn
        // gửi lại cho AI làm lịch sử). Giao diện cũng khoá ô nhập tới lúc này; "Xoá hội thoại" vẫn là lối thoát nếu luồng treo.
        if (s.isCompanionStreaming) return
        sendJob?.cancel()
        val myGeneration = ++generation

        val turn = CompanionContext.prepareTurn(s.companionChatHistory, question, isRetry)
        // Máy chủ chỉ trả lời cho tài khoản đã đăng nhập: khỏi gọi mạng chỉ để nhận 401
        if (TruyenCloudApi.authToken.isBlank()) {
            uiStateFlow.value = s.copy(
                companionChatHistory = turn.shown,
                isCompanionTyping = false,
                companionError = AiErrors.MSG_LOGIN_REQUIRED,
                companionFailedQuestion = question
            )
            return
        }

        val chapter = s.currentChapter
        val paragraphs = s.paragraphs.ifEmpty { s.displayParagraphs }
        val center = CompanionContext.centerIndex(
            paragraphCount = paragraphs.size,
            scrollFraction = scrollFraction(),
            restoreFraction = s.restoreScrollPosition,
            ttsActive = s.isTtsActive,
            ttsParagraph = s.ttsCurrentParagraph
        )
        val context = CompanionContext.window(paragraphs, center).ifBlank { null }
        val novelTitle = s.novel?.let { it.titleVi.ifBlank { it.title } } ?: "Tác phẩm"
        val chapterTitle = chapter?.let { it.titleVi.ifBlank { it.title } } ?: "Chương hiện tại"
        val character = s.companionSelectedCharacter
        val assistantIndex = turn.shown.size

        uiStateFlow.value = s.copy(
            companionChatHistory = turn.shown,
            isCompanionTyping = true,
            isCompanionStreaming = true,
            companionError = null,
            companionFailedQuestion = null
        )

        sendJob = scope.launch {
            var streamed = ""
            try {
                val res = companionApi.chatStream(
                    novelTitle = novelTitle,
                    chapterTitle = chapterTitle,
                    chapterNumber = chapter?.chapterNumber,
                    userMessage = question,
                    contextText = context,
                    characterName = character,
                    chatHistory = turn.request
                ) { chunk ->
                    if (myGeneration != generation) return@chatStream
                    streamed += chunk
                    putAssistant(assistantIndex, streamed)
                }
                if (myGeneration != generation) return@launch
                when (val result = CompanionPolicy.classify(res)) {
                    is CompanionResult.Answer -> {
                        putAssistant(assistantIndex, result.text)
                        // Luồng SSE không mang câu hỏi gợi ý: giữ bộ đang có
                        val s2 = uiStateFlow.value
                        uiStateFlow.value = s2.copy(
                            companionSuggestedQuestions = result.suggestions.ifEmpty {
                                s2.companionSuggestedQuestions.ifEmpty { CompanionContext.DEFAULT_SUGGESTIONS }
                            },
                            isCompanionTyping = false,
                            isCompanionStreaming = false,
                            companionError = null,
                            companionFailedQuestion = null
                        )
                    }
                    is CompanionResult.Failed -> fail(assistantIndex, question, result.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (myGeneration != generation) return@launch
                fail(assistantIndex, question, AiErrors.message(e))
            } finally {
                // Mọi đường kết thúc của lượt HIỆN HÀNH đều phải mở lại ô nhập (lượt đã bị thay / xoá do lượt khác lo)
                if (myGeneration == generation && uiStateFlow.value.isCompanionStreaming) {
                    uiStateFlow.value = uiStateFlow.value.copy(isCompanionTyping = false, isCompanionStreaming = false)
                }
            }
        }
    }

    /** Đặt / cập nhật tin trả lời của trợ lý ở vị trí [index] (chưa có thì thêm vào cuối). */
    private fun putAssistant(index: Int, text: String) {
        val s = uiStateFlow.value
        uiStateFlow.value = s.copy(companionChatHistory = CompanionContext.withAssistant(s.companionChatHistory, index, text), isCompanionTyping = false)
    }

    /** Lượt thất bại: bỏ phần chữ đã stream (thường là câu xin lỗi dự phòng), giữ câu hỏi của người đọc và hiện bong bóng lỗi. */
    private fun fail(index: Int, question: String, message: String) {
        val s = uiStateFlow.value
        uiStateFlow.value = s.copy(
            companionChatHistory = CompanionContext.withoutAssistant(s.companionChatHistory, index),
            isCompanionTyping = false,
            isCompanionStreaming = false,
            companionError = message,
            companionFailedQuestion = question
        )
    }
}
