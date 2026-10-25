package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.shared.reader.BookmarkGroups

/**
 * Bảng dấu trang (chương này / cả truyện), hộp thoại tạo - sửa dấu trang và thanh hoàn tác sau khi xoá.
 * Tách khỏi [ReaderModals] để file đó không phình ra.
 */
@Composable
internal fun ReaderBookmarkModals(viewModel: ReaderViewModel, uiState: ReaderUiState) {
    val coordinator = viewModel.bookmarkCoordinator
    val chapterNumber = uiState.currentChapter?.chapterNumber ?: 1
    val undoMessage = uiState.deletedBookmark?.let { "Đã xóa dấu trang ở đoạn #${it.paragraphIndex + 1}" }

    if (uiState.showBookmarksSheet) {
        ReaderSheetBackdrop(onDismiss = viewModel::closeBookmarksSheet) {
            BottomSheetAnchor {
                BookmarksSheet(
                    bookmarks = BookmarkGroups.visible(uiState.bookmarks, uiState.bookmarkScope, chapterNumber),
                    scope = uiState.bookmarkScope,
                    currentChapterNumber = chapterNumber,
                    onScopeChange = coordinator::setScope,
                    onJumpToBookmark = { bm ->
                        viewModel.closeBookmarksSheet()
                        // Cùng chương thì nhảy ngay; chương khác thì tải chương rồi mới nhảy (xem ReaderBookmarkCoordinator)
                        viewModel.jumpToBookmark(bm.chapterNumber, bm.paragraphIndex)
                    },
                    onDeleteBookmark = viewModel::deleteBookmark,
                    onExport = viewModel::exportCurrentBookmarks,
                    onDismiss = viewModel::closeBookmarksSheet,
                    undoMessage = undoMessage,
                    onUndo = coordinator::undoDelete
                )
            }
        }
    } else if (undoMessage != null) {
        // Xoá từ hộp thoại sửa dấu trang (bảng danh sách đang đóng): thanh hoàn tác nổi phía trên thanh công cụ dưới
        Box(modifier = Modifier.fillMaxSize()) {
            BookmarkUndoSnackbar(
                message = undoMessage,
                onUndo = coordinator::undoDelete,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 96.dp)
            )
        }
    }

    if (uiState.showAddBookmarkDialog) {
        val snippet = uiState.activeBookmarkSnippet
        AddBookmarkDialog(
            snippetText = snippet,
            existingBookmark = uiState.activeBookmarkForEdit,
            onSave = { colorHex, note -> viewModel.saveBookmark(colorHex, note) },
            onDelete = uiState.activeBookmarkForEdit?.let { bm -> { viewModel.deleteBookmark(bm) } },
            // "Chọn chữ" của màn đọc là chạm giữ đoạn văn: từ đây mở bảng tra cứu với đoạn đó để bôi đen và ghim một cái tên
            onExplainTerm = {
                viewModel.closeAddBookmarkDialog()
                viewModel.openLoreLookup(initialTerm = "", context = snippet)
            },
            onDismiss = viewModel::closeAddBookmarkDialog
        )
    }
}
