package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.truyencloud.compose.components.EmptyState
import com.truyencloud.compose.components.HairlineDivider
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.SavedWord

/**
 * Sổ từ. [showOverview] = false (khi đang tìm kiếm) bỏ thẻ "Hôm nay", hàng chip và luôn hiện danh sách phẳng.
 */
@Composable
internal fun NotebookView(
    uiState: VocabularyUiState,
    onFilterSelect: (VocabFilter) -> Unit,
    onContextGroupModeSelect: (ContextGroupMode) -> Unit,
    onSelectBook: (String) -> Unit,
    onSelectDomain: (String) -> Unit,
    onWeaveStoryForBook: (String) -> Unit,
    onWeaveStoryForDomain: (String) -> Unit,
    onSpeak: (String) -> Unit,
    onSelectForRealLife: (SavedWord) -> Unit,
    onGenerateArt: (SavedWord) -> Unit,
    onLoadDetails: (SavedWord) -> Unit = {},
    onToggleSuspend: (SavedWord) -> Unit = {},
    onDelete: (SavedWord) -> Unit,
    onOpenStudyGoal: () -> Unit = {},
    onOpenAddWord: () -> Unit = {},
    bottomPadding: Dp = Spacing.lg,
    showOverview: Boolean = true
) {
    // Giữ id thay vì cả thẻ để hình minh họa vừa vẽ xong hiện ngay trong tờ đang mở
    var openWordId by remember { mutableStateOf<String?>(null) }
    val openWord = openWordId?.let { id -> uiState.allWords.firstOrNull { it.id == id } }
    val chips: @Composable () -> Unit = {
        FilterChipsRow(
            uiState = uiState,
            onFilterSelect = onFilterSelect,
            onContextGroupModeSelect = onContextGroupModeSelect
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (if (showOverview) uiState.contextGroupMode else ContextGroupMode.ALL) {
            ContextGroupMode.BY_BOOK -> NovelContextCollectionsView(
                booksMap = uiState.booksMap,
                onSelectBook = onSelectBook,
                onWeaveStoryForBook = onWeaveStoryForBook,
                onSpeak = onSpeak,
                headerContent = chips,
                bottomPadding = bottomPadding
            )
            ContextGroupMode.BY_DOMAIN -> SemanticDomainClustersView(
                domainsMap = uiState.domainsMap,
                onSelectDomain = onSelectDomain,
                onWeaveStoryForDomain = onWeaveStoryForDomain,
                onSpeak = onSpeak,
                headerContent = chips,
                bottomPadding = bottomPadding
            )
            ContextGroupMode.ALL -> WordList(
                uiState = uiState,
                showOverview = showOverview,
                chips = chips,
                bottomPadding = bottomPadding,
                onOpenStudyGoal = onOpenStudyGoal,
                onOpenAddWord = onOpenAddWord,
                onOpenWord = { openWordId = it.id }
            )
        }
    }

    if (openWord != null) {
        LaunchedEffect(openWord.id) {
            onLoadDetails(openWord)
        }
        WordDetailSheet(
            word = openWord,
            cachedArt = uiState.cardArtCache[openWord.word],
            isGeneratingArt = uiState.generatingArtWords.contains(openWord.word),
            onSpeak = { onSpeak(openWord.word) },
            onRealLife = {
                openWordId = null
                onSelectForRealLife(openWord)
            },
            onGenerateArt = { onGenerateArt(openWord) },
            onToggleSuspend = { onToggleSuspend(openWord) },
            onDelete = { onDelete(openWord) },
            onDismiss = { openWordId = null }
        )
    }
}

@Composable
private fun WordList(
    uiState: VocabularyUiState,
    showOverview: Boolean,
    chips: @Composable () -> Unit,
    bottomPadding: Dp,
    onOpenStudyGoal: () -> Unit,
    onOpenAddWord: () -> Unit,
    onOpenWord: (SavedWord) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding)
    ) {
        if (showOverview && uiState.allWords.isNotEmpty()) {
            item(key = "today") {
                TodaySummaryCard(
                    uiState = uiState,
                    modifier = Modifier.padding(horizontal = Spacing.pageH, vertical = Spacing.xs),
                    onOpenStudyGoal = onOpenStudyGoal,
                    onOpenAddWord = onOpenAddWord
                )
            }
            item(key = "filters") { chips() }
        }

        if (uiState.filteredWords.isEmpty()) {
            item(key = "empty") {
                val searching = uiState.searchQuery.isNotBlank()
                val noWordsAtAll = uiState.allWords.isEmpty()
                EmptyState(
                    message = when {
                        searching -> "Không tìm thấy từ vựng nào"
                        noWordsAtAll -> "Sổ từ đang trống"
                        else -> "Chưa có từ vựng nào trong danh mục này"
                    },
                    subMessage = when {
                        searching -> "Thử tìm với từ khóa khác."
                        noWordsAtAll -> "Chạm vào từ tiếng Anh khi đọc truyện hoặc tự thêm từ mới vào đây."
                        else -> "Chọn một nhóm khác ở hàng trên."
                    },
                    icon = TruyenCloudIcons.School,
                    actionButton = if (noWordsAtAll && !searching) {
                        {
                            com.truyencloud.compose.components.PrimaryButton(
                                text = "Thêm từ vựng mới",
                                onClick = onOpenAddWord
                            )
                        }
                    } else null
                )
            }
        } else {
            itemsIndexed(uiState.filteredWords, key = { _, word -> word.id }) { index, word ->
                Column {
                    if (index > 0) HairlineDivider(startInset = Spacing.pageH)
                    WordRow(word = word, onClick = { onOpenWord(word) })
                }
            }
        }
    }
}
