package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.components.elapsedWaitText
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.components.rememberElapsedSeconds
import com.truyencloud.compose.theme.AppPalette
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.ChipShape
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.reader.TranslationFailureKind
import kotlinx.coroutines.delay

internal class BannerAction(val label: String, val primary: Boolean = false, val onClick: () -> Unit)

/**
 * Khách chưa đăng nhập (chưa có phiên) bị chặn dịch: bấm [Thử lại] không bao giờ thành công nên ẩn nút đó. Người đã đăng nhập
 * mà phiên hết hạn (vẫn còn token) thì vẫn có nút — phiên có thể vừa được gia hạn nền.
 */
internal fun TranslationFailureKind?.isGuestBlocked(): Boolean =
    this == TranslationFailureKind.LOGIN_REQUIRED && TruyenCloudApi.authToken.isBlank()

private class BannerTone(val color: Color, val soft: Color, val icon: ImageVector)

private fun bannerTone(tone: NoticeTone, c: AppPalette): BannerTone = when (tone) {
    NoticeTone.Info -> BannerTone(c.onAccentSoft, c.accentSoft, TruyenCloudIcons.Info)
    NoticeTone.Success -> BannerTone(c.success, c.successSoft, TruyenCloudIcons.CheckCircle)
    NoticeTone.Warning -> BannerTone(c.warning, c.warningSoft, TruyenCloudIcons.Warning)
    NoticeTone.Error -> BannerTone(c.error, c.errorSoft, TruyenCloudIcons.Warning)
}

/**
 * Banner một khối: biểu tượng (hoặc vòng quay khi [busy]) + lời nhắn + hàng nút chữ. Nền thẻ ĐẶC có phủ một lớp tông ngữ nghĩa nhạt
 * nên chữ phía sau không lộ qua; trạng thái không chỉ dựa vào màu mà còn có biểu tượng.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderBannerCard(
    message: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.Info,
    actions: List<BannerAction> = emptyList(),
    title: String? = null,
    busy: Boolean = false
) {
    val colors = LocalCustomColors.current
    val style = bannerTone(tone, colors)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(InputShape)
            .background(colors.cardHigh)
            .background(style.soft)
            .border(1.dp, colors.hairline, InputShape)
            .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = if (actions.isEmpty()) 12.dp else 0.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = style.color)
            } else {
                Icon(style.icon, contentDescription = null, tint = style.color, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                if (title != null) Text(text = title, style = AppText.secondaryStrong, color = colors.textPrimary)
                Text(text = message, style = AppText.secondary, color = colors.textPrimary)
            }
        }
        if (actions.isNotEmpty()) {
            // Chữ nút thẳng hàng với chữ lời nhắc: lệch 30dp (biểu tượng 20 + khoảng 10) trừ đệm ngang 12dp của nút chữ
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp),
                horizontalArrangement = Arrangement.Start,
                verticalArrangement = Arrangement.Center
            ) {
                actions.forEach { action ->
                    TextActionButton(
                        text = action.label,
                        onClick = action.onClick,
                        color = if (action.primary) colors.accent else colors.textSecondary
                    )
                }
            }
        }
    }
}

/** Chip nhỏ "Đang tải chương N…" (có nút đóng huỷ) hiện khi đang tải chương khác mà chương cũ vẫn đọc được. */
@Composable
internal fun ReaderLoadingChip(target: Int?, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalCustomColors.current
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(ChipShape)
            .background(colors.cardHigh)
            .border(1.dp, colors.hairline, ChipShape)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (target != null) "Đang tải chương $target…" else "Đang tải chương…",
            style = AppText.secondary,
            color = colors.textPrimary
        )
        QuietIconButton(TruyenCloudIcons.Close, "Hủy tải chương", onCancel, iconSize = 20.dp)
    }
}

/** Banner không chặn ở đầu màn hình khi đang dịch / dịch lại trong lúc vẫn có chữ để đọc. */
@Composable
internal fun ReaderTranslatingBanner(chapterNumber: Int?, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val elapsed = rememberElapsedSeconds()
    val label = if (chapterNumber != null) "AI đang dịch chương $chapterNumber" else "AI đang dịch"
    ReaderBannerCard(
        message = "$label… ${elapsedWaitText(elapsed)}. Bạn vẫn đọc tiếp được.",
        tone = NoticeTone.Info,
        actions = listOf(BannerAction("Hủy", onClick = onCancel)),
        modifier = modifier,
        busy = true
    )
}

/** Thông báo ngắn tự tắt (giữ nguyên nội dung đang đọc khi một việc nền thất bại). Màu theo chủ đề đọc nên sáng / sepia vẫn đọc được. */
@Composable
internal fun ReaderToast(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalCustomColors.current
    LaunchedEffect(message) {
        delay(5_000L)
        onDismiss()
    }
    Box(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .heightIn(min = 48.dp)
            .clip(InputShape)
            .background(colors.cardHigh)
            .border(1.dp, colors.hairline, InputShape)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text = message, style = AppText.secondary, color = colors.textPrimary)
    }
}

/** Huy hiệu nhỏ ở góc: đang dịch NGẦM một chương khác (người đọc đã sang chương khác trong lúc chờ). */
@Composable
internal fun ReaderBackgroundTranslationBadge(chapterNumber: Int, modifier: Modifier = Modifier) {
    val colors = LocalCustomColors.current
    Row(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(ChipShape)
            .background(colors.cardHigh)
            .border(1.dp, colors.hairline, ChipShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.accent)
        Text(text = "Đang dịch chương $chapterNumber…", style = AppText.caption, color = colors.textSecondary)
    }
}
