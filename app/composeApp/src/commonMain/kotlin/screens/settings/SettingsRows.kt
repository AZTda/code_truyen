package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.HairlineDivider
import com.truyencloud.compose.components.InsetIconTile
import com.truyencloud.compose.components.InsetIconTint
import com.truyencloud.compose.components.InsetRow
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.theme.AppPalette
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Hàng tự do trong [com.truyencloud.compose.components.InsetGroup] khi cần điều khiển chiếm cả chiều ngang bên dưới
 * tiêu đề (ô chọn phân đoạn, hàng chip, khối nhập). Cùng nhịp với InsetRow: ô biểu tượng 28dp, tiêu đề 15sp, dòng phụ 13sp.
 *
 * Nhóm cắt đúng 1dp đáy của hàng cuối, nên hàng tự vẽ đường kẻ đáy như InsetRow; thiếu đường kẻ này thì nhóm sẽ cắt mất
 * 1dp nội dung của hàng.
 *
 * @param enabled false chỉ làm mờ phần chữ; điều khiển bên trong tự lo trạng thái tắt của nó.
 */
@Composable
internal fun SettingsFormRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: InsetIconTint = InsetIconTint.Accent,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalCustomColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    InsetIconTile(icon, iconTint, enabled)
                    Spacer(Modifier.width(16.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = AppText.body,
                        color = colors.textPrimary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = AppText.secondary,
                            color = colors.textSecondary,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
        HairlineDivider(startInset = if (icon != null) FORM_ROW_ICON_INSET else FORM_ROW_PLAIN_INSET)
    }
}

// Cùng độ thụt đường kẻ với InsetRow: 60dp khi có ô biểu tượng, 16dp khi không
private val FORM_ROW_ICON_INSET = 60.dp
private val FORM_ROW_PLAIN_INSET = 16.dp

/**
 * Từ cỡ chữ này trở lên, cột chữ của một hàng chỉ còn vài ký tự mỗi dòng nếu vẫn đặt giá trị / nhãn hành động cạnh nó, nên các
 * hàng chuyển nội dung phụ xuống dưới phần chữ và ô phân đoạn dùng nhãn ngắn.
 */
internal const val LARGE_FONT_SCALE = 1.15f

/** Dòng phụ dài hơn số ký tự này thì cần cả bề ngang hàng (xem `stackLongText` của [ActionRow]). */
private const val LONG_SUBTITLE_CHARS = 44

/** Cỡ chữ hệ thống hiện tại có đủ lớn để cần bố cục gọn không. */
@Composable
internal fun isLargeFontScale(): Boolean = LocalDensity.current.fontScale > LARGE_FONT_SCALE

/**
 * Hàng có nhãn hành động ở cuối ("Đồng bộ ngay", "Xoá…", "Kiểm tra"). Cỡ chữ thường: nhãn nằm bên phải như InsetRow và
 * chạm cả hàng. Cỡ chữ lớn: nhãn là một nút chữ đặt dưới tiêu đề, thẳng hàng với phần chữ (chỉ nút chạm được).
 *
 * @param isBusy true thì thay nhãn bằng vòng chờ.
 * @param stackLongText true thì cũng chuyển nhãn xuống dưới khi dòng phụ dài hơn [LONG_SUBTITLE_CHARS] ký tự, kể cả cỡ chữ thường.
 * @param onClick null = hàng chỉ để xem (nhãn không bấm được).
 */
@Composable
internal fun ActionRow(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconTint: InsetIconTint = InsetIconTint.Accent,
    actionLabel: String? = null,
    actionColor: Color = Color.Unspecified,
    isBusy: Boolean = false,
    stackLongText: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val colors = LocalCustomColors.current
    val tone = if (actionColor == Color.Unspecified) colors.accent else actionColor
    // Dòng phụ dài (trạng thái đồng bộ kèm lý do lỗi) mà nhãn lại rộng thì cột chữ bị ép còn ~150dp và mất chữ ở dòng thứ 3
    val stacked = isLargeFontScale() || (stackLongText && (subtitle?.length ?: 0) > LONG_SUBTITLE_CHARS)
    if (!stacked) {
        InsetRow(
            title = title,
            modifier = modifier,
            subtitle = subtitle,
            icon = icon,
            iconTint = iconTint,
            onClick = onClick,
            trailing = {
                if (isBusy) RowSpinner(tone) else if (actionLabel != null) RowActionText(actionLabel, tone)
            }
        )
    } else {
        SettingsFormRow(title = title, modifier = modifier, subtitle = subtitle, icon = icon, iconTint = iconTint) {
            if (isBusy) {
                // RowSpinner đã có 12dp đệm trái; thêm 32dp cho thẳng hàng với tiêu đề, và chiều cao bằng nút chữ để hàng không giật
                Box(Modifier.heightIn(min = 48.dp).padding(start = 32.dp), contentAlignment = Alignment.CenterStart) { RowSpinner(tone) }
            } else if (actionLabel != null && onClick != null) {
                // 32dp + 12dp đệm của nút = 44dp: thẳng hàng với tiêu đề (ô biểu tượng 28dp + khoảng cách 16dp)
                TextActionButton(text = actionLabel, onClick = onClick, color = tone, modifier = Modifier.padding(start = 32.dp))
            } else if (actionLabel != null) {
                Box(Modifier.padding(start = 32.dp)) { RowActionText(actionLabel, tone) }
            }
        }
    }
}

/**
 * Chữ hành động ở cuối hàng ("Xoá…", "Đồng bộ ngay"): 15sp Medium, màu theo ngữ nghĩa. Tự chừa 12dp bên trái vì InsetRow
 * không chừa khoảng cách giữa cột chữ và nội dung `trailing`.
 */
@Composable
internal fun RowActionText(text: String, color: Color, enabled: Boolean = true) {
    Text(
        text = text,
        style = AppText.body.copy(fontWeight = FontWeight.Medium),
        color = if (enabled) color else LocalCustomColors.current.textTertiary,
        maxLines = 1,
        modifier = Modifier.padding(start = 12.dp)
    )
}

/** Vòng chờ nhỏ 20dp cuối hàng khi hành động đang chạy (cũng chừa 12dp bên trái). */
@Composable
internal fun RowSpinner(color: Color = LocalCustomColors.current.accent) {
    CircularProgressIndicator(color = color, strokeWidth = 2.dp, modifier = Modifier.padding(start = 12.dp).size(20.dp))
}

/**
 * Chạy [content] với bảng màu có màu "thẻ" do [cardTone] quyết định. Ô phân đoạn và ô tìm của hệ thống thiết kế tô nền bằng màu thẻ;
 * đặt chúng trên một nền cũng màu thẻ (nhóm inset, tờ trượt) thì nền ô chìm mất. Đổi màu thẻ cục bộ cho ô nổi lên mà không
 * phải sửa thành phần chung. Mọi màu khác (chữ, màu nhấn, đường kẻ) giữ nguyên.
 */
@Composable
private fun WithCardTone(cardTone: (AppPalette) -> Color, content: @Composable () -> Unit) {
    val c = LocalCustomColors.current
    val adjusted = remember(c) {
        AppPalette(
            isDark = c.isDark, bg = c.bg, surface = c.surface, card = cardTone(c), cardHigh = c.cardHigh,
            hairline = c.hairline, outline = c.outline,
            textPrimary = c.textPrimary, textSecondary = c.textSecondary, textTertiary = c.textTertiary,
            accent = c.accent, accentStrong = c.accentStrong, onAccent = c.onAccent,
            accentSoft = c.accentSoft, onAccentSoft = c.onAccentSoft,
            success = c.success, warning = c.warning, error = c.error
        )
    }
    CompositionLocalProvider(LocalCustomColors provides adjusted, content = content)
}

/**
 * Ô phân đoạn nằm TRONG nhóm inset / tờ trượt: ở giao diện tối rãnh (màu thẻ) chìm vào nền nhóm nên đổi rãnh sang màu nền trang,
 * giống ô phân đoạn của iOS ở chế độ tối. Giao diện sáng có rãnh cardHigh đủ tương phản nên giữ nguyên.
 */
@Composable
internal fun OnCardTrack(content: @Composable () -> Unit) {
    if (LocalCustomColors.current.isDark) WithCardTone({ it.bg }, content) else content()
}

/**
 * Ô tìm kiếm trên nền tờ trượt (cũng màu thẻ): tối thì nền ô là màu nền trang, sáng thì là cardHigh, để ô hiện rõ như ô tìm của iOS.
 */
@Composable
internal fun OnCardField(content: @Composable () -> Unit) {
    WithCardTone({ if (it.isDark) it.bg else it.cardHigh }, content)
}
