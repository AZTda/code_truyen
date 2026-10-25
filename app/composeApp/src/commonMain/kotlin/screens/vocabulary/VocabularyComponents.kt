package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.components.SegmentedControl
import com.truyencloud.compose.theme.Spacing

/**
 * Chọn chế độ học: 4 ô, nhãn ngắn (xem [VocabMode]). Khi người dùng phóng chữ lớn hơn ~1,1 thì bốn nhãn không còn vừa
 * một rãnh SegmentedControl 360dp (bị cắt "Luyện t..."), nên chuyển sang hàng chip cuộn ngang: vẫn chọn một, không cắt chữ.
 */
@Composable
internal fun VocabularyModeSwitcher(
    activeMode: VocabMode,
    onSelectMode: (VocabMode) -> Unit,
    modifier: Modifier = Modifier
) {
    if (LocalDensity.current.fontScale > SEGMENTED_MAX_FONT_SCALE) {
        ChipScrollRow(count = VocabMode.entries.size, selectedIndex = activeMode.ordinal, modifier = modifier) {
            items(VocabMode.entries) { mode ->
                FilterChip8(label = mode.title, selected = mode == activeMode, onClick = { onSelectMode(mode) })
            }
        }
    } else {
        SegmentedControl(
            items = VocabMode.entries,
            selected = activeMode,
            onSelect = onSelectMode,
            label = { it.title },
            modifier = modifier.padding(horizontal = Spacing.pageH)
        )
    }
}

private const val SEGMENTED_MAX_FONT_SCALE = 1.12f

/**
 * Hàng chip nhóm + bộ lọc của Sổ từ (chọn một): Tất cả, Theo truyện, Chủ đề rồi các bộ lọc. Số đếm đứng sau nhãn
 * khi đã biết (tất cả, cần ôn, đã thuộc, khó nhớ). Cuộn ngang, mỗi chip có vùng chạm cao 48dp.
 */
@Composable
internal fun FilterChipsRow(
    uiState: VocabularyUiState,
    onFilterSelect: (VocabFilter) -> Unit,
    onContextGroupModeSelect: (ContextGroupMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val allMode = uiState.contextGroupMode == ContextGroupMode.ALL
    val filters = VocabFilter.entries.filter { it != VocabFilter.ALL }
    // Thứ tự chip: Tất cả, Theo truyện, Chủ đề rồi các bộ lọc
    val selectedIndex = when (uiState.contextGroupMode) {
        ContextGroupMode.BY_BOOK -> 1
        ContextGroupMode.BY_DOMAIN -> 2
        ContextGroupMode.ALL -> if (uiState.selectedFilter == VocabFilter.ALL) 0 else 3 + filters.indexOf(uiState.selectedFilter)
    }
    ChipScrollRow(count = 3 + filters.size, selectedIndex = selectedIndex, modifier = modifier) {
        item {
            FilterChip8(
                label = ContextGroupMode.ALL.title,
                selected = allMode && uiState.selectedFilter == VocabFilter.ALL,
                trailingText = uiState.allWords.size.takeIf { it > 0 }?.toString(),
                onClick = {
                    onContextGroupModeSelect(ContextGroupMode.ALL)
                    onFilterSelect(VocabFilter.ALL)
                }
            )
        }
        item {
            FilterChip8(
                label = ContextGroupMode.BY_BOOK.title,
                selected = uiState.contextGroupMode == ContextGroupMode.BY_BOOK,
                onClick = { onContextGroupModeSelect(ContextGroupMode.BY_BOOK) }
            )
        }
        item {
            FilterChip8(
                label = ContextGroupMode.BY_DOMAIN.title,
                selected = uiState.contextGroupMode == ContextGroupMode.BY_DOMAIN,
                onClick = { onContextGroupModeSelect(ContextGroupMode.BY_DOMAIN) }
            )
        }
        items(filters) { filter ->
            val count = when (filter) {
                VocabFilter.DUE -> uiState.dueWords.size
                VocabFilter.MASTERED -> uiState.snapshot.matureWords
                VocabFilter.LEECH -> uiState.snapshot.leechWords
                else -> 0
            }
            FilterChip8(
                label = filter.label,
                selected = allMode && uiState.selectedFilter == filter,
                trailingText = count.takeIf { it > 0 }?.toString(),
                onClick = {
                    onContextGroupModeSelect(ContextGroupMode.ALL)
                    onFilterSelect(filter)
                }
            )
        }
    }
}
