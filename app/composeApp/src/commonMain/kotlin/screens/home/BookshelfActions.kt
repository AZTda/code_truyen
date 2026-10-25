package com.truyencloud.compose.screens.home

import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.library.LibrarySort

/** Các thao tác trên tập truyện đang chọn (thanh chọn nhiều ở đầu Tủ sách). */
class SelectionActions(
    val onClear: () -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onFavorite: () -> Unit = {},
    val onMoveShelf: () -> Unit = {},
    val onDelete: () -> Unit = {}
)

/** Mọi hành động của Tủ sách gom một chỗ để hàm vẽ không phải nhận hàng chục tham số. */
class BookshelfActions(
    val onSelectShelf: (String) -> Unit,
    val onSelectSort: (LibrarySort) -> Unit,
    val onToggleFavorites: () -> Unit,
    val onToggleOvernightOnly: () -> Unit = {},
    val onToggleAiTranslatedOnly: () -> Unit = {},
    val onOpenNovel: (Novel) -> Unit,
    val onContinueReading: ((Novel) -> Unit)?,
    val onLongPress: (Novel) -> Unit,
    val onToggleSelection: (Novel) -> Unit,
    val onExplore: () -> Unit,
    val onImport: () -> Unit,
    val onRetry: () -> Unit,
    val onRefresh: () -> Unit,
    val onDismissMessage: () -> Unit,
    val onSpeakWord: (String) -> Unit,
    val onOpenVocab: () -> Unit,
    val onSearch: () -> Unit = {},
    val onDismissImportMessage: () -> Unit = {},
    val selection: SelectionActions = SelectionActions()
)
