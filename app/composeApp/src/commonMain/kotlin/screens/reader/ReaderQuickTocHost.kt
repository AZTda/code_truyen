package com.truyencloud.compose.screens.reader

import androidx.compose.runtime.Composable

/** Nền mờ + mục lục nhanh ở đáy màn đọc. Bấm ra ngoài để đóng; bấm trong bảng không làm đóng. */
@Composable
internal fun ReaderQuickTocHost(state: ReaderUiState, viewModel: ReaderViewModel) {
    if (!state.showQuickToc) return
    ReaderSheetBackdrop(onDismiss = viewModel::toggleQuickToc) {
        BottomSheetAnchor {
            QuickTocSheet(
                chapters = state.chapters,
                currentChapterNumber = state.currentChapter?.chapterNumber ?: 1,
                lastChapterNumber = state.lastChapterNumber,
                flags = state.tocFlags,
                serverTranslated = state.tocServerTranslated,
                onSelectChapter = viewModel::selectChapterFromToc,
                onDismiss = viewModel::toggleQuickToc
            )
        }
    }
}
