package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.data.Bookmark
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.reader.ReadingProgressPolicy
import com.truyencloud.shared.reader.ScrollReport
import com.truyencloud.shared.reader.ScrollSaveTracker
import com.truyencloud.shared.repo.BookmarkRepository
import com.truyencloud.shared.repo.ReaderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class ReaderAnnotationCoordinator(
    private val bookmarkRepository: BookmarkRepository?,
    private val readerRepository: ReaderRepository,
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope
) {
    private var scrollSaveJob: Job? = null
    private var brightnessHudJob: Job? = null

    // Vị trí cuộn gắn với CHƯƠNG đã cuộn (không phải một con số trần): sang chương mới mà chưa cuộn thì lúc thoát phải lưu đúng vị trí
    // đã khôi phục của chương mới chứ không phải 0.95 của chương TRƯỚC, và vị trí chưa lưu của chương trước được lưu ngay khi rời nó.
    private val scrollTracker = ScrollSaveTracker()

    // Bộ thu dấu trang: MỘT cho cả truyện, không phải một bộ cho mỗi lần mở chương.
    private var bookmarksJob: Job? = null
    private var bookmarksNovelId: String? = null

    fun onScrollPositionChanged(position: Float) {
        val novel = uiStateFlow.value.novel ?: return
        val chapter = uiStateFlow.value.currentChapter ?: return
        // Lần báo đầu tiên của chương mới trong lúc chương trước còn một lần lưu đang chờ: lưu NGAY vị trí cuối của chương trước.
        // (Bản cũ chỉ có một lịch lưu nên huỷ nó ở đây và vị trí cuối của chương vừa rời mất hẳn.)
        scrollTracker.report(ScrollReport(novel, chapter.chapterNumber, position))?.let { left ->
            scope.launch { saveProgress(left) }
        }
        scrollSaveJob?.cancel()
        scrollSaveJob = scope.launch {
            delay(3000L)
            // Lưu đúng chương đã cuộn (không đọc lại state sau 3 giây — người dùng có thể đã sang chương khác).
            scrollTracker.takeUnsaved()?.let { saveProgress(it) }
        }
    }

    fun flushReadingProgressImmediately() {
        scrollSaveJob?.cancel()
        // Vị trí chương vừa rời mà chưa kịp lưu (Quay lại ngay sau khi bấm Sau)
        val pending = scrollTracker.takeUnsaved()
        val state = uiStateFlow.value
        val novel = state.novel
        val chapter = state.currentChapter
        // Chương đang mở CHỈ được lưu khi nội dung thật đã hiện. Trước đó currentChapter có thể là bản giữ chỗ do Home dựng khi bấm
        // "Đọc tiếp" (scrollPosition = 0, chương chưa tải máy này): lưu nó sẽ ghi 0 đè lên vị trí đã lưu — rồi đẩy 0 lên đám mây.
        val current = if (novel != null && chapter != null) {
            // Chưa cuộn lần nào ở chương này thì lưu lại đúng vị trí đã khôi phục của chương.
            ReadingProgressPolicy.positionOnLeave(
                contentShown = state.paragraphs.isNotEmpty(),
                reported = scrollTracker.positionFor(novel.id, chapter.chapterNumber),
                restored = chapter.scrollPosition
            )?.let { ScrollReport(novel, chapter.chapterNumber, it) }
        } else null
        if (pending == null && current == null) return
        scope.launch {
            // Một coroutine, đúng thứ tự: chương vừa rời trước, chương đang mở sau (lần lưu sau cùng quyết định chỗ "Đọc tiếp")
            pending?.let { saveProgress(it) }
            if (current != null && pending?.isSameChapterAs(current) != true) saveProgress(current)
        }
    }

    private suspend fun saveProgress(report: ScrollReport) = saveProgress(report.novel, report.chapterNumber, report.position)

    /**
     * Lưu tiến độ. Truyện chưa có trong tủ thì lần lưu đầu tiên tạo hàng (xem ReaderRepository), nên
     * luôn truyền Novel của trình đọc.
     */
    private suspend fun saveProgress(novel: Novel, chapterNumber: Int, position: Float) {
        try {
            readerRepository.saveReadingProgress(novel, novel.id, chapterNumber, position)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Lỗi ghi đĩa không được làm sập trình đọc; lần lưu sau sẽ ghi lại.
        }
    }

    fun onScrollRestored() {
        uiStateFlow.value = uiStateFlow.value.copy(restoreScrollPosition = 0f)
    }

    fun adjustBrightnessDelta(delta: Float) {
        val current = uiStateFlow.value.readerBrightness
        val newBrightness = (current + delta).coerceIn(0.15f, 1.0f)
        val percent = (newBrightness * 100).toInt()
        uiStateFlow.value = uiStateFlow.value.copy(
            readerBrightness = newBrightness,
            showBrightnessHud = true,
            brightnessHudPercent = percent
        )
        brightnessHudJob?.cancel()
        brightnessHudJob = scope.launch {
            delay(1500L)
            uiStateFlow.value = uiStateFlow.value.copy(showBrightnessHud = false)
        }
    }

    fun setReaderBrightness(brightness: Float) {
        val clamped = brightness.coerceIn(0.15f, 1.0f)
        uiStateFlow.value = uiStateFlow.value.copy(
            readerBrightness = clamped,
            showBrightnessHud = true,
            brightnessHudPercent = (clamped * 100).toInt()
        )
        brightnessHudJob?.cancel()
        brightnessHudJob = scope.launch {
            delay(1500L)
            uiStateFlow.value = uiStateFlow.value.copy(showBrightnessHud = false)
        }
    }

    fun toggleAutoScroll() {
        val next = !uiStateFlow.value.isAutoScrolling
        uiStateFlow.value = uiStateFlow.value.copy(
            isAutoScrolling = next,
            isAutoScrollPaused = false,
            showBars = false
        )
    }

    fun setAutoScrollPaused(paused: Boolean) {
        uiStateFlow.value = uiStateFlow.value.copy(isAutoScrollPaused = paused)
    }

    fun cycleAutoScrollSpeed() {
        val nextSpeed = when (uiStateFlow.value.autoScrollSpeed) {
            1.0f -> 1.5f
            1.5f -> 2.0f
            2.0f -> 3.0f
            3.0f -> 0.5f
            else -> 1.0f
        }
        uiStateFlow.value = uiStateFlow.value.copy(autoScrollSpeed = nextSpeed)
    }

    fun stopAutoScroll() {
        uiStateFlow.value = uiStateFlow.value.copy(isAutoScrolling = false, isAutoScrollPaused = false)
    }

    /**
     * Theo dõi dấu trang của CẢ truyện: [ReaderUiState.bookmarks] là danh sách cả cuốn (xếp theo chương
     * rồi đoạn) còn `chapterBookmarks` tự lọc theo chương đang mở — nên đổi chương không phải nạp lại
     * và không thể còn bộ thu cũ nào ghi đè kết quả (bản cũ mở thêm một bộ thu không bao giờ huỷ ở
     * MỖI lần nạp chương, làm highlight của chương hiện tại biến mất thất thường).
     */
    @Suppress("UNUSED_PARAMETER")
    fun loadBookmarks(novelId: String, chapterNumber: Int) {
        val repo = bookmarkRepository ?: return
        if (bookmarksNovelId != novelId || bookmarksJob?.isActive != true) {
            bookmarksJob?.cancel()
            if (bookmarksNovelId != novelId) {
                // Đổi sang truyện khác: bỏ dấu trang truyện cũ ngay (chapterBookmarks chỉ lọc theo số chương).
                uiStateFlow.value = uiStateFlow.value.copy(bookmarks = emptyList())
            }
            bookmarksNovelId = novelId
            bookmarksJob = scope.launch {
                repo.getBookmarksForNovel(novelId)
                    .catch { it.printStackTrace() }
                    .collect { bms -> uiStateFlow.value = uiStateFlow.value.copy(bookmarks = bms) }
            }
        }
        // Đã giãn cách ~30 giây bên trong kho, nên mở chương liên tục cũng không đồng bộ liên tục.
        scope.launch {
            try { repo.syncWithServer() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
    }

    fun openBookmarksSheet() { uiStateFlow.value = uiStateFlow.value.copy(showBookmarksSheet = true) }
    fun closeBookmarksSheet() { uiStateFlow.value = uiStateFlow.value.copy(showBookmarksSheet = false) }

    fun openAddBookmarkDialog(paragraphIndex: Int, text: String, existingBookmark: Bookmark? = null) {
        uiStateFlow.value = uiStateFlow.value.copy(
            showAddBookmarkDialog = true,
            bookmarkTargetParagraphIndex = paragraphIndex,
            bookmarkTargetText = text,
            activeBookmarkForEdit = existingBookmark
        )
    }

    fun closeAddBookmarkDialog() {
        uiStateFlow.value = uiStateFlow.value.copy(
            showAddBookmarkDialog = false,
            bookmarkTargetParagraphIndex = -1,
            bookmarkTargetText = "",
            activeBookmarkForEdit = null
        )
    }

    fun saveBookmark(colorHex: String, note: String) {
        val repo = bookmarkRepository ?: return
        val current = uiStateFlow.value
        val existing = current.activeBookmarkForEdit
        val novel = current.novel ?: return
        val ch = current.currentChapter ?: return

        scope.launch {
            if (existing != null) {
                repo.updateBookmark(existing.copy(colorHex = colorHex, note = note))
            } else {
                repo.saveBookmark(
                    novelId = novel.id,
                    chapterNumber = ch.chapterNumber,
                    chapterTitle = ch.titleVi.ifBlank { ch.title },
                    paragraphIndex = current.bookmarkTargetParagraphIndex,
                    selectedText = current.bookmarkTargetText,
                    note = note,
                    colorHex = colorHex
                )
            }
            // Kho tự hẹn một lượt đồng bộ gộp (không đồng bộ ngay ở mỗi lần lưu).
            closeAddBookmarkDialog()
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        val repo = bookmarkRepository ?: return
        scope.launch {
            repo.deleteBookmark(bookmark)
            if (uiStateFlow.value.activeBookmarkForEdit?.id == bookmark.id) {
                closeAddBookmarkDialog()
            }
        }
    }

    fun openQuoteCard(text: String) {
        if (text.isNotBlank()) {
            uiStateFlow.value = uiStateFlow.value.copy(showQuoteCard = true, selectedQuoteText = text)
        }
    }

    fun closeQuoteCard() { uiStateFlow.value = uiStateFlow.value.copy(showQuoteCard = false) }
}
