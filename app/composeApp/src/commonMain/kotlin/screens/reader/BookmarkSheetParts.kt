package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.EmptyState
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.data.Bookmark
import com.truyencloud.shared.reader.BookmarkGroup
import com.truyencloud.shared.reader.BookmarkScope

@Composable
internal fun BookmarkList(
    groups: List<BookmarkGroup>,
    scope: BookmarkScope,
    currentChapterNumber: Int,
    onJump: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit
) {
    val colors = LocalCustomColors.current
    val listState = rememberLazyListState()

    // Xem cả truyện: mở sẵn ở nhóm của chương đang đọc (đếm các mục — tiêu đề nhóm + dấu trang — đứng trước nó)
    LaunchedEffect(scope, groups.size) {
        if (scope == BookmarkScope.NOVEL) {
            val before = groups.takeWhile { it.chapterNumber < currentChapterNumber }.sumOf { 1 + it.bookmarks.size }
            val total = groups.sumOf { 1 + it.bookmarks.size }
            if (before in 1 until total) listState.scrollToItem(before)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
        contentPadding = PaddingValues(horizontal = ReaderSheetInset),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        groups.forEach { group ->
            if (scope == BookmarkScope.NOVEL) {
                item(key = "chapter-${group.chapterNumber}") {
                    val isCurrent = group.chapterNumber == currentChapterNumber
                    Text(
                        text = if (isCurrent) "${group.label} · đang đọc" else group.label,
                        style = AppText.secondaryStrong,
                        color = if (isCurrent) colors.accent else colors.textSecondary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
            items(group.bookmarks, key = { it.id }) { bm ->
                BookmarkListItem(bookmark = bm, onJump = { onJump(bm) }, onDelete = { onDelete(bm) })
            }
        }
    }
}

@Composable
internal fun BookmarksEmptyState(scope: BookmarkScope) {
    EmptyState(
        message = if (scope == BookmarkScope.CHAPTER) "Chưa có đoạn nào được đánh dấu" else "Truyện này chưa có dấu trang nào",
        subMessage = "Chạm giữ đoạn văn trong lúc đọc để tạo dấu trang và ghi chú.",
        icon = TruyenCloudIcons.BookmarkBorder
    )
}
