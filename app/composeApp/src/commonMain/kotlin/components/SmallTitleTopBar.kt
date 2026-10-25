package com.truyencloud.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont
import com.truyencloud.compose.theme.Spacing

/**
 * Thanh trên của màn con / chi tiết: tiêu đề giữa 17sp SemiBold, nút quay lại là mũi tên chevron, hành động bên phải
 * là [QuietIconButton]. Nền đặc + đường kẻ mảnh ở đáy; tự chừa vùng thanh trạng thái qua [windowInsets].
 *
 * @param onBack null = không có nút quay lại.
 * @param serifTitle true khi tiêu đề là tên truyện / chương (chỉ khi đó mới dùng chữ serif).
 * @param windowInsets truyền `WindowInsets(0.dp)` nếu màn cha đã tự chừa thanh trạng thái.
 */
@Composable
fun SmallTitleTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backContentDescription: String = "Quay lại",
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = WindowInsets.statusBars,
    containerColor: Color = Color.Unspecified,
    showDivider: Boolean = true,
    serifTitle: Boolean = false
) {
    val colors = LocalCustomColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor.takeOrElse { colors.bg })
            .windowInsetsPadding(windowInsets)
    ) {
        TopBarRow(
            height = Spacing.topBarHeight,
            leading = {
                if (onBack != null) {
                    QuietIconButton(
                        icon = TruyenCloudIcons.ChevronLeft,
                        contentDescription = backContentDescription,
                        onClick = onBack,
                        tint = colors.textPrimary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            },
            trailing = {
                Row(
                    modifier = Modifier.padding(end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions
                )
            }
        ) {
            val base = AppText.largeTitleCollapsed
            Text(
                text = title,
                style = if (serifTitle) base.copy(fontFamily = ReaderFont) else base,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }
            )
        }
        if (showDivider) HairlineDivider()
    }
}
