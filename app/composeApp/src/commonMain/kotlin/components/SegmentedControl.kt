package com.truyencloud.compose.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppAnimations
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.SegmentedThumbShape
import com.truyencloud.compose.theme.SegmentedTrackShape
import kotlin.math.roundToInt

/**
 * Chọn một trong 2-4 lựa chọn ngắn kiểu iOS (kệ sách, Khám phá, chế độ giao diện). Rãnh cao 36dp bo 10, viên chọn bo 8
 * trượt 200ms kèm bóng mờ 1dp; vùng chạm mỗi ô cao 48dp. Nhãn 13sp Medium, một dòng.
 *
 * Nhãn dài hơn chiều rộng một ô sẽ bị cắt "…": giữ nhãn ngắn, hoặc dùng cuộn ngang với [FilterChip8] khi có từ 5 lựa chọn.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    if (options.isEmpty()) return
    val colors = LocalCustomColors.current
    val index = selectedIndex.coerceIn(0, options.lastIndex)

    // Rãnh cố định 36dp ở cỡ chữ chuẩn nên giới hạn phóng chữ để nhãn không bị cắt khi người dùng chỉnh chữ rất lớn
    CappedFontScale(max = 1.3f) {
        val thumbPosition by animateFloatAsState(
            targetValue = index.toFloat(),
            animationSpec = tween(AppAnimations.SHORT_MS),
            label = "segmentThumb"
        )

        Box(modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f)) {
            // Rãnh + viên chọn nằm sau hàng ô chạm; rãnh thấp hơn ô chạm 12dp (36 so với 48)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(vertical = 6.dp)
                    .clip(SegmentedTrackShape)
                    .background(colors.segmentTrack)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Đo viên chọn rộng đúng 1/n rãnh rồi đặt theo vị trí đang trượt (không cần BoxWithConstraints)
                        .layout { measurable, constraints ->
                            val segment = constraints.maxWidth / options.size
                            val placeable = measurable.measure(Constraints.fixed(segment, constraints.maxHeight))
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                placeable.place((thumbPosition * segment).roundToInt(), 0)
                            }
                        }
                        .padding(2.dp)
                        .shadow(1.dp, SegmentedThumbShape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
                        .background(colors.segmentThumb, SegmentedThumbShape)
                )
            }

            Row(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                options.forEachIndexed { i, label ->
                    val textColor by animateColorAsState(
                        targetValue = if (i == index) colors.textPrimary else colors.textSecondary,
                        animationSpec = tween(AppAnimations.SHORT_MS),
                        label = "segmentText"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .selectable(selected = i == index, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(i) })
                            .padding(horizontal = 2.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = AppText.secondaryStrong,
                            color = textColor,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** Biến thể theo giá trị: `SegmentedControl(Shelf.entries, shelf, { shelf = it }, label = { it.label })`. */
@Composable
fun <T> SegmentedControl(
    items: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    SegmentedControl(
        options = items.map(label),
        selectedIndex = items.indexOf(selected).coerceAtLeast(0),
        onSelect = { onSelect(items[it]) },
        modifier = modifier,
        enabled = enabled
    )
}
