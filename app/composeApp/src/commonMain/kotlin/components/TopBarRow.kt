package com.truyencloud.compose.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

private const val LEADING = "leading"
private const val TITLE = "title"
private const val TRAILING = "trailing"

/**
 * Hàng thanh trên: [leading] sát trái, [trailing] sát phải, [title] căn GIỮA màn hình khi đủ chỗ. Tiêu đề không bao giờ
 * đè lên nút: nếu căn giữa thì chạm nút (vd. 3 nút bên phải trên màn 360dp) nó dịch sang trái vừa đủ, và chỉ bị cắt "…"
 * khi cả khoảng giữa hai nhóm nút cũng không đủ rộng.
 */
@Composable
internal fun TopBarRow(
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
    title: @Composable () -> Unit
) {
    Layout(
        content = {
            Box(Modifier.layoutId(LEADING)) { leading() }
            Box(Modifier.layoutId(TITLE)) { title() }
            Box(Modifier.layoutId(TRAILING)) { trailing() }
        },
        modifier = modifier.fillMaxWidth().heightIn(min = height)
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val lead = measurables.first { it.layoutId == LEADING }.measure(loose)
        val trail = measurables.first { it.layoutId == TRAILING }.measure(loose)
        val symmetric = max(lead.width, trail.width)
        val w = constraints.maxWidth
        // Chừa đối xứng nếu tiêu đề vừa trong đó; không vừa thì dùng toàn bộ khoảng giữa hai nhóm nút
        val naturalWidth = measurables.first { it.layoutId == TITLE }.maxIntrinsicWidth(loose.maxHeight)
        val titleMax = if (naturalWidth <= w - 2 * symmetric) w - 2 * symmetric else (w - lead.width - trail.width)
        val titlePlaceable = measurables.first { it.layoutId == TITLE }
            .measure(Constraints(maxWidth = titleMax.coerceAtLeast(0), maxHeight = loose.maxHeight))
        val h = maxOf(constraints.minHeight, lead.height, trail.height, titlePlaceable.height)
        layout(w, h) {
            lead.placeRelative(0, (h - lead.height) / 2)
            trail.placeRelative(w - trail.width, (h - trail.height) / 2)
            // Căn giữa, rồi kẹp vào khoảng giữa hai nhóm nút
            val centered = (w - titlePlaceable.width) / 2
            val minX = lead.width
            val maxX = (w - trail.width - titlePlaceable.width).coerceAtLeast(minX)
            titlePlaceable.placeRelative(centered.coerceIn(minX, maxX), (h - titlePlaceable.height) / 2)
        }
    }
}
