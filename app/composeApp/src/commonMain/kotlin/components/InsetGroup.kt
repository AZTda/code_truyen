package com.truyencloud.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.GroupShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import kotlin.math.ceil

/**
 * Nhóm danh sách inset kiểu iOS (Cài đặt, danh sách tuỳ chọn): khối màu thẻ bo 16, cách mép màn hình 16dp, các
 * [InsetRow] ngăn nhau bằng đường kẻ mảnh thụt vào 60dp. Tiêu đề nhóm 13sp Medium phía trên, chú thích 12sp phía dưới.
 *
 * Mỗi hàng tự vẽ đường kẻ ở đáy; nhóm cắt bỏ đúng một đường kẻ cuối nên không cần báo hàng nào là hàng cuối.
 * Xếp nhiều nhóm trong `Column(verticalArrangement = Arrangement.spacedBy(Spacing.section))`.
 */
@Composable
fun InsetGroup(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalCustomColors.current
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.groupInset)) {
        if (header != null) InsetGroupHeader(header)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GroupShape)
                .background(colors.card)
                .layout { measurable, constraints ->
                    // Bỏ 1dp cuối: đó là đường kẻ đáy của hàng cuối, nằm ngoài vùng cắt nên không hiện
                    val placeable = measurable.measure(constraints)
                    val trim = ceil(1.dp.toPx()).toInt()
                    layout(placeable.width, (placeable.height - trim).coerceAtLeast(0)) { placeable.place(0, 0) }
                },
            content = content
        )
        if (footer != null) InsetGroupFooter(footer)
    }
}

/** Tiêu đề nhóm: 13sp Medium chữ phụ, đặt phía trên khối nhóm (thụt 16dp để thẳng với chữ trong hàng). */
@Composable
fun InsetGroupHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = AppText.groupHeader,
        color = LocalCustomColors.current.textSecondary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
            .semantics { heading() }
    )
}

/** Chú thích nhóm: 12sp chữ thứ ba, đặt phía dưới khối nhóm. */
@Composable
fun InsetGroupFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = AppText.groupFooter,
        color = LocalCustomColors.current.textTertiary,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp)
    )
}
