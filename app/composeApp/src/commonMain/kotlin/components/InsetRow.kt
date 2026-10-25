package com.truyencloud.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppPalette
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.IconTileShape
import com.truyencloud.compose.theme.LocalCustomColors

/** Màu ô biểu tượng 28dp: tô nhạt theo ngữ nghĩa. Mặc định là màu nhấn; Neutral cho hàng không cần nhấn mạnh. */
enum class InsetIconTint { Accent, Neutral, Success, Warning, Error }

internal val IconDividerInset = 60.dp
internal val PlainDividerInset = 16.dp

/**
 * Một hàng của [InsetGroup]: ô biểu tượng 28dp (tuỳ chọn), tiêu đề 15sp, dòng phụ 13sp, rồi giá trị / công tắc / mũi tên
 * bên phải. Cao tối thiểu 52dp và cao thêm theo cỡ chữ. Có [onClick] thì cả hàng bấm được.
 *
 * @param value chữ giá trị bên phải (vd. "Hệ thống"), một dòng.
 * @param destructive tiêu đề màu lỗi cho hành động nguy hiểm (Xóa dữ liệu).
 * @param trailing nội dung riêng bên phải (nút, chữ trạng thái) thay cho [value].
 */
@Composable
fun InsetRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: InsetIconTint = InsetIconTint.Accent,
    value: String? = null,
    showChevron: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val colors = LocalCustomColors.current
    InsetRowFrame(
        modifier = modifier,
        dividerInset = if (icon != null) IconDividerInset else PlainDividerInset,
        enabled = enabled,
        action = if (onClick != null) InsetRowAction.Button(onClick) else null
    ) {
        RowLead(icon, iconTint, title, subtitle, destructive, enabled)
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = AppText.body,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp)
            )
        }
        trailing?.invoke(this)
        if (showChevron) {
            Spacer(Modifier.width(4.dp))
            Icon(TruyenCloudIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
        }
    }
}

/** Hàng có công tắc: bấm cả hàng để bật / tắt. */
@Composable
fun InsetSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: InsetIconTint = InsetIconTint.Accent,
    enabled: Boolean = true
) {
    val colors = LocalCustomColors.current
    InsetRowFrame(
        modifier = modifier,
        dividerInset = if (icon != null) IconDividerInset else PlainDividerInset,
        enabled = enabled,
        action = InsetRowAction.Toggle(checked, onCheckedChange)
    ) {
        RowLead(icon, iconTint, title, subtitle, destructive = false, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
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

internal sealed interface InsetRowAction {
    class Button(val onClick: () -> Unit) : InsetRowAction
    class Toggle(val checked: Boolean, val onChange: (Boolean) -> Unit) : InsetRowAction
}

/** Khung hàng: cao >= 52dp, đệm 16dp, đường kẻ đáy thụt [dividerInset] (nhóm sẽ cắt đường kẻ của hàng cuối). */
@Composable
internal fun InsetRowFrame(
    modifier: Modifier,
    dividerInset: Dp,
    enabled: Boolean,
    action: InsetRowAction?,
    content: @Composable RowScope.() -> Unit
) {
    val colors = LocalCustomColors.current
    val interactive = when (action) {
        is InsetRowAction.Button -> Modifier.clickable(enabled = enabled, role = Role.Button, onClick = action.onClick)
        is InsetRowAction.Toggle -> Modifier.toggleable(
            value = action.checked, enabled = enabled, role = Role.Switch, onValueChange = action.onChange
        )
        null -> Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val thickness = 1.dp.toPx()
                val start = dividerInset.toPx()
                drawRect(colors.hairline, Offset(start, size.height - thickness), Size(size.width - start, thickness))
            }
            .then(interactive)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun RowScope.RowLead(
    icon: ImageVector?,
    iconTint: InsetIconTint,
    title: String,
    subtitle: String?,
    destructive: Boolean,
    enabled: Boolean
) {
    val colors = LocalCustomColors.current
    if (icon != null) {
        InsetIconTile(icon, iconTint, enabled)
        Spacer(Modifier.width(16.dp))
    }
    Column(modifier = Modifier.weight(1f).alpha(if (enabled) 1f else 0.5f)) {
        Text(
            text = title,
            style = AppText.body,
            color = if (destructive) colors.error else colors.textPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = AppText.secondary,
                color = colors.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Ô biểu tượng vuông 28dp bo 8 với nền tô nhạt theo [tint]; biểu tượng 18dp. */
@Composable
fun InsetIconTile(icon: ImageVector, tint: InsetIconTint, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val colors = LocalCustomColors.current
    val (fill, glyph) = tileColors(tint, colors)
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(IconTileShape)
            .background(fill)
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = glyph, modifier = Modifier.size(18.dp))
    }
}

private fun tileColors(tint: InsetIconTint, c: AppPalette): Pair<Color, Color> = when (tint) {
    InsetIconTint.Accent -> c.accentSoft to c.onAccentSoft
    InsetIconTint.Neutral -> c.cardHigh to c.textSecondary
    InsetIconTint.Success -> c.successSoft to c.success
    InsetIconTint.Warning -> c.warningSoft to c.warning
    InsetIconTint.Error -> c.errorSoft to c.error
}
