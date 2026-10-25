package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.api.EnglishApi
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.reader.AiErrors
import com.truyencloud.shared.reader.ComprehensionPolicy
import com.truyencloud.shared.reader.QuizOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Trắc nghiệm đọc hiểu. Bộ câu hỏi MẪU của máy chủ (`is_fallback`) là câu chung chung với đáp án dễ đoán, không phải bài kiểm tra về
 * chương này: không hiện, không lưu, chỉ báo lỗi kèm nút thử lại ([ComprehensionPolicy]). Kết quả về muộn của chương trước (người
 * đọc đã sang chương khác) cũng bị bỏ thay vì ghi đè câu hỏi của chương mới.
 */
class ReaderComprehensionCoordinator(
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope
) {
    private var loadJob: Job? = null

    fun openComprehension() {
        uiStateFlow.value = uiStateFlow.value.copy(showComprehensionSheet = true)
        val s = uiStateFlow.value
        // Dùng cờ trong trạng thái (không phải loadJob): đổi chương xoá cờ này, còn lượt tải cũ vẫn có thể đang chạy dở
        if (s.comprehensionQuestions.isEmpty() && !s.isLoadingComprehension) loadComprehensionQuestions()
    }

    fun closeComprehension() { uiStateFlow.value = uiStateFlow.value.copy(showComprehensionSheet = false) }

    fun answerComprehensionQuestion(questionId: String, optionIndex: Int) {
        if (uiStateFlow.value.isComprehensionSubmitted) return
        val current = uiStateFlow.value.comprehensionAnswers.toMutableMap()
        current[questionId] = optionIndex
        uiStateFlow.value = uiStateFlow.value.copy(comprehensionAnswers = current)
    }

    fun submitComprehension() {
        val questions = uiStateFlow.value.comprehensionQuestions
        val answers = uiStateFlow.value.comprehensionAnswers
        var correct = 0
        for (q in questions) {
            if (answers[q.id] == q.correctIndex) correct++
        }
        uiStateFlow.value = uiStateFlow.value.copy(isComprehensionSubmitted = true, comprehensionScore = correct)
    }

    /** "Làm lại": xoá đáp án đã chọn (giữ nguyên bộ câu hỏi). */
    fun retryComprehension() {
        uiStateFlow.value = uiStateFlow.value.copy(isComprehensionSubmitted = false, comprehensionAnswers = emptyMap(), comprehensionScore = 0)
    }

    /** Tải bộ câu hỏi mới từ máy chủ (cũng là việc của nút "Thử lại" khi lần trước lỗi). */
    fun loadComprehensionQuestions() {
        val s = uiStateFlow.value
        val chapter = s.currentChapter ?: return
        val paragraphs = if (s.englishParagraphs.isNotEmpty()) s.englishParagraphs else s.paragraphs
        if (paragraphs.isEmpty()) return
        // Máy chủ chỉ ra câu hỏi cho tài khoản đã đăng nhập: khỏi gọi mạng chỉ để nhận 401
        if (TruyenCloudApi.authToken.isBlank()) {
            uiStateFlow.value = s.copy(isLoadingComprehension = false, comprehensionError = AiErrors.MSG_LOGIN_REQUIRED)
            return
        }

        val key = s.chapterKey
        loadJob?.cancel()
        uiStateFlow.value = s.copy(isLoadingComprehension = true, comprehensionError = null)
        loadJob = scope.launch {
            try {
                val res = EnglishApi().generateComprehension(
                    title = chapter.titleVi.ifBlank { chapter.title },
                    paragraphs = paragraphs,
                    novelId = s.novel?.id ?: "",
                    chapterNumber = chapter.chapterNumber
                )
                if (stale(key)) return@launch
                uiStateFlow.value = when (val outcome = ComprehensionPolicy.outcome(res)) {
                    is QuizOutcome.Ready -> uiStateFlow.value.copy(
                        isLoadingComprehension = false,
                        comprehensionError = null,
                        comprehensionQuestions = outcome.questions,
                        comprehensionAnswers = emptyMap(),
                        isComprehensionSubmitted = false,
                        comprehensionScore = 0
                    )
                    is QuizOutcome.Failed -> uiStateFlow.value.copy(
                        isLoadingComprehension = false,
                        comprehensionQuestions = emptyList(),
                        comprehensionError = outcome.message
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (stale(key)) return@launch
                uiStateFlow.value = uiStateFlow.value.copy(isLoadingComprehension = false, comprehensionError = AiErrors.message(e))
            }
        }
    }

    /** Người đọc đã sang chương khác trong lúc chờ: câu hỏi của chương cũ không được ghi vào màn mới. */
    private fun stale(key: String?): Boolean = uiStateFlow.value.chapterKey != key
}
