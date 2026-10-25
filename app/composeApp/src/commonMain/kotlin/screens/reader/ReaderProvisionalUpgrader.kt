package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.reader.ProvisionalRetryPolicy
import com.truyencloud.shared.reader.ProvisionalTranslation
import com.truyencloud.shared.reader.ProvisionalTranslation.UpgradeOutcome
import com.truyencloud.shared.reader.ReaderScrollRestore
import com.truyencloud.shared.repo.TranslateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Nâng cấp NỀN bản dịch tạm (Google dự phòng) lên bản AI.
 *
 * Ngay sau khi hiện một chương có bản tạm, nếu đã đăng nhập thì xin máy chủ dịch lại MỘT lần (không force; máy chủ
 * tự giữ hẹn giờ 10 phút mỗi chương). Có bản AI thật và người đọc vẫn ở chương đó thì thay đoạn văn tại chỗ —
 * danh sách cuộn giữ nguyên nên không bị nhảy vị trí.
 */
internal class ReaderProvisionalUpgrader(
    private val translateRepository: TranslateRepository,
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope,
    /** Vị trí cuộn gần nhất (0..1) để giữ đúng chỗ đang đọc khi số đoạn thay đổi. */
    private val scrollFraction: () -> Float,
    /** Nội dung vừa đổi: dựng lại đoạn đọc TTS. */
    private val onSwapped: (List<String>) -> Unit
) {
    private val policy = ProvisionalRetryPolicy()
    private var job: Job? = null

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun maybeStart(novelId: String, chapter: Chapter) {
        val loggedIn = TruyenCloudApi.authToken.isNotBlank()
        if (!policy.tryBegin(novelId, chapter.chapterNumber, isProvisional = true, isLoggedIn = loggedIn)) return
        val number = chapter.chapterNumber
        job?.cancel()
        job = scope.launch {
            val result = try {
                translateRepository.translateChapterDetailed(
                    novelId = novelId,
                    chapterNumber = number,
                    content = chapter.originalContent,
                    force = false
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Lượt xin nền hỏng: vẫn giữ bản tạm + banner, người đọc có nút "Dịch lại bằng AI"
                return@launch
            }
            val s = uiStateFlow.value
            val shown = s.currentChapter
            val sameChapter = s.novel?.id == novelId && shown?.chapterNumber == number
            val outcome = ProvisionalTranslation.outcomeOf(
                resultIsFallback = result.isFallback,
                stillOnSameChapter = sameChapter,
                ttsActive = s.isTtsActive,
                showingOriginal = s.showOriginal
            )
            when {
                outcome == UpgradeOutcome.SWAP_IN_PLACE -> swap(result.text, result.engine)
                // Người đọc đang xem bản gốc: chữ trên màn hình giữ nguyên, nhưng bản dịch của chương phải là bản AI
                // để lúc bấm "Xem Bản Dịch" họ thấy bản mới chứ không phải bản tạm cũ.
                outcome == UpgradeOutcome.KEEP_CURRENT_VIEW && shown != null && sameChapter && s.showOriginal ->
                    uiStateFlow.value = s.copy(currentChapter = shown.copy(translatedContent = result.text, translationEngine = result.engine))
            }
        }
    }

    private fun swap(text: String, engine: String) {
        val s = uiStateFlow.value
        val chapter = s.currentChapter ?: return
        val paragraphs = splitReaderParagraphs(text)
        if (paragraphs.isEmpty()) return
        // Số đoạn đổi thì chỉ số cũ không còn trỏ đúng chỗ: quy theo tỉ lệ đã cuộn.
        val keepPlace = ReaderScrollRestore.placeAfterReplace(s.paragraphs.size, paragraphs.size, scrollFraction())
        uiStateFlow.value = s.copy(
            currentChapter = chapter.copy(translatedContent = text, translationEngine = engine),
            paragraphs = paragraphs,
            isProvisionalTranslation = false,
            restoreScrollPosition = if (keepPlace > 0f) keepPlace else s.restoreScrollPosition,
            toastMessage = "Đã cập nhật bản dịch AI cho chương này"
        )
        onSwapped(paragraphs)
    }
}
