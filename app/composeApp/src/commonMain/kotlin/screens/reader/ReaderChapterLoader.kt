package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.reader.ChapterNavigation
import com.truyencloud.shared.reader.ProvisionalTranslation
import com.truyencloud.shared.reader.ReaderTranslationFailure
import com.truyencloud.shared.reader.TranslationFailureKind
import com.truyencloud.shared.repo.ChapterRepository
import com.truyencloud.shared.repo.ReaderRepository
import com.truyencloud.shared.repo.TranslateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Các điểm nối giữa bộ tải chương và [ReaderViewModel] (TTS, dấu trang, tải trước...). */
internal class ReaderChapterHooks(
    /** Sắp công bố chương KHÁC: huỷ việc nền gắn với chương cũ (chuyển thể Anh, hoà trộn...). */
    val onSwitching: () -> Unit,
    /** Chương đã có đoạn văn (hoặc báo lỗi dịch): nạp dấu trang, TTS, tải trước... */
    val onReady: (novelId: String, chapterNumber: Int, startTts: Boolean, hasParagraphs: Boolean) -> Unit,
    /** Nội dung của CHÍNH chương đang mở vừa đổi (đọc bản gốc...): dựng lại đoạn đọc TTS. */
    val onContentChanged: (List<String>) -> Unit,
    /** Mở một truyện KHÁC truyện đang giữ: huỷ việc nền gắn với truyện cũ (dịch lại / cào lại...) kẻo kết quả của nó rơi vào truyện mới. */
    val onNovelChanged: () -> Unit = {}
)

/**
 * Tải một chương: lấy nội dung, dịch AI nếu chưa có bản dịch, rồi công bố vào [ReaderUiState].
 *
 * Bất biến quan trọng:
 *  - Chương mới CHỈ thay chương cũ khi đã có nội dung (hoặc đã báo lỗi dịch): trong lúc chờ, chương cũ vẫn đọc được
 *    và giao diện hiện thanh tiến trình; tải lỗi thì hiện banner ngay trên chương cũ ([ReaderUiState.chapterError]).
 *  - Dịch hỏng KHÔNG âm thầm rơi về tiếng Trung: lỗi thân thiện vào [ReaderUiState.translationError], người đọc tự chọn
 *    "Đọc bản gốc".
 *  - Đổi chương thì xoá trạng thái gắn với chương cũ (Anh / đọc hiểu / tóm tắt...) — xem [withChapterScopedStateCleared].
 */
internal class ReaderChapterLoader(
    private val chapterRepository: ChapterRepository,
    private val translateRepository: TranslateRepository,
    private val readerRepository: ReaderRepository,
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope,
    private val hooks: ReaderChapterHooks,
    private val upgrader: ReaderProvisionalUpgrader
) {
    private var job: Job? = null
    private var generation = 0
    private var inTranslationPhase = false

    private var state: ReaderUiState
        get() = uiStateFlow.value
        set(value) { uiStateFlow.value = value }

    val isLoading: Boolean get() = job?.isActive == true

    // ── Tải chương ──────────────────────────────────────────────────────────

    fun load(novelId: String, chapterNumber: Int, autoStartTts: Boolean = false, countCompletion: Boolean = false) {
        val gen = ++generation
        job?.cancel()
        upgrader.cancel()
        // Lượt tải vừa bị thay đang ở pha dịch: cờ "đang dịch chương N" là của NÓ và job dịch đã bị huỷ, nên không ai còn dịch chương N.
        val orphanedTranslation = inTranslationPhase
        inTranslationPhase = false
        val s = state
        // Chỉ giữ cờ "đang dịch" của đúng chương sắp tải (lượt dịch lại của người đọc vẫn chạy nền và tự dọn khi xong / bị huỷ). Cờ của chương
        // KHÁC không được dính sang lượt tải này: lượt đó có thể đã bị huỷ, và cờ còn nằm lại sẽ làm lần tải chương ấy sau này tưởng nó còn
        // đang được dịch (huy hiệu "Đang dịch Ch. N…" kẹt khi lần tải hỏng / bị huỷ).
        val keepTranslatingFlag = !orphanedTranslation && s.translatingChapterNumber == chapterNumber
        val startTts = autoStartTts || s.isTtsActive
        val sameChapter = s.novel?.id == novelId && s.currentChapter?.chapterNumber == chapterNumber
        state = s.copy(
            // "Đọc bản gốc" chỉ áp cho chương đang xem: sang chương khác thì dịch lại bình thường, không kẹt ở tiếng Trung
            showOriginal = if (sameChapter) s.showOriginal else false,
            targetChapterNumber = chapterNumber,
            isLoadingChapter = !(sameChapter && s.paragraphs.isNotEmpty()),
            chapterError = null,
            translationError = null,
            translationErrorKind = null,
            isTtsActive = startTts,
            isTranslating = keepTranslatingFlag,
            translatingChapterNumber = if (keepTranslatingFlag) chapterNumber else null,
            isAutoScrolling = false,
            isAutoScrollPaused = false
        )
        job = scope.launch {
            try {
                run(novelId, chapterNumber, startTts, countCompletion)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (gen == generation) failLoad(chapterNumber, t)
            } finally {
                if (gen == generation) inTranslationPhase = false
            }
        }
    }

    private suspend fun run(novelId: String, number: Int, startTts: Boolean, countCompletion: Boolean) {
        var chapter = chapterRepository.getChapter(novelId, number)
        currentCoroutineContext().ensureActive()
        val stored = chapter.translatedContent
        val junk = isJunkTranslation(stored)
        val usable = !stored.isNullOrBlank() && !junk
        val original = chapter.originalContent
        val useOriginal = state.showOriginal && !original.isNullOrBlank()

        val text: String
        var provisional = false
        if (useOriginal || usable) {
            text = if (useOriginal) original.orEmpty() else stored.orEmpty()
            provisional = !useOriginal && ProvisionalTranslation.isProvisional(false, chapter.translationEngine)
        } else if (original.isNullOrBlank()) {
            text = ""
        } else {
            val translated = translateForReading(novelId, chapter, junk, startTts) ?: return
            chapter = chapter.copy(
                titleVi = translated.titleVi.ifBlank { chapter.titleVi },
                translatedContent = translated.text,
                translationEngine = translated.engine
            )
            text = translated.text
            provisional = translated.isFallback
        }
        currentCoroutineContext().ensureActive()

        val paragraphs = splitReaderParagraphs(text)
        val restore = chapter.scrollPosition
        val last = lastKnownChapter(novelId, number)
        publish(chapter, paragraphs) {
            it.copy(
                isLoadingChapter = false,
                isTranslating = false,
                translatingChapterNumber = null,
                targetChapterNumber = null,
                isProvisionalTranslation = provisional,
                restoreScrollPosition = restore,
                lastChapterNumber = last,
                chapterError = if (paragraphs.isEmpty()) {
                    "Chương $number chưa có nội dung — nguồn có thể chưa cập nhật. Hãy thử lại sau hoặc chọn chương khác."
                } else null
            )
        }
        hooks.onReady(novelId, number, startTts, paragraphs.isNotEmpty())
        if (ChapterNavigation.shouldCountCompletion(countCompletion, loadSucceeded = true)) {
            scope.launch { readerRepository.recordReadingSession(minutes = 0, chaptersCompleted = 1) }
        }
        if (provisional && paragraphs.isNotEmpty()) upgrader.maybeStart(novelId, chapter)
    }

    /** Dịch chương chưa có bản dịch. Trả null khi thất bại (trạng thái lỗi đã được công bố). */
    private suspend fun translateForReading(
        novelId: String,
        chapter: Chapter,
        junk: Boolean,
        startTts: Boolean
    ): TranslateRepository.ChapterTranslation? {
        val number = chapter.chapterNumber
        // Máy chủ chỉ dịch cho tài khoản đã đăng nhập: khỏi gọi mạng chỉ để nhận 401.
        if (TruyenCloudApi.authToken.isBlank()) {
            publishTranslationFailure(chapter, TranslationFailureKind.LOGIN_REQUIRED, startTts)
            hooks.onReady(novelId, number, startTts, false)
            return null
        }
        inTranslationPhase = true
        val shown = state.currentChapter?.chapterNumber == number && state.novel?.id == novelId
        publish(chapter, if (shown) state.paragraphs else emptyList()) {
            it.copy(isLoadingChapter = false, isTranslating = true, translatingChapterNumber = number)
        }
        return try {
            translateRepository.translateChapterDetailed(
                novelId = novelId,
                chapterNumber = number,
                content = chapter.originalContent,
                force = junk
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            publishTranslationFailure(chapter, ReaderTranslationFailure.classify(e), startTts)
            hooks.onReady(novelId, number, startTts, false)
            null
        }
    }

    private fun publishTranslationFailure(chapter: Chapter, kind: TranslationFailureKind, startTts: Boolean) {
        val message = ReaderTranslationFailure.message(kind)
        val shown = state.currentChapter?.chapterNumber == chapter.chapterNumber
        publish(chapter, if (shown) state.paragraphs else emptyList()) {
            it.copy(
                isLoadingChapter = false,
                isTranslating = false,
                translatingChapterNumber = null,
                targetChapterNumber = null,
                translationError = message,
                translationErrorKind = kind,
                restoreScrollPosition = chapter.scrollPosition,
                // Bộ đọc TTS chỉ biết "không tải được chương kế" qua chapterError: đang tự chuyển chương thì báo để nó dừng hẳn.
                chapterError = if (startTts) message else null,
                ttsError = if (startTts) UNTRANSLATED_TTS_MESSAGE else it.ttsError
            )
        }
    }

    private fun failLoad(number: Int, t: Throwable) {
        val s = state
        // Hỏng giữa pha dịch của chính lượt tải này: job dịch đã chết nên cờ "đang dịch" do nó đặt không còn ý nghĩa
        val orphaned = inTranslationPhase
        state = s.copy(
            isLoadingChapter = false,
            chapterError = ReaderTranslationFailure.chapterLoadMessage(t, number),
            isTranslating = s.isTranslating && !orphaned,
            translatingChapterNumber = if (orphaned) null else s.translatingChapterNumber
        )
    }

    /** Công bố [chapter] + [paragraphs]; sang chương/truyện khác thì xoá trạng thái của chương cũ trước. */
    private fun publish(chapter: Chapter, paragraphs: List<String>, transform: (ReaderUiState) -> ReaderUiState) {
        val s = state
        val novelId = s.novel?.id ?: chapter.novelId
        val switching = s.chapterKey != ChapterNavigation.chapterKey(novelId, chapter.chapterNumber)
        if (switching) hooks.onSwitching()
        val base = if (switching) s.withChapterScopedStateCleared() else s
        val updatedChapters = if (chapter.titleVi.isNotBlank() && base.chapters.isNotEmpty()) {
            base.chapters.map { ch ->
                if (ch.chapterNumber == chapter.chapterNumber && ch.titleVi != chapter.titleVi) {
                    ch.copy(titleVi = chapter.titleVi)
                } else ch
            }
        } else base.chapters
        state = transform(base.copy(currentChapter = chapter, chapters = updatedChapters, paragraphs = paragraphs))
    }

    private suspend fun lastKnownChapter(novelId: String, current: Int): Int {
        val s = state
        val dbLast = try {
            chapterRepository.getLastChapterNumber(novelId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0
        }
        return ChapterNavigation.lastKnown(
            s.lastChapterNumber, dbLast, s.novel?.totalChapters ?: 0, s.chapters.maxOfOrNull { it.chapterNumber } ?: 0, current
        )
    }

    // ── Thử lại / huỷ ──────────────────────────────────────────────────────

    /** Tải lại chương vừa thất bại (hoặc dịch lại chương đang mở nếu lỗi nằm ở bước dịch). */
    fun retry() {
        val s = state
        val novel = s.novel ?: return
        val number = s.targetChapterNumber ?: s.currentChapter?.chapterNumber ?: return
        load(novel.id, number, autoStartTts = s.isTtsActive)
    }

    fun dismissChapterError() {
        state = state.copy(chapterError = null, targetChapterNumber = null)
    }

    /** Huỷ lượt tải đang chờ (chương cũ vẫn đọc được). */
    fun cancelLoad() {
        if (!isLoading) return
        val orphaned = inTranslationPhase
        abandonJob()
        val s = state
        state = s.copy(
            isLoadingChapter = false,
            targetChapterNumber = null,
            isTranslating = s.isTranslating && !orphaned,
            translatingChapterNumber = if (orphaned) null else s.translatingChapterNumber
        )
    }

    /** [Hủy] ở thẻ tiến trình dịch: dừng hẳn yêu cầu đang chờ và để màn hình dùng được. */
    fun cancelTranslation() {
        if (!inTranslationPhase || !isLoading) return
        abandonJob()
        val s = state
        val empty = s.paragraphs.isEmpty()
        state = s.copy(
            isLoadingChapter = false,
            isTranslating = false,
            translatingChapterNumber = null,
            targetChapterNumber = null,
            translationError = if (empty) ReaderTranslationFailure.MSG_CANCELLED else null,
            translationErrorKind = if (empty) TranslationFailureKind.OTHER else null
        )
        // Bỏ dịch giữa chừng: onReady chưa kịp chạy cho chương này, mà dấu trang / tải trước chương kế vẫn cần
        val novelId = s.novel?.id
        val number = s.currentChapter?.chapterNumber
        if (novelId != null && number != null) hooks.onReady(novelId, number, s.isTtsActive, false)
    }

    private companion object {
        const val UNTRANSLATED_TTS_MESSAGE = "Chương này chưa có bản dịch nên chưa nghe được. Hãy dịch chương rồi bấm phát."
    }

    private fun abandonJob() {
        generation++
        job?.cancel()
        inTranslationPhase = false
    }
}
