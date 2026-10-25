package com.truyencloud.compose.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.components.SearchField
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.ChipShape
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Thanh lọc mục lục: ô tìm (số chương hoặc tên), nút nhảy nhanh tới chương đang đọc và hai nút bật/tắt — chỉ hiện chương đã tải, và đảo thứ tự (mới nhất trước).
 * Nút nào đang bật thì có nền màu nhấn nhạt và trình đọc màn hình đọc ra trạng thái bật.
 */
@Composable
fun ChapterFilterBar(
    filterQuery: String,
    onFilterChange: (String) -> Unit,
    isAscending: Boolean,
    onToggleSort: () -> Unit,
    onlyDownloaded: Boolean = false,
    onToggleOnlyDownloaded: () -> Unit = {},
    currentChapter: Int = 0,
    onJumpToCurrentChapter: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SearchField(
            value = filterQuery,
            onValueChange = onFilterChange,
            placeholder = "Tìm chương",
            modifier = Modifier.weight(1f)
        )
        if (currentChapter > 1 && onJumpToCurrentChapter != null) {
            FilterChip8(
                label = "Tới ch. $currentChapter",
                selected = false,
                onClick = onJumpToCurrentChapter
            )
        }
        FilterToggleButton(
            icon = TruyenCloudIcons.CloudDownload,
            contentDescription = "Chỉ hiện chương đã tải",
            checked = onlyDownloaded,
            onToggle = onToggleOnlyDownloaded
        )
        FilterToggleButton(
            icon = TruyenCloudIcons.SwapVert,
            contentDescription = "Xếp chương mới nhất trước",
            checked = !isAscending,
            onToggle = onToggleSort
        )
    }
}

/** Nút biểu tượng bật/tắt: vùng chạm 48dp, ô nền 40dp bo 10 chỉ có màu khi đang bật. */
@Composable
private fun FilterToggleButton(
    icon: ImageVector,
    contentDescription: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val colors = LocalCustomColors.current
    Box(
        modifier = Modifier
            .size(48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() }),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(ChipShape)
                .background(if (checked) colors.accentSoft else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (checked) colors.onAccentSoft else colors.textSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
