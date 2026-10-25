package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.truyencloud.compose.components.IosAlertAction
import com.truyencloud.compose.components.IosAlertDialog
import com.truyencloud.compose.components.IosAlertRole
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.reader.ChapterNavigation

/** Những gì thẻ "hết truyện" cần biết: chương cuối đã biết, đang kiểm tra chương mới, thông báo chương mới... */
internal data class EndOfBookUi(
    val isLastChapter: Boolean = false,
    val lastChapter: Int = 0,
    val isChecking: Boolean = false,
    val notice: String? = null,
    val alertsAvailable: Boolean = false,
    val alertsEnabled: Boolean = false
)

internal fun ReaderUiState.endOfBookUi(): EndOfBookUi = EndOfBookUi(
    isLastChapter = ChapterNavigation.isLast(currentChapter?.chapterNumber ?: 0, lastChapterNumber),
    lastChapter = lastChapterNumber,
    isChecking = isCheckingNewChapters,
    notice = newChaptersNotice,
    alertsAvailable = newChapterAlertsAvailable,
    alertsEnabled = newChapterAlertsEnabled
)

/** "Bạn đã đọc hết (hiện có N chương)" + [Kiểm tra chương mới] + [Bật thông báo chương mới]. */
@Composable
internal fun ReaderEndOfBookPanel(
    ui: EndOfBookUi,
    onCheckNewChapters: () -> Unit,
    onEnableAlerts: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = ChapterNavigation.endOfBookMessage(ui.lastChapter),
            style = AppText.bodyStrong,
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        PrimaryButton(
            text = if (ui.isChecking) "Đang kiểm tra…" else "Kiểm tra chương mới",
            onClick = { if (!ui.isChecking) onCheckNewChapters() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !ui.isChecking
        )
        if (ui.alertsAvailable) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            TonalButton(
                text = if (ui.alertsEnabled) "Đã bật thông báo chương mới" else "Bật thông báo chương mới",
                onClick = { if (!ui.alertsEnabled) onEnableAlerts() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !ui.alertsEnabled
            )
        }
        ui.notice?.let { notice ->
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = notice,
                style = AppText.captionRegular,
                color = colors.textSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Hộp thoại "hết truyện" — bấm Chương sau ở chương cuối (nhất là chế độ lật trang, nơi không có thẻ hết chương để cuộn tới).
 * Kiểu iOS: tiêu đề giữa, nút xếp dọc. Sau khi kiểm tra mà có chương mới thì đổi thành nút sang chương kế.
 */
@Composable
internal fun ReaderEndOfBookDialog(
    ui: EndOfBookUi,
    onCheckNewChapters: () -> Unit,
    onEnableAlerts: () -> Unit,
    onNextChapter: () -> Unit,
    onDismiss: () -> Unit
) {
    if (ui.isLastChapter) {
        IosAlertDialog(
            onDismissRequest = onDismiss,
            title = ChapterNavigation.endOfBookMessage(ui.lastChapter),
            message = ui.notice,
            actions = buildList {
                add(
                    IosAlertAction(
                        label = if (ui.isChecking) "Đang kiểm tra…" else "Kiểm tra chương mới",
                        role = IosAlertRole.Primary,
                        enabled = !ui.isChecking,
                        onClick = onCheckNewChapters
                    )
                )
                if (ui.alertsAvailable) {
                    add(
                        IosAlertAction(
                            label = if (ui.alertsEnabled) "Đã bật thông báo chương mới" else "Bật thông báo chương mới",
                            enabled = !ui.alertsEnabled,
                            onClick = onEnableAlerts
                        )
                    )
                }
                add(IosAlertAction(label = "Đóng", onClick = onDismiss))
            }
        )
    } else {
        IosAlertDialog(
            onDismissRequest = onDismiss,
            title = ui.notice ?: "Có chương mới để đọc tiếp.",
            actions = listOf(
                IosAlertAction(
                    label = "Đọc chương tiếp theo",
                    role = IosAlertRole.Primary,
                    onClick = {
                        onDismiss()
                        onNextChapter()
                    }
                ),
                IosAlertAction(label = "Đóng", onClick = onDismiss)
            )
        )
    }
}
