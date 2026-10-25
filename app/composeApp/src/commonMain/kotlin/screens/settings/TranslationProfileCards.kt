package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.InsetIconTint
import com.truyencloud.compose.components.InsetRow
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.LocalCustomColors

data class GenreItem(
    val name: String,
    val icon: ImageVector
)

data class RatioOption(
    val value: String,
    val title: String,
    val subtitle: String
)

/** Bốn thể loại chính của hồ sơ dịch. [GenreItem.name] chính là giá trị lưu trên máy chủ. */
internal val GENRE_ITEMS = listOf(
    GenreItem("Tiên hiệp / Huyền huyễn", TruyenCloudIcons.Sparkles),
    GenreItem("Đô thị / Ngôn tình", TruyenCloudIcons.DomainEmotion),
    GenreItem("Võ hiệp / Kiếm hiệp", TruyenCloudIcons.Bolt),
    GenreItem("Khoa huyễn / Mạt thế", TruyenCloudIcons.Discover)
)

/** Ba mức giữ từ Hán Việt. [RatioOption.value] là giá trị lưu trên máy chủ; [title] ngắn để vừa ô phân đoạn. */
internal val RATIO_OPTIONS = listOf(
    RatioOption(value = "Thuần Việt mềm mại", title = "Thuần Việt", subtitle = "Mềm mại, dễ đọc"),
    RatioOption(value = "Cân bằng tự nhiên", title = "Cân bằng", subtitle = "Hài hòa chuẩn mực"),
    RatioOption(value = "Cổ trang Hán Việt", title = "Cổ trang", subtitle = "Đậm chất kiếm hiệp")
)

/**
 * Một thể loại trong danh sách chọn một: ô biểu tượng, tên, dấu tích màu nhấn khi đang chọn. Không phải quản trị viên
 * ([enabled] = false) thì dòng mờ đi và không chạm được.
 */
@Composable
internal fun GenreRow(
    item: GenreItem,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalCustomColors.current
    InsetRow(
        title = item.name,
        icon = item.icon,
        iconTint = if (isSelected) InsetIconTint.Accent else InsetIconTint.Neutral,
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.semantics { selected = isSelected },
        trailing = {
            if (isSelected) {
                Icon(
                    imageVector = TruyenCloudIcons.Check,
                    contentDescription = "Đang chọn",
                    tint = colors.accent,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
    )
}
