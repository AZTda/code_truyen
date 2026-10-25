package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.truyencloud.compose.components.EmptyState
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.SavedWord

/** Từ vựng nhóm theo từng bộ truyện đã đọc. */
@Composable
fun NovelContextCollectionsView(
    booksMap: Map<String, List<SavedWord>>,
    onSelectBook: (String) -> Unit,
    onWeaveStoryForBook: (String) -> Unit,
    onSpeak: (String) -> Unit,
    headerContent: (@Composable () -> Unit)? = null,
    bottomPadding: Dp = Spacing.lg
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        if (headerContent != null) {
            item { headerContent() }
        }

        if (booksMap.isEmpty()) {
            item {
                EmptyState(
                    message = "Chưa có từ vựng theo bộ truyện",
                    subMessage = "Khi bạn đọc tiểu thuyết và tra cứu, từ vựng sẽ tự động nhóm theo từng tác phẩm.",
                    icon = TruyenCloudIcons.NovelCollection
                )
            }
        } else {
            items(booksMap.entries.toList(), key = { it.key }) { (bookTitle, words) ->
                CollectionCard(
                    title = bookTitle,
                    subtitle = "${words.size} từ vựng đã lưu",
                    words = words,
                    onSpeak = onSpeak,
                    primaryLabel = "Ôn thẻ",
                    onPrimary = { onSelectBook(bookTitle) },
                    onWeave = { onWeaveStoryForBook(bookTitle) },
                    serifTitle = true,
                    modifier = Modifier.padding(horizontal = Spacing.pageH)
                )
            }
        }
    }
}
