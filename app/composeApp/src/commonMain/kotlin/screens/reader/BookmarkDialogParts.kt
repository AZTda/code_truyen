package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.SecondaryButton
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppColors
import com.truyencloud.compose.theme.LocalCustomColors

/** Hàng chọn màu đánh dấu: 4 ô tròn, vùng chạm 48dp; ô đang chọn có dấu tích và vòng viền (không chỉ dựa vào màu). */
@Composable
internal fun BookmarkColorPicker(selectedColor: String, onSelect: (String) -> Unit) {
    val colors = LocalCustomColors.current
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BookmarkColors.forEach { (hex, name) ->
            val isChosen = selectedColor.equals(hex, ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .selectable(selected = isChosen, role = Role.RadioButton, onClick = { onSelect(hex) })
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(parseHexColor(hex))
                        .border(
                            width = if (isChosen) 3.dp else 1.dp,
                            color = if (isChosen) colors.textPrimary else colors.hairline,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isChosen) {
                        // Ô màu đánh dấu luôn là tông sáng vừa nên dấu tích dùng màu mực tối cố định
                        Icon(
                            imageVector = TruyenCloudIcons.Check,
                            contentDescription = null,
                            tint = AppColors.darkOnAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Nút phụ dưới đoạn trích: mở bảng tra cứu với chính đoạn văn này để bôi đen và ghim một cái tên. */
@Composable
internal fun ExplainTermButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TonalButton(
        text = "Giải thích hoặc ghim tên",
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        leadingIcon = TruyenCloudIcons.Search
    )
}

/** Các nút cuối hộp thoại (đặt trong hàng nút ghim của [ReaderDialogFrame]): [Xóa] (khi sửa dấu trang cũ) hoặc [Hủy], và [Lưu dấu trang]. */
@Composable
internal fun RowScope.BookmarkDialogActions(
    canDelete: Boolean,
    onDelete: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    if (canDelete) {
        TonalButton(text = "Xóa", onClick = onDelete, modifier = Modifier.weight(1f), destructive = true)
    } else {
        SecondaryButton(text = "Hủy", onClick = onCancel, modifier = Modifier.weight(1f))
    }
    PrimaryButton(text = "Lưu dấu trang", onClick = onSave, modifier = Modifier.weight(1.4f))
}
