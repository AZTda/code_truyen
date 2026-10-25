package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.data.Bookmark
import com.truyencloud.shared.export.BookmarkExporter
import com.truyencloud.shared.export.PlatformShareHandler
import com.truyencloud.shared.reader.BookmarkGroups
import com.truyencloud.shared.reader.BookmarkJump
import com.truyencloud.shared.reader.BookmarkScope
import com.truyencloud.shared.reader.JumpView
import com.truyencloud.shared.reader.PendingJump
import com.truyencloud.shared.reader.ReaderScrollRestore
import com.truyencloud.shared.repo.BookmarkRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Dấu trang cả truyện: chọn phạm vi danh sách, nhảy tới dấu trang (kể cả ở chương khác), xoá có hoàn tác và xuất.
 *
 * Bản cũ chỉ có dấu trang của chương hiện tại, nên nhánh "sang chương khác" không bao giờ chạy; và lệnh nhảy được gọi ngay sau
 * khi yêu cầu tải chương — trước khi đoạn văn của chương mới tồn tại — nên bị bỏ hoặc bị lần khôi phục vị trí đã lưu ghi đè.
 */
internal class ReaderBookmarkCoordinator(
    private val loader: ReaderChapterLoader,
    private val annotation: ReaderAnnotationCoordinator,
    private val bookmarkRepository: BookmarkRepository?,
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope
) {
    private var jumpJob: Job? = null
    private var undoJob: Job? = null

    /** Mỗi lần nhảy một số mới: lần nhảy cũ bị thay thế không được xoá chỗ cần nhảy của lần nhảy mới. */
    private var jumpEpoch = 0

    fun setScope(scope: BookmarkScope) {
        uiStateFlow.value = uiStateFlow.value.copy(bookmarkScope = scope)
    }

    // ── Nhảy tới dấu trang ──────────────────────────────────────────────────

    /**
     * Cùng chương và đã có chữ thì nhảy ngay; khác chương thì tải chương, ghi nhớ chỗ cần nhảy trong
     * [ReaderUiState.pendingJump] và chỉ áp dụng khi đoạn văn của chương đích đã được công bố ([BookmarkJump]).
     */
    fun jumpTo(chapterNumber: Int, paragraphIndex: Int) {
        val s = uiStateFlow.value
        val novel = s.novel ?: return
        jumpJob?.cancel()
        val epoch = ++jumpEpoch
        if (BookmarkJump.canJumpNow(chapterNumber, s.currentChapter?.chapterNumber, s.paragraphs.size)) {
            // Đang tải một chương khác (người đọc vừa bấm Sau / Mục lục): bỏ lượt tải đó, chương này đã có chữ để nhảy luôn
            if (s.isLoadingChapter) loader.cancelLoad()
            uiStateFlow.value = uiStateFlow.value.copy(
                pendingJump = null,
                restoreScrollPosition = ReaderScrollRestore.positionForParagraph(paragraphIndex, s.displayParagraphs.size),
                scrollToParagraphIndex = paragraphIndex
            )
            return
        }
        val jump = PendingJump(novel.id, chapterNumber, paragraphIndex)
        uiStateFlow.value = s.copy(pendingJump = jump)
        loader.load(novel.id, chapterNumber, autoStartTts = s.isTtsActive)
        jumpJob = scope.launch {
            try {
                // Chương đích có thể phải dịch cả phút; quá lâu mà vẫn chưa xong thì thôi, đừng để chỗ nhảy nằm lại mãi
                withTimeoutOrNull(JUMP_TIMEOUT_MS) { uiStateFlow.first { settle(jump) } }
            } finally {
                if (epoch == jumpEpoch && uiStateFlow.value.pendingJump != null) {
                    uiStateFlow.value = uiStateFlow.value.copy(pendingJump = null)
                }
            }
        }
    }

    /**
     * Bộ tải chương gọi NGAY sau khi công bố chương (xem [BookmarkJump.onChapterReady]): đây là đường chính áp dụng lần nhảy. Đặt
     * vị trí nhảy ngay trong lượt công bố thì danh sách chỉ thấy MỘT giá trị khôi phục (không nháy qua vị trí đã lưu) và TTS đang bật
     * bắt đầu đúng đoạn đó. Chương tải hỏng / dịch hỏng thì bỏ lần nhảy luôn, không để nó nằm lại cho một chương khác.
     */
    fun onChapterReady(novelId: String, number: Int, hasParagraphs: Boolean) {
        val s = uiStateFlow.value
        val jump = s.pendingJump ?: return
        val count = if (hasParagraphs) s.displayParagraphs.size else 0
        when (val step = BookmarkJump.onChapterReady(jump, novelId, number, count)) {
            null -> Unit
            BookmarkJump.Step.Wait -> Unit
            BookmarkJump.Step.Drop -> uiStateFlow.value = s.copy(pendingJump = null)
            is BookmarkJump.Step.Apply -> uiStateFlow.value = s.copy(
                pendingJump = null,
                restoreScrollPosition = step.position,
                scrollToParagraphIndex = jump.paragraphIndex
            )
        }
    }

    /**
     * Đường dự phòng nếu lượt công bố chương không đi qua [onChapterReady]: đợi tới khi đoạn văn của chương đích đã có và vị trí
     * đã lưu của chương đã được danh sách áp dụng xong, rồi mới đặt vị trí nhảy. true = đã áp dụng hoặc bỏ hẳn lần nhảy này.
     */
    private fun settle(jump: PendingJump): Boolean {
        val s = uiStateFlow.value
        if (s.pendingJump != jump) return true // đã được xử lý ở onChapterReady (hoặc bị thay bằng lần nhảy khác)
        val shown = s.currentChapter?.chapterNumber
        val view = JumpView(
            novelId = s.novel?.id,
            shownChapter = shown,
            paragraphCount = s.displayParagraphs.size,
            isLoadingChapter = s.isLoadingChapter,
            loadingTarget = s.targetChapterNumber,
            isTranslatingShown = s.isTranslating && (s.translatingChapterNumber == null || s.translatingChapterNumber == shown),
            restorePending = s.restoreScrollPosition > 0f
        )
        return when (val step = BookmarkJump.step(jump, view)) {
            BookmarkJump.Step.Wait -> false
            BookmarkJump.Step.Drop -> {
                uiStateFlow.value = s.copy(pendingJump = null)
                true
            }
            is BookmarkJump.Step.Apply -> {
                uiStateFlow.value = s.copy(
                    pendingJump = null,
                    restoreScrollPosition = step.position,
                    scrollToParagraphIndex = jump.paragraphIndex
                )
                true
            }
        }
    }

    // ── Xoá có hoàn tác ─────────────────────────────────────────────────────

    /** Xoá mềm (kho gửi bia mộ đồng bộ) và mở thanh "Đã xoá dấu trang — Hoàn tác" trong vài giây. */
    fun delete(bookmark: Bookmark) {
        annotation.deleteBookmark(bookmark)
        undoJob?.cancel()
        uiStateFlow.value = uiStateFlow.value.copy(deletedBookmark = bookmark)
        undoJob = scope.launch {
            delay(UNDO_WINDOW_MS)
            if (uiStateFlow.value.deletedBookmark?.id == bookmark.id) dismissUndo()
        }
    }

    /** Khôi phục dấu trang vừa xoá: ghi lại đúng dòng đó (cùng id) với giờ sửa mới hơn bia mộ nên cũng thắng khi đồng bộ. */
    fun undoDelete() {
        val bookmark = uiStateFlow.value.deletedBookmark ?: return
        undoJob?.cancel()
        uiStateFlow.value = uiStateFlow.value.copy(deletedBookmark = null)
        val repo = bookmarkRepository ?: return
        scope.launch {
            try {
                repo.updateBookmark(bookmark.copy(deletedAt = 0L))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Không khôi phục được thì dấu trang vẫn ở trạng thái đã xoá — không có gì khác để làm
            }
        }
    }

    fun dismissUndo() {
        undoJob?.cancel()
        uiStateFlow.value = uiStateFlow.value.copy(deletedBookmark = null)
    }

    // ── Xuất ────────────────────────────────────────────────────────────────

    /** Xuất danh sách đang hiển thị: một chương, hoặc cả truyện nhóm theo chương. */
    fun export() {
        val s = uiStateFlow.value
        val chapter = s.currentChapter ?: return
        val shown = BookmarkGroups.visible(s.bookmarks, s.bookmarkScope, chapter.chapterNumber)
        if (shown.isEmpty()) return
        val novel = s.novel
        val novelTitle = novel?.titleVi?.ifBlank { novel.title } ?: "Truyện"
        val (fileName, content) = if (s.bookmarkScope == BookmarkScope.CHAPTER) {
            BookmarkExporter.exportMarkdown(shown, novelTitle, chapter.titleVi.ifBlank { chapter.title })
        } else {
            BookmarkGroups.exportMarkdown(novelTitle, BookmarkGroups.group(shown))
        }
        PlatformShareHandler.shareTextFile(fileName, content, "text/markdown")
    }

    private companion object {
        const val UNDO_WINDOW_MS = 6_000L
        const val JUMP_TIMEOUT_MS = 20 * 60_000L
    }
}
