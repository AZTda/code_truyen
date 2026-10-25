package com.truyencloud.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppPalette
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors

enum class NoticeTone { Info, Success, Warning, Error }

/**
 * Dải thông báo trong trang (lỗi mạng, đã lưu, cần đăng nhập...): nền tô nhạt theo [tone], biểu tượng + chữ 13sp (không chỉ
 * dựa vào màu), nút chữ [actionLabel] và nút đóng tuỳ chọn. Bo 12, không viền.
 */
@Composable
fun NoticeBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.Info,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) {
    val colors = LocalCustomColors.current
    val (fill, accent, icon) = toneStyle(tone, colors)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(InputShape)
            .background(fill)
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = if (onDismiss != null) 0.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            if (title != null) Text(title, style = AppText.secondaryStrong, color = colors.textPrimary)
            Text(text, style = AppText.secondary, color = colors.textPrimary)
            if (actionLabel != null && onAction != null) {
                // Nút chữ có đệm ngang 12dp: dịch sang trái để chữ thẳng hàng với đoạn nội dung phía trên
                TextActionButton(actionLabel, onAction, modifier = Modifier.offset(x = (-12).dp), color = accent)
            }
        }
        if (onDismiss != null) {
            QuietIconButton(TruyenCloudIcons.Close, "Đóng thông báo", onDismiss, iconSize = 20.dp)
        }
    }
}

private data class ToneStyle(val fill: Color, val accent: Color, val icon: ImageVector)

private fun toneStyle(tone: NoticeTone, c: AppPalette): ToneStyle = when (tone) {
    NoticeTone.Info -> ToneStyle(c.accentSoft, c.onAccentSoft, TruyenCloudIcons.Info)
    NoticeTone.Success -> ToneStyle(c.successSoft, c.success, TruyenCloudIcons.CheckCircle)
    NoticeTone.Warning -> ToneStyle(c.warningSoft, c.warning, TruyenCloudIcons.Warning)
    NoticeTone.Error -> ToneStyle(c.errorSoft, c.error, TruyenCloudIcons.Warning)
}
