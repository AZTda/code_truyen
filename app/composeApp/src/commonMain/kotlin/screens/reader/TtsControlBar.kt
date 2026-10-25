package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.CappedFontScale
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.ButtonShape
import com.truyencloud.compose.theme.CardShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.tts.AmbientSoundType
import com.truyencloud.shared.tts.SleepTimerMode
import com.truyencloud.shared.tts.TtsSpeedPresets

/**
 * Bề ngang dòng thẻ đủ chỗ cho cả năm nút 48dp cùng cột thông tin (>= 80dp); hẹp hơn thì nút chuyển sang hàng riêng
 * thay vì thu nhỏ vùng chạm.
 */
private val SingleRowMinWidth = 332.dp

/**
 * Thanh nghe đọc nổi phía trên thanh dưới của màn đọc: thẻ phẳng cardHigh + đường kẻ mảnh, cùng kiểu với thanh cuộn tự động.
 * Bên trái là vị trí đoạn đang đọc (chạm để mở cài đặt nghe đọc), kế đó là đoạn trước / phát - dừng / đoạn sau, tốc độ và đóng.
 * Dòng nhỏ phía trên vị trí cho biết hẹn giờ đang chạy, âm nền đang bật, hoặc trạng thái phát.
 *
 * @param respectNavigationBar true khi thanh dưới của màn đọc đang ẩn (thanh đó đã chừa vùng điều hướng khi hiện).
 */
@Composable
fun TtsControlBar(
    isPlaying: Boolean,
    currentParagraph: Int,
    totalParagraphs: Int,
    speed: Float,
    sleepTimerMode: SleepTimerMode = SleepTimerMode.OFF,
    sleepTimerRemainingSeconds: Int? = null,
    ambientSoundType: AmbientSoundType = AmbientSoundType.NONE,
    onPlayPause: () -> Unit,
    onPrevParagraph: () -> Unit,
    onNextParagraph: () -> Unit,
    onCycleSpeed: () -> Unit,
    onOpenStudio: () -> Unit,
    onCloseTts: () -> Unit,
    modifier: Modifier = Modifier,
    respectNavigationBar: Boolean = false,
    preparingLabel: String? = null,
    deviceFallback: Boolean = false
) {
    val colors = LocalCustomColors.current
    // Đang chờ máy chủ dựng giọng thì nút chính là "thôi chờ" (cùng chỗ với nút tạm dừng)
    val busy = isPlaying || preparingLabel != null
    val caption = playerCaption(isPlaying, sleepTimerMode, sleepTimerRemainingSeconds, ambientSoundType)
    val position = "${currentParagraph + 1} / $totalParagraphs"

    // Chữ trong thanh cố định theo hàng 48dp nên không phóng quá 1,15 lần
    CappedFontScale(max = 1.15f) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxWidth()
                .then(if (respectNavigationBar) Modifier.navigationBarsPadding() else Modifier)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            val singleRow = maxWidth >= SingleRowMinWidth
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CardShape)
                    .background(colors.cardHigh)
                    .border(1.dp, colors.hairline, CardShape)
            ) {
                StatusLine(preparingLabel, deviceFallback)
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayerInfo(caption, position, onOpenStudio, Modifier.weight(1f))
                    if (singleRow) TransportKeys(busy, onPrevParagraph, onPlayPause, onNextParagraph)
                    SpeedKey(speed, onCycleSpeed)
                    QuietIconButton(TruyenCloudIcons.Close, "Đóng trình nghe đọc", onCloseTts)
                }
                if (!singleRow) {
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.Center) {
                        TransportKeys(busy, onPrevParagraph, onPlayPause, onNextParagraph)
                    }
                }
            }
        }
    }
}

/**
 * Dòng trạng thái đủ rộng phía trên hàng nút: đang chờ máy chủ dựng giọng AI (sẽ tự phát), hoặc đang đọc bằng giọng của
 * điện thoại vì giọng AI lỗi. Không có gì để báo thì không chiếm chỗ.
 */
@Composable
private fun StatusLine(preparingLabel: String?, deviceFallback: Boolean) {
    val text = preparingLabel ?: if (deviceFallback) "Máy chủ chưa phản hồi, đang đọc bằng giọng của điện thoại" else return
    val colors = LocalCustomColors.current
    Text(
        text = text,
        style = AppText.caption,
        color = if (preparingLabel != null) colors.onAccentSoft else colors.textSecondary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp)
    )
}

private class PlayerCaption(val text: String, val icon: ImageVector?, val highlighted: Boolean)

/**
 * Một dòng trạng thái: hẹn giờ (đếm ngược hoặc hết chương) > âm nền đang bật > đang nghe / tạm dừng. "Hết chương" không kèm biểu
 * tượng vì cột thông tin chỉ rộng khoảng 90dp: có biểu tượng thì chữ bị cắt "Hết chư…" ở cỡ chữ lớn.
 */
private fun playerCaption(
    isPlaying: Boolean,
    timerMode: SleepTimerMode,
    timerRemaining: Int?,
    ambient: AmbientSoundType
): PlayerCaption = when {
    timerRemaining != null && timerRemaining > 0 ->
        PlayerCaption(formatClock(timerRemaining), TruyenCloudIcons.Timer, highlighted = true)
    timerMode == SleepTimerMode.END_OF_CHAPTER ->
        PlayerCaption("Hết chương", icon = null, highlighted = true)
    ambient != AmbientSoundType.NONE -> PlayerCaption(ambient.label, ambient.icon(), highlighted = false)
    else -> PlayerCaption(if (isPlaying) "Đang nghe" else "Tạm dừng", icon = null, highlighted = false)
}

@Composable
private fun PlayerInfo(caption: PlayerCaption, position: String, onOpenStudio: () -> Unit, modifier: Modifier) {
    val colors = LocalCustomColors.current
    Column(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = "Mở cài đặt nghe đọc", role = Role.Button, onClick = onOpenStudio)
            .padding(end = 4.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (caption.icon != null) {
                Icon(
                    imageVector = caption.icon,
                    contentDescription = null,
                    tint = if (caption.highlighted) colors.accent else colors.textSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = caption.text,
                style = AppText.caption,
                color = if (caption.highlighted) colors.onAccentSoft else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = position,
            style = AppText.secondaryStrong,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TransportKeys(isPlaying: Boolean, onPrev: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    val colors = LocalCustomColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        QuietIconButton(TruyenCloudIcons.SkipPrevious, "Đoạn trước", onPrev, tint = colors.textPrimary)
        Box(
            modifier = Modifier.size(48.dp).clickable(role = Role.Button, onClick = onPlayPause),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(ButtonShape)
                    .background(colors.accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) TruyenCloudIcons.PauseFilled else TruyenCloudIcons.PlayFilled,
                    contentDescription = if (isPlaying) "Tạm dừng" else "Đọc tiếp",
                    tint = colors.onAccent,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        QuietIconButton(TruyenCloudIcons.SkipNext, "Đoạn sau", onNext, tint = colors.textPrimary)
    }
}

/** Nút đổi tốc độ: chữ màu nhấn (vd. "1.25x"), chạm để nhảy sang mốc kế tiếp. */
@Composable
private fun SpeedKey(speed: Float, onCycleSpeed: () -> Unit) {
    val colors = LocalCustomColors.current
    val label = TtsSpeedPresets.label(speed)
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onCycleSpeed)
            .semantics { contentDescription = "Tốc độ đọc $label, chạm để đổi" }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, style = AppText.secondaryStrong, color = colors.accent, maxLines = 1)
    }
}

/** 75 giây -> "01:15". */
internal fun formatClock(totalSeconds: Int): String {
    val m = (totalSeconds / 60).toString().padStart(2, '0')
    val s = (totalSeconds % 60).toString().padStart(2, '0')
    return "$m:$s"
}
