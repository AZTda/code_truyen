package com.truyencloud.compose.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.EmptyState
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.library.LibraryEmptyCopy
import com.truyencloud.shared.library.LibraryShelves

/**
 * Trạng thái trống có hướng dẫn cho từng kệ: tủ hoàn toàn trống ("Khám phá truyện" + "Nạp tệp sách"), kệ trống, hoặc bộ
 * lọc yêu thích không khớp truyện nào ("Bỏ lọc yêu thích"). Kệ "Hoàn thành" trống chỉ nói cách chuyển truyện sang, không có nút.
 */
@Composable
fun LibraryEmptyState(
    shelf: String,
    libraryIsEmpty: Boolean,
    favoritesOnly: Boolean,
    onExplore: () -> Unit,
    onImport: () -> Unit,
    onClearFavoritesFilter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val copy = LibraryEmptyCopy.of(shelf, libraryIsEmpty, favoritesOnly)
    val filterOnly = favoritesOnly && !libraryIsEmpty
    val hasActions = filterOnly || libraryIsEmpty || shelf != LibraryShelves.COMPLETED

    EmptyState(
        message = copy.title,
        subMessage = copy.hint,
        icon = if (filterOnly) TruyenCloudIcons.Heart else TruyenCloudIcons.Bookshelf,
        actionButton = if (hasActions) {
            {
                Column(
                    modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    when {
                        filterOnly -> PrimaryButton("Bỏ lọc yêu thích", onClearFavoritesFilter, Modifier.fillMaxWidth())
                        libraryIsEmpty -> {
                            PrimaryButton("Khám phá truyện", onExplore, Modifier.fillMaxWidth())
                            TonalButton("Nạp tệp sách", onImport, Modifier.fillMaxWidth(), leadingIcon = TruyenCloudIcons.Import)
                        }
                        else -> PrimaryButton("Khám phá truyện", onExplore, Modifier.fillMaxWidth())
                    }
                }
            }
        } else null,
        modifier = modifier
    )
}
