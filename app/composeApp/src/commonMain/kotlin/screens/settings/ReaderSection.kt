package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.InsetGroup
import com.truyencloud.compose.components.InsetIconTint
import com.truyencloud.compose.components.InsetRow
import com.truyencloud.compose.components.InsetSwitchRow
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.SegmentedControl
import com.truyencloud.compose.components.ThemeModeControl
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

private val READER_MODES = listOf(
    "translated" to "Bản dịch",
    "original" to "Nguyên tác",
    "bilingual" to "Song ngữ"
)

private const val MIN_FONT_SIZE = 12
private const val MAX_FONT_SIZE = 32

/**
 * Nhóm "Đọc truyện": chế độ giao diện Hệ thống / Sáng / Tối, chế độ hiển thị văn bản, cỡ chữ mặc định và báo chương mới.
 * Chế độ văn bản nằm trong hồ sơ dịch DÙNG CHUNG nên chỉ quản trị viên đổi được ([isAdmin]); người khác vẫn đổi được
 * ngay trong màn đọc.
 */
@Composable
fun ReaderSection(
    preferredTheme: String,
    fontSize: Int = 18,
    readerMode: String = "translated",
    onThemeChange: (String) -> Unit,
    onFontSizeChange: (Int) -> Unit = {},
    onReaderModeChange: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    isAdmin: Boolean = true,
    /** Báo khi truyện đang đọc / yêu thích có chương mới. */
    notifyNewChapters: Boolean = false,
    onNotifyNewChaptersChange: (Boolean) -> Unit = {},
    notifyStatusMessage: String = "",
    onCheckUpdatesNow: () -> Unit = {},
    isCheckingUpdates: Boolean = false
) {
    InsetGroup(
        modifier = modifier,
        header = "Đọc truyện",
        footer = if (isAdmin) null else {
            "Chế độ mặc định do quản trị viên đặt cho cả máy chủ. Bạn vẫn đổi được chế độ ngay trong màn đọc."
        }
    ) {
        SettingsFormRow(title = "Giao diện", icon = TruyenCloudIcons.Moon) {
            OnCardTrack {
                ThemeModeControl(storedTheme = preferredTheme, onSelect = { onThemeChange(it.stored) })
            }
        }

        InsetRow(
            title = "Cỡ chữ",
            subtitle = "Mặc định cho mọi chương",
            icon = TruyenCloudIcons.FormatSize,
            trailing = { FontSizeStepper(fontSize, onFontSizeChange) }
        )

        SettingsFormRow(title = "Chế độ hiển thị văn bản", icon = TruyenCloudIcons.Translate, enabled = isAdmin) {
            OnCardTrack {
                SegmentedControl(
                    options = READER_MODES.map { it.second },
                    selectedIndex = READER_MODES.indexOfFirst { it.first == readerMode }.coerceAtLeast(0),
                    onSelect = { onReaderModeChange(READER_MODES[it].first) },
                    enabled = isAdmin
                )
            }
        }

        InsetSwitchRow(
            title = "Báo khi truyện có chương mới",
            subtitle = "Theo dõi các truyện bạn đang đọc hoặc đã đánh dấu yêu thích",
            checked = notifyNewChapters,
            onCheckedChange = onNotifyNewChaptersChange,
            icon = TruyenCloudIcons.Bell,
            iconTint = InsetIconTint.Neutral
        )
        if (notifyNewChapters) {
            InsetRow(
                title = if (isCheckingUpdates) "Đang kiểm tra..." else "Kiểm tra chương mới ngay",
                subtitle = notifyStatusMessage.ifBlank { null },
                icon = TruyenCloudIcons.Refresh,
                iconTint = InsetIconTint.Neutral,
                onClick = if (isCheckingUpdates) null else onCheckUpdatesNow,
                trailing = { if (isCheckingUpdates) RowSpinner() }
            )
        }
    }
}

/** Bước chỉnh cỡ chữ: [−] 18 [+], mỗi nút 48dp, giới hạn 12..32. */
@Composable
private fun FontSizeStepper(fontSize: Int, onFontSizeChange: (Int) -> Unit) {
    val colors = LocalCustomColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        QuietIconButton(
            icon = TruyenCloudIcons.Remove,
            contentDescription = "Giảm cỡ chữ",
            onClick = { onFontSizeChange(fontSize - 1) },
            tint = colors.textPrimary,
            enabled = fontSize > MIN_FONT_SIZE
        )
        Text(
            text = "$fontSize",
            style = AppText.bodyStrong,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 28.dp)
        )
        QuietIconButton(
            icon = TruyenCloudIcons.Add,
            contentDescription = "Tăng cỡ chữ",
            onClick = { onFontSizeChange(fontSize + 1) },
            tint = colors.textPrimary,
            enabled = fontSize < MAX_FONT_SIZE
        )
    }
}
