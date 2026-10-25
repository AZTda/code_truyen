package com.truyencloud.compose.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppAnimations
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.ChipShape
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Chip lọc / nhãn hình chữ nhật bo 10 (không phải viên thuốc), cao 32dp trong vùng chạm 48dp. Chưa chọn: nền thẻ + chữ
 * phụ; đã chọn: nền accentSoft + chữ màu nhấn. Không viền màu.
 *
 * @param trailingText số đếm đứng sau nhãn (vd. số truyện yêu thích).
 * @param containerColor nền khi chưa chọn; mặc định màu thẻ. Đặt cardHigh nếu chip nằm trong một thẻ.
 */
@Composable
fun FilterChip8(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailingText: String? = null,
    enabled: Boolean = true,
    containerColor: Color = Color.Unspecified
) {
    val colors = LocalCustomColors.current
    val fill by animateColorAsState(
        targetValue = if (selected) colors.accentSoft else containerColor.takeOrElse { colors.card },
        animationSpec = tween(AppAnimations.SHORT_MS),
        label = "chipFill"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) colors.onAccentSoft else colors.textSecondary,
        animationSpec = tween(AppAnimations.SHORT_MS),
        label = "chipText"
    )
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 32.dp)
                .clip(ChipShape)
                .background(fill)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, tint = textColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = AppText.secondaryStrong,
                color = if (enabled) textColor else colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (trailingText != null) {
                Spacer(Modifier.width(6.dp))
                Text(text = trailingText, style = AppText.secondaryStrong, color = textColor, maxLines = 1)
            }
        }
    }
}
