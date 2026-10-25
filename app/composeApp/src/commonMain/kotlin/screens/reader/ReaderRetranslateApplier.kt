package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.reader.BilingualPairs
import com.truyencloud.shared.reader.ChapterNavigation
import com.truyencloud.shared.reader.ProvisionalTranslation
import com.truyencloud.shared.reader.ReaderScrollRestore
import com.truyencloud.shared.reader.ReaderTranslationFailure
import com.truyencloud.shared.reader.TranslationFailureKind
import com.truyencloud.shared.repo.TranslateRepository
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Áp kết quả một lượt dịch / dịch lại / cào lại (do người đọc yêu cầu) vào [ReaderUiState], hoặc báo lỗi mà KHÔNG làm mất
 * nội dung đang đọc. Kết quả đã được `TranslateRepository` lưu vào Room; ở đây chỉ quyết định cách hiển thị.
 *
 * Mỗi lượt gắn với MỘT chương của MỘT truyện: truyện nào cũng mở ở chương 1 nên chỉ so số chương là chưa đủ — bản dịch chậm của
 * chương 1 truyện A sẽ đè lên chương 1 của truyện B vừa mở. Mọi hàm ở đây nhận thêm `novelId` của lượt yêu cầu.
 */
internal object ReaderRetranslateApplier {

    /** Màn hình còn đang hiển thị đúng chương [number] của truyện [novelId] không. */
    private fun isShown(s: ReaderUiState, novelId: String, number: Int): Boolean =
        ChapterNavigation.isShown(s.novel?.id, s.currentChapter?.chapterNumber, novelId, number)

    /**
     * Lượt này không còn hiển thị được (đã sang chương / truyện khác): chỉ nhả cờ "đang dịch" nếu nó còn thuộc về lượt này. Truyện khác
     * có thể đang dịch một chương cùng số, nên phải cùng truyện mới được nhả.
     */
    private fun releaseFlag(uiStateFlow: MutableStateFlow<ReaderUiState>, novelId: String, number: Int) {
        val s = uiStateFlow.value
        if (s.novel?.id == novelId && s.translatingChapterNumber == number) {
            uiStateFlow.value = s.copy(isTranslating = false, translatingChapterNumber = null)
        }
    }

    fun apply(
        uiStateFlow: MutableStateFlow<ReaderUiState>,
        novelId: String,
        requested: Chapter,
        result: TranslateRepository.ChapterTranslation,
        targetLanguage: String,
        forced: Boolean,
        scrollFraction: () -> Float,
        onContentUpdated: (List<String>) -> Unit
    ) {
        val number = requested.chapterNumber
        val s = uiStateFlow.value
        val current = s.currentChapter
        if (current == null || !isShown(s, novelId, number)) {
            // Người đọc đã sang chương / truyện khác: bản dịch đã lưu trong máy, lần mở chương đó sẽ dùng.
            releaseFlag(uiStateFlow, novelId, number)
            return
        }
        val base = s.copy(
            isTranslating = false,
            translatingChapterNumber = null,
            translationError = null,
            translationErrorKind = null
        )
        when (targetLanguage.lowercase()) {
            "en", "english" -> {
                val paragraphs = splitReaderParagraphs(result.text)
                uiStateFlow.value = base.copy(
                    isEnglishMode = true,
                    isBilingualMode = false,
                    isProgressiveMode = false,
                    englishParagraphs = paragraphs,
                    bilingualViParagraphs = emptyList(),
                    showOriginal = false
                )
                onContentUpdated(paragraphs)
            }
            "bilingual" -> applyBilingual(uiStateFlow, base, result.text, onContentUpdated)
            else -> applyVietnamese(uiStateFlow, base, current, result, forced, scrollFraction, onContentUpdated)
        }
    }

    /** Cào lại xong: chỉ áp khi vẫn đang xem đúng chương đó của đúng truyện đó (cùng quy tắc với [apply]). */
    fun applyRecrawl(
        uiStateFlow: MutableStateFlow<ReaderUiState>,
        novelId: String,
        number: Int,
        updated: Chapter,
        onContentUpdated: (List<String>) -> Unit
    ) {
        val s = uiStateFlow.value
        if (!isShown(s, novelId, number)) {
            releaseFlag(uiStateFlow, novelId, number)
            return
        }
        val text = if (!s.showOriginal && !updated.translatedContent.isNullOrBlank()) {
            updated.translatedContent!!
        } else {
            updated.originalContent ?: ""
        }
        val paras = splitReaderParagraphs(text)
        uiStateFlow.value = s.copy(
            isTranslating = false,
            translatingChapterNumber = null,
            currentChapter = updated,
            paragraphs = paras,
            isProvisionalTranslation = !s.showOriginal &&
                ProvisionalTranslation.isProvisional(false, updated.translationEngine)
        )
        onContentUpdated(paras)
    }

    private fun applyBilingual(
        uiStateFlow: MutableStateFlow<ReaderUiState>,
        base: ReaderUiState,
        text: String,
        onContentUpdated: (List<String>) -> Unit
    ) {
        val pairs = BilingualPairs.parse(text)
        if (!BilingualPairs.isUsable(pairs)) {
            uiStateFlow.value = base.copy(toastMessage = "Bản song ngữ máy chủ trả về chưa đúng định dạng — giữ nguyên chế độ hiện tại.")
            return
        }
        val english = pairs.map { it.english }
        uiStateFlow.value = base.copy(
            isBilingualMode = true,
            isEnglishMode = false,
            isProgressiveMode = false,
            englishParagraphs = english,
            bilingualViParagraphs = pairs.map { it.vietnamese },
            showOriginal = false
        )
        onContentUpdated(english)
    }

    private fun applyVietnamese(
        uiStateFlow: MutableStateFlow<ReaderUiState>,
        base: ReaderUiState,
        current: Chapter,
        result: TranslateRepository.ChapterTranslation,
        forced: Boolean,
        scrollFraction: () -> Float,
        onContentUpdated: (List<String>) -> Unit
    ) {
        val paragraphs = splitReaderParagraphs(result.text)
        // Dịch lại bắt buộc mà AI lỗi thì máy chủ trả NGUYÊN bản cũ: báo thật để người đọc không tưởng đã dịch lại xong.
        val unchanged = forced && result.text == current.translatedContent
        val keepPlace = ReaderScrollRestore.placeAfterReplace(base.paragraphs.size, paragraphs.size, scrollFraction())
        uiStateFlow.value = base.copy(
            currentChapter = current.copy(translatedContent = result.text, translationEngine = result.engine),
            paragraphs = paragraphs,
            isProvisionalTranslation = result.isFallback,
            isEnglishMode = false,
            isBilingualMode = false,
            englishParagraphs = emptyList(),
            bilingualViParagraphs = emptyList(),
            showOriginal = false,
            restoreScrollPosition = if (keepPlace > 0f) keepPlace else base.restoreScrollPosition,
            toastMessage = when {
                unchanged && result.isFallback -> "AI vẫn đang bận — tạm giữ bản dịch máy."
                unchanged -> "Máy chủ chưa dịch lại được — giữ nguyên bản dịch hiện tại."
                forced && result.isFallback -> "AI đang bận nên tạm dùng bản dịch máy."
                else -> null
            }
        )
        if (!unchanged) onContentUpdated(paragraphs)
    }

    /**
     * Lượt dịch / cào lại thất bại. Có nội dung đang đọc thì chỉ hiện thông báo ngắn và GIỮ NGUYÊN nội dung
     * (vd. dịch lại với prompt riêng gặp HTTP 503); chưa có gì để đọc thì hiện thẻ lỗi có nút thử lại.
     * Người đọc đã sang truyện khác thì lỗi của lượt cũ không liên quan gì tới màn hình mới.
     */
    fun fail(
        uiStateFlow: MutableStateFlow<ReaderUiState>,
        novelId: String,
        number: Int,
        error: Throwable,
        otherMessage: String = ReaderTranslationFailure.MSG_OTHER
    ) {
        val s = uiStateFlow.value
        if (s.novel?.id != novelId) return
        val cleared = if (s.translatingChapterNumber == number) s.copy(isTranslating = false, translatingChapterNumber = null) else s
        if (cleared.currentChapter?.chapterNumber != number) {
            uiStateFlow.value = cleared
            return
        }
        val kind = ReaderTranslationFailure.classify(error)
        val message = if (kind == TranslationFailureKind.OTHER) otherMessage else ReaderTranslationFailure.message(kind)
        showFailure(uiStateFlow, cleared, kind, message)
    }

    /**
     * Khách chưa đăng nhập bấm dịch / dịch lại / cào lại: máy chủ chỉ làm cho tài khoản (khách nhận 401) nên báo ngay thay vì gửi một
     * yêu cầu chắc chắn bị từ chối. Hiện giống [fail]: chưa có chữ thì thẻ lỗi, có chữ thì thông báo ngắn.
     */
    fun loginRequired(uiStateFlow: MutableStateFlow<ReaderUiState>) {
        val kind = TranslationFailureKind.LOGIN_REQUIRED
        val closed = uiStateFlow.value.copy(showRetranslateDialog = false, showSettingsSheet = false)
        showFailure(uiStateFlow, closed, kind, ReaderTranslationFailure.message(kind))
    }

    private fun showFailure(
        uiStateFlow: MutableStateFlow<ReaderUiState>,
        base: ReaderUiState,
        kind: TranslationFailureKind,
        message: String
    ) {
        uiStateFlow.value = if (base.paragraphs.isEmpty()) {
            base.copy(translationError = message, translationErrorKind = kind)
        } else {
            base.copy(toastMessage = "$message. Giữ nguyên nội dung đang đọc.")
        }
    }
}
