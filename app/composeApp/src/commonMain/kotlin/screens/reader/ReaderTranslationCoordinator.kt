package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.reader.ReaderTranslationFailure
import com.truyencloud.shared.reader.TranslationFailureKind
import com.truyencloud.shared.repo.ChapterRepository
import com.truyencloud.shared.repo.TranslateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ReaderTranslationCoordinator(
    private val translateRepository: TranslateRepository,
    private val chapterRepository: ChapterRepository,
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope,
    /** Vị trí cuộn gần nhất (0..1): giữ đúng chỗ đang đọc khi nội dung được thay mà số đoạn đổi. */
    private val scrollFraction: () -> Float = { 0f },
    private val onContentUpdated: (List<String>) -> Unit
) {
    private var translationJob: Job? = null

    /** Chương mà [translationJob] đang dịch / cào lại — để cờ "đang dịch chương N" luôn dọn được khi lượt đó bị huỷ. */
    private var jobChapter: Int? = null

    fun openRetranslateDialog() { uiStateFlow.value = uiStateFlow.value.copy(showRetranslateDialog = true) }
    fun closeRetranslateDialog() { uiStateFlow.value = uiStateFlow.value.copy(showRetranslateDialog = false) }
    fun onRetranslatePromptChange(prompt: String) { uiStateFlow.value = uiStateFlow.value.copy(retranslatePrompt = prompt) }

    /**
     * Nút "Dịch AI" (chương chưa có bản dịch): KHÔNG force, để máy chủ trả ngay bản đã lưu / đã cache nếu có thay vì
     * chạy lại AI một cách tốn kém.
     */
    fun triggerTranslation() = runTranslation(null, "vi", uiStateFlow.value.englishLevel, force = false)

    /** Dịch lại theo yêu cầu người đọc (prompt riêng / ngôn ngữ đích / bản tạm → AI): luôn bỏ qua bản đã lưu. */
    fun triggerRetranslation(
        customPrompt: String? = null,
        targetLanguage: String = "vi",
        englishLevel: String = uiStateFlow.value.englishLevel
    ) = runTranslation(customPrompt, targetLanguage, englishLevel, force = true)

    /** Dừng lượt dịch / cào lại đang chờ; nội dung đang đọc giữ nguyên. */
    fun cancelTranslation() {
        val job = translationJob
        if (job?.isActive != true) return
        job.cancel()
        translationJob = null
        val owned = jobChapter
        jobChapter = null
        val s = uiStateFlow.value
        if (!s.isTranslating) {
            // Cờ "đang dịch" đã tắt (người đọc sang chương khác) nhưng số chương của lượt vừa huỷ vẫn nằm lại: dọn đi, kẻo lần tải
            // chương đó sau này tưởng nó còn đang được dịch (huy hiệu "đang dịch" kẹt mãi)
            if (owned != null && s.translatingChapterNumber == owned) uiStateFlow.value = s.copy(translatingChapterNumber = null)
            return
        }
        // Chưa có chữ nào để đọc thì phải nói rõ vì sao màn hình trống (thẻ có nút thử lại / đọc bản gốc)
        val empty = s.paragraphs.isEmpty()
        uiStateFlow.value = s.copy(
            isTranslating = false,
            translatingChapterNumber = null,
            translationError = if (empty) ReaderTranslationFailure.MSG_CANCELLED else s.translationError,
            translationErrorKind = if (empty) TranslationFailureKind.OTHER else s.translationErrorKind
        )
    }

    /**
     * Mở truyện KHÁC: lượt dịch / cào lại của truyện cũ bị huỷ để kết quả (hay lỗi) của nó không rơi vào truyện mới — truyện nào cũng
     * mở ở chương 1 nên chỉ khớp số chương là không đủ. Cờ trên màn hình do `prepareOpen` đặt lại.
     */
    fun cancelForNovelChange() {
        translationJob?.cancel()
        translationJob = null
        jobChapter = null
    }

    private fun runTranslation(customPrompt: String?, targetLanguage: String, englishLevel: String, force: Boolean) {
        val novel = uiStateFlow.value.novel ?: return
        val chapter = uiStateFlow.value.currentChapter ?: return
        val number = chapter.chapterNumber

        // Máy chủ chỉ dịch cho tài khoản đã đăng nhập: khách bấm thì báo ngay, khỏi gửi một yêu cầu chắc chắn nhận 401
        if (TruyenCloudApi.authToken.isBlank()) {
            ReaderRetranslateApplier.loginRequired(uiStateFlow)
            return
        }

        translationJob?.cancel()
        jobChapter = number
        uiStateFlow.value = uiStateFlow.value.copy(
            showRetranslateDialog = false,
            isTranslating = true,
            translatingChapterNumber = number,
            translationError = null,
            translationErrorKind = null,
            englishLevel = englishLevel
        )

        translationJob = scope.launch {
            try {
                val result = translateRepository.translateChapterDetailed(
                    novelId = novel.id,
                    chapterNumber = number,
                    content = chapter.originalContent,
                    force = force,
                    customPrompt = customPrompt,
                    targetLanguage = targetLanguage,
                    englishLevel = englishLevel
                )
                ReaderRetranslateApplier.apply(
                    uiStateFlow, novel.id, chapter, result, targetLanguage, force, scrollFraction, onContentUpdated
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReaderRetranslateApplier.fail(uiStateFlow, novel.id, number, e)
            }
        }
    }

    fun triggerRecrawl(autoTranslate: Boolean = true, customPrompt: String? = null) {
        val novel = uiStateFlow.value.novel ?: return
        val chapter = uiStateFlow.value.currentChapter ?: return
        val number = chapter.chapterNumber

        // Cào lại từ nguồn cũng chỉ dành cho tài khoản đã đăng nhập (máy chủ trả 401 cho khách)
        if (TruyenCloudApi.authToken.isBlank()) {
            ReaderRetranslateApplier.loginRequired(uiStateFlow)
            return
        }

        uiStateFlow.value = uiStateFlow.value.copy(
            showRetranslateDialog = false,
            showSettingsSheet = false,
            isTranslating = true,
            translatingChapterNumber = number,
            translationError = null,
            translationErrorKind = null
        )

        translationJob?.cancel()
        jobChapter = number
        translationJob = scope.launch {
            try {
                val updated = chapterRepository.recrawlChapter(
                    novelId = novel.id,
                    chapterNumber = number,
                    autoTranslate = autoTranslate,
                    customPrompt = customPrompt
                )
                ReaderRetranslateApplier.applyRecrawl(uiStateFlow, novel.id, number, updated, onContentUpdated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReaderRetranslateApplier.fail(uiStateFlow, novel.id, number, e, otherMessage = "Không cào lại được bản gốc từ nguồn")
            }
        }
    }
}
