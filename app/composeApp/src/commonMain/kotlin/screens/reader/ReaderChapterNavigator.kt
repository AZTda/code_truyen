package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.library.ReadingHistoryStore
import com.truyencloud.shared.reader.ChapterNavigation
import com.truyencloud.shared.reader.ProvisionalTranslation
import com.truyencloud.shared.reader.ReaderScrollRestore
import com.truyencloud.shared.repo.ChapterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Mở màn đọc, chuyển chương (có chặn ở chương cuối) và chọn đọc bản gốc — phần điều hướng nằm trên [ReaderChapterLoader].
 */
internal class ReaderChapterNavigator(
    private val loader: ReaderChapterLoader,
    private val chapterRepository: ChapterRepository,
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope,
    private val hooks: ReaderChapterHooks,
    private val upgrader: ReaderProvisionalUpgrader
) {
    /** Chương mà người đọc đã cuộn tới thẻ hết chương (mới tính là "đã đọc xong"). */
    private var endReachedKey: String? = null

    private var state: ReaderUiState
        get() = uiStateFlow.value
        set(value) { uiStateFlow.value = value }

    fun noteEndReached() { endReachedKey = state.chapterKey }

    fun recordCurrentHistory(isAudio: Boolean = state.isTtsActive) {
        val n = state.novel ?: return
        val ch = state.currentChapter ?: return
        ReadingHistoryStore.shared.record(n, ch.chapterNumber, ch.titleVi.ifBlank { ch.title }, isAudio = isAudio)
    }

    /**
     * Gọi khi mở màn đọc. Khác chương/truyện với nội dung đang giữ thì bỏ hẳn đoạn văn cũ để màn hình không hiện
     * lẫn tiêu đề mới với nội dung cũ.
     */
    fun prepareOpen(novel: Novel, chapter: Chapter, initialChapters: List<Chapter>, autoStartTts: Boolean, bilingual: Boolean) {
        val cur = state
        val sameNovel = cur.novel?.id == novel.id
        val sameChapter = sameNovel && cur.currentChapter?.chapterNumber == chapter.chapterNumber && cur.paragraphs.isNotEmpty()
        var next = cur
        if (!sameChapter) {
            hooks.onSwitching()
            next = next.withChapterScopedStateCleared().copy(paragraphs = emptyList(), showOriginal = false)
        }
        if (!sameNovel) {
            hooks.onNovelChanged()
            next = next.copy(
                chapters = emptyList(), lastChapterNumber = 0, tocFlags = emptyMap(), showQuickToc = false,
                // Lượt dịch lại / cào lại của truyện cũ vừa bị huỷ: cờ "đang dịch chương N" của nó không được dính sang truyện mới
                isTranslating = false, translatingChapterNumber = null
            )
        }
        endReachedKey = null
        ReadingHistoryStore.shared.record(
            novel = novel,
            chapterNumber = chapter.chapterNumber,
            chapterTitle = chapter.titleVi.ifBlank { chapter.title },
            isAudio = autoStartTts
        )
        state = next.copy(
            novel = novel,
            // Mở lại đúng chương đang giữ nội dung thì giữ bản đầy đủ (chương lấy từ danh sách chỉ có tiêu đề, không có nội dung)
            currentChapter = cur.currentChapter?.takeIf { sameChapter } ?: chapter,
            chapters = if (initialChapters.isNotEmpty()) initialChapters else next.chapters,
            isTtsActive = if (autoStartTts) true else next.isTtsActive,
            isBilingualMode = if (bilingual) true else next.isBilingualMode,
            lastChapterNumber = ChapterNavigation.lastKnown(
                next.lastChapterNumber, novel.totalChapters, initialChapters.maxOfOrNull { it.chapterNumber } ?: 0
            ),
            isLoadingChapter = !sameChapter,
            chapterError = null
        )
    }

    fun next(autoStartTts: Boolean, finishedReading: Boolean = false) {
        val s = state
        val novel = s.novel ?: return
        val current = s.currentChapter ?: return
        if (s.isLoadingChapter) return
        val target = ChapterNavigation.nextTarget(current.chapterNumber, s.lastChapterNumber)
        if (target == null) {
            state = s.copy(showEndOfBook = true)
            return
        }
        // Chỉ khi người đọc thật sự tới cuối (thẻ hết chương / nghe hết) mới đánh dấu "đã đọc" VÀ tính một chương hoàn thành vào
        // thống kê: bấm Sau mà chưa đọc gì không được làm tăng số chương đã đọc hay giữ chuỗi ngày đọc.
        val finished = ChapterNavigation.hasFinishedReading(finishedReading, endReachedKey, s.chapterKey)
        if (finished) {
            scope.launch { chapterRepository.markChapterRead(novel.id, current.chapterNumber) }
        }
        loader.load(novel.id, target, autoStartTts, countCompletion = finished)
    }

    fun prev(autoStartTts: Boolean) {
        val s = state
        val novel = s.novel ?: return
        val current = s.currentChapter ?: return
        if (s.isLoadingChapter) return
        val target = ChapterNavigation.prevTarget(current.chapterNumber) ?: return
        loader.load(novel.id, target, autoStartTts)
    }

    /** [Đọc bản gốc]: người đọc tự chọn văn bản gốc cho chương này (không bao giờ tự động). */
    fun readOriginal() {
        val chapter = state.currentChapter ?: return
        val paragraphs = chapter.originalContent?.let(::splitReaderParagraphs).orEmpty()
        if (paragraphs.isEmpty()) {
            state = state.copy(toastMessage = "Chương này chưa có bản gốc để đọc.")
            return
        }
        loader.cancelTranslation()
        upgrader.cancel()
        state = state.copy(
            showOriginal = true,
            paragraphs = paragraphs,
            isLoadingChapter = false,
            isTranslating = false,
            translatingChapterNumber = null,
            translationError = null,
            translationErrorKind = null,
            isProvisionalTranslation = false,
            chapterError = null,
            restoreScrollPosition = chapter.scrollPosition
        )
        hooks.onContentChanged(paragraphs)
    }

    /**
     * Nút "Xem Văn Bản Gốc" / "Xem Bản Dịch" ở thanh dưới. Chỉ đổi cờ khi có chữ để hiện (chương không có bản gốc thì
     * báo, không để nhãn nút lệch với chữ đang hiện); số đoạn đổi thì giữ đúng chỗ đang đọc theo tỉ lệ đã cuộn.
     */
    fun toggleOriginal(scrollFraction: Float) {
        val s = state
        val chapter = s.currentChapter ?: return
        val toOriginal = !s.showOriginal
        val source = if (toOriginal) chapter.originalContent else chapter.translatedContent
        val paragraphs = source?.let(::splitReaderParagraphs).orEmpty()
        if (paragraphs.isEmpty()) {
            state = s.copy(toastMessage = if (toOriginal) "Chương này chưa có bản gốc để đọc." else "Chương này chưa có bản dịch.")
            return
        }
        val keepPlace = ReaderScrollRestore.placeAfterReplace(s.paragraphs.size, paragraphs.size, scrollFraction)
        state = s.copy(
            showOriginal = toOriginal,
            paragraphs = paragraphs,
            // Quay lại bản dịch mà đó vẫn là bản tạm của Google thì banner "Dịch lại bằng AI" phải hiện lại
            isProvisionalTranslation = !toOriginal && ProvisionalTranslation.isProvisional(s.isProvisionalTranslation, chapter.translationEngine),
            restoreScrollPosition = if (keepPlace > 0f) keepPlace else s.restoreScrollPosition
        )
        hooks.onContentChanged(paragraphs)
    }
}
