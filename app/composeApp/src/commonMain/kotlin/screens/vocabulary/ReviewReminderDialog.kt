package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.components.InsetGroup
import com.truyencloud.compose.components.InsetRow
import com.truyencloud.compose.components.InsetSwitchRow
import com.truyencloud.compose.components.IosSheet
import com.truyencloud.compose.components.NoticeBanner
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.notify.ReviewReminderConfig

private val PRESETS = listOf(7 to 0, 12 to 0, 18 to 0, 20 to 0, 21 to 30)

private fun two(n: Int) = n.toString().padStart(2, '0')

/** Bật/tắt nhắc ôn hằng ngày và chọn giờ nhắc (mặc định 20:00), mở dưới dạng tờ trượt. */
@Composable
internal fun ReviewReminderDialog(
    initial: ReviewReminderConfig,
    notice: String?,
    onSave: (ReviewReminderConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val c = LocalCustomColors.current
    var enabled by remember { mutableStateOf(initial.enabled) }
    var hour by remember { mutableStateOf(initial.hour) }
    var minute by remember { mutableStateOf(initial.minute) }

    IosSheet(onDismissRequest = onDismiss, title = "Nhắc ôn từ vựng") {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = "Mỗi ngày đúng giờ này, TruyenCloud báo số từ đang chờ ôn, ví dụ “12 từ cần ôn”.",
                style = AppText.secondary,
                color = c.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.pageH)
            )

            InsetGroup {
                InsetSwitchRow(title = "Nhắc mỗi ngày", checked = enabled, onCheckedChange = { enabled = it })
                InsetRow(title = "Giờ", enabled = enabled) {
                    TimeStepper("giờ", hour, enabled, { hour = (hour + 23) % 24 }, { hour = (hour + 1) % 24 })
                }
                InsetRow(title = "Phút", enabled = enabled) {
                    TimeStepper("phút", minute, enabled, { minute = (minute + 55) % 60 }, { minute = (minute + 5) % 60 })
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.pageH),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                PRESETS.forEach { (h, m) ->
                    FilterChip8(
                        label = "${two(h)}:${two(m)}",
                        selected = h == hour && m == minute,
                        enabled = enabled,
                        containerColor = c.cardHigh,
                        onClick = { hour = h; minute = m }
                    )
                }
            }

            if (notice != null) {
                NoticeBanner(
                    text = notice,
                    tone = NoticeTone.Warning,
                    modifier = Modifier.padding(horizontal = Spacing.pageH)
                )
            }

            Column(
                modifier = Modifier.padding(horizontal = Spacing.pageH),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PrimaryButton(
                    text = "Lưu",
                    onClick = { onSave(ReviewReminderConfig(enabled, hour, minute)) },
                    modifier = Modifier.fillMaxWidth()
                )
                TextActionButton(text = "Đóng", onClick = onDismiss)
            }
        }
    }
}

/** [−] 20 [+]: hai nút 48dp quanh con số, dùng làm phần đuôi của một [InsetRow]. */
@Composable
private fun RowScope.TimeStepper(
    unit: String,
    value: Int,
    enabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    val c = LocalCustomColors.current
    QuietIconButton(TruyenCloudIcons.Remove, "Giảm $unit", onDecrease, enabled = enabled)
    Text(
        text = two(value),
        style = AppText.bodyStrong,
        color = if (enabled) c.textPrimary else c.textTertiary,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(32.dp)
    )
    QuietIconButton(TruyenCloudIcons.Add, "Tăng $unit", onIncrease, enabled = enabled)
}
