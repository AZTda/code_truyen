package com.truyencloud.compose.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.IosSheet
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.ChipShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.library.LibrarySort

/**
 * Tờ "Sắp xếp và lọc" mở từ nút sắp xếp ở thanh tiêu đề Tủ sách: chọn kiểu sắp xếp (được nhớ giữa các lần mở) và bật / tắt
 * chỉ hiện truyện yêu thích. Chọn một kiểu sắp xếp thì tờ tự đóng; công tắc yêu thích giữ tờ mở.
 */
@Composable
fun LibrarySortSheet(
    sort: LibrarySort,
    favoritesOnly: Boolean,
    favoriteCount: Int,
    overnightOnly: Boolean = false,
    overnightCount: Int = 0,
    aiTranslatedOnly: Boolean = false,
    aiTranslatedCount: Int = 0,
    onSelectSort: (LibrarySort) -> Unit,
    onToggleFavorites: () -> Unit,
    onToggleOvernightOnly: () -> Unit = {},
    onToggleAiTranslatedOnly: () -> Unit = {},
    onDismiss: () -> Unit
) {
    IosSheet(onDismissRequest = onDismiss, title = "Sắp xếp và lọc") {
        LibrarySortContent(
            sort = sort,
            favoritesOnly = favoritesOnly,
            favoriteCount = favoriteCount,
            overnightOnly = overnightOnly,
            overnightCount = overnightCount,
            aiTranslatedOnly = aiTranslatedOnly,
            aiTranslatedCount = aiTranslatedCount,
            onSelectSort = onSelectSort,
            onToggleFavorites = onToggleFavorites,
            onToggleOvernightOnly = onToggleOvernightOnly,
            onToggleAiTranslatedOnly = onToggleAiTranslatedOnly,
            onDismiss = onDismiss
        )
    }
}

@Composable
internal fun LibrarySortContent(
    sort: LibrarySort,
    favoritesOnly: Boolean,
    favoriteCount: Int,
    overnightOnly: Boolean = false,
    overnightCount: Int = 0,
    aiTranslatedOnly: Boolean = false,
    aiTranslatedCount: Int = 0,
    onSelectSort: (LibrarySort) -> Unit,
    onToggleFavorites: () -> Unit,
    onToggleOvernightOnly: () -> Unit = {},
    onToggleAiTranslatedOnly: () -> Unit = {},
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.pageH)
    ) {
        SheetSectionTitle("Sắp xếp theo")
        LibrarySort.entries.forEach { option ->
            SortOptionRow(
                label = option.label,
                selected = option == sort,
                onClick = {
                    onSelectSort(option)
                    onDismiss()
                }
            )
        }
        val showFavorites = favoriteCount > 0 || favoritesOnly
        val showOvernight = overnightCount > 0 || overnightOnly
        val showAiTranslated = aiTranslatedCount > 0 || aiTranslatedOnly
        if (showFavorites || showOvernight || showAiTranslated) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            SheetSectionTitle("Lọc")
            if (showFavorites) {
                FilterSwitchRow(
                    title = "Chỉ hiện truyện yêu thích",
                    subtitle = "$favoriteCount truyện yêu thích ở kệ này".takeIf { favoriteCount > 0 },
                    checked = favoritesOnly,
                    onToggle = onToggleFavorites
                )
            }
            if (showOvernight) {
                FilterSwitchRow(
                    title = "Đang bật dịch ngầm ban đêm",
                    subtitle = "$overnightCount truyện đang dịch ngầm ở kệ này".takeIf { overnightCount > 0 },
                    checked = overnightOnly,
                    onToggle = onToggleOvernightOnly
                )
            }
            if (showAiTranslated) {
                FilterSwitchRow(
                    title = "Đã có chương dịch AI",
                    subtitle = "$aiTranslatedCount truyện đã dịch AI ở kệ này".takeIf { aiTranslatedCount > 0 },
                    checked = aiTranslatedOnly,
                    onToggle = onToggleAiTranslatedOnly
                )
            }
        }
    }
}

@Composable
private fun SheetSectionTitle(text: String) {
    Text(
        text = text,
        style = AppText.groupHeader,
        color = LocalCustomColors.current.textSecondary,
        modifier = Modifier.padding(vertical = Spacing.xs)
    )
}

@Composable
private fun SortOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalCustomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(ChipShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = if (selected) AppText.bodyStrong else AppText.body,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(TruyenCloudIcons.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun FilterSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val colors = LocalCustomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(ChipShape)
            .toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() }),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = AppText.body, color = colors.textPrimary)
            if (!subtitle.isNullOrBlank()) {
                Text(text = subtitle, style = AppText.secondary, color = colors.textSecondary)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent,
                checkedBorderColor = colors.accent,
                uncheckedThumbColor = colors.textSecondary,
                uncheckedTrackColor = colors.cardHigh,
                uncheckedBorderColor = colors.outline
            )
        )
    }
}
