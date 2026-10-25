package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.SegmentedControl
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.shared.data.Bookmark
import com.truyencloud.shared.reader.BookmarkGroups
import com.truyencloud.shared.reader.BookmarkScope

/**
 * Màu đánh dấu người đọc chọn cho một dấu trang: mã hex được LƯU cùng dấu trang trong cơ sở dữ liệu (dữ liệu người dùng, không phải màu
 * giao diện) nên giữ nguyên bốn giá trị. Chữ trên giao diện luôn dùng màu chữ của chủ đề; màu đánh dấu chỉ tô thanh, ô chọn và biểu tượng.
 */
val BookmarkColors = listOf(
    Pair("#FFD54F", "Vàng"),
    Pair("#4DD0E1", "Xanh lam"),
    Pair("#F06292", "Hồng"),
    Pair("#81C784", "Xanh lục")
)

private fun hexToColor(hex: String): Color {
    val clean = hex.removePrefix("#")
    val value = clean.toLong(16)
    return Color(if (clean.length == 6) value or 0xFF000000 else value)
}

/** Đổi mã hex đã lưu thành màu; mã hỏng thì dùng màu đầu tiên của bảng. */
fun parseHexColor(hex: String): Color =
    try {
        hexToColor(hex)
    } catch (_: Exception) {
        hexToColor(BookmarkColors.first().first)
    }

/**
 * Dấu trang & ghi chú. [bookmarks] là danh sách đã lọc theo [scope] ("Chương này" hoặc "Cả truyện" — nhóm theo chương, bấm một
 * dấu trang ở chương khác sẽ tải chương đó rồi nhảy tới). [undoMessage] khác null thì hiện thanh hoàn tác ngay trong bảng.
 */
@Composable
fun BookmarksSheet(
    bookmarks: List<Bookmark>,
    scope: BookmarkScope,
    currentChapterNumber: Int,
    onScopeChange: (BookmarkScope) -> Unit,
    onJumpToBookmark: (Bookmark) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onExport: () -> Unit = {},
    undoMessage: String? = null,
    onUndo: () -> Unit = {}
) {
    val groups = remember(bookmarks) { BookmarkGroups.group(bookmarks) }

    ReaderSheet(
        onDismiss = onDismiss,
        title = "Dấu trang và ghi chú",
        subtitle = BookmarkGroups.summary(scope, groups, currentChapterNumber),
        modifier = modifier,
        headerActions = {
            if (bookmarks.isNotEmpty()) {
                QuietIconButton(TruyenCloudIcons.Export, "Xuất dấu trang", onExport)
            }
        }
    ) {
        SegmentedControl(
            options = listOf("Chương này", "Cả truyện"),
            selectedIndex = if (scope == BookmarkScope.NOVEL) 1 else 0,
            onSelect = { onScopeChange(if (it == 1) BookmarkScope.NOVEL else BookmarkScope.CHAPTER) },
            modifier = Modifier.padding(horizontal = ReaderSheetInset)
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (bookmarks.isEmpty()) {
            BookmarksEmptyState(scope)
        } else {
            BookmarkList(
                groups = groups,
                scope = scope,
                currentChapterNumber = currentChapterNumber,
                onJump = onJumpToBookmark,
                onDelete = onDeleteBookmark
            )
        }

        if (undoMessage != null) {
            BookmarkUndoSnackbar(
                message = undoMessage,
                onUndo = onUndo,
                modifier = Modifier.fillMaxWidth().padding(horizontal = ReaderSheetInset, vertical = 8.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
