package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.SheetHandle
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.BottomSheetShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont

/** Lề ngang trong các tờ trượt của màn đọc (khớp lề nhóm danh sách inset 16dp). */
internal val ReaderSheetInset = 16.dp

/**
 * Màu nền tờ trượt của màn đọc: thấp hơn màu thẻ một bậc (tối: surface, sáng: bg) để thẻ / nhóm / ô nhập bên trong
 * (màu thẻ) nổi lên như bề mặt nâng. Lấy theo chủ đề đọc đang dùng nên sepia, giấy, đen OLED... đều đúng tông.
 */
@Composable
internal fun readerSheetColor(): Color {
    val c = LocalCustomColors.current
    return if (c.isDark) c.surface else c.bg
}

/**
 * Khung chung của các tờ trượt dựng tay trong màn đọc (đặt trong [ReaderSheetBackdrop]): bo 24 hai góc trên, thanh nắm 36x5dp,
 * tiêu đề 17sp SemiBold (+ dòng phụ 13sp), nút đóng 48dp và các hành động tuỳ chọn ở góc phải. Chừa thanh điều hướng hệ thống
 * ở đáy; [withIme] chừa thêm bàn phím cho sheet có ô nhập. Chiều cao do người gọi đặt qua [modifier] (vd. fillMaxHeight(0.72f)).
 *
 * Nội dung không có lề ngang sẵn: mỗi phần tự đặt lề [ReaderSheetInset] (nhóm InsetGroup đã tự thụt 16dp).
 */
@Composable
internal fun ReaderSheet(
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    serifSubtitle: Boolean = false,
    withIme: Boolean = false,
    headerActions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(BottomSheetShape)
            .background(readerSheetColor())
            .navigationBarsPadding()
            .then(if (withIme) Modifier.imePadding() else Modifier)
    ) {
        SheetHandle(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 4.dp))
        ReaderSheetHeader(
            title = title,
            subtitle = subtitle,
            serifSubtitle = serifSubtitle,
            onDismiss = onDismiss,
            headerActions = headerActions
        )
        content()
    }
}

/** Hàng tiêu đề của tờ trượt: tiêu đề 17sp SemiBold (+ dòng phụ 13sp), hành động tuỳ chọn và nút đóng 48dp. */
@Composable
internal fun ReaderSheetHeader(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    serifSubtitle: Boolean = false,
    headerActions: @Composable RowScope.() -> Unit = {}
) {
    val colors = LocalCustomColors.current
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                text = title,
                style = AppText.sectionHeader,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = if (serifSubtitle) AppText.secondary.copy(fontFamily = ReaderFont) else AppText.secondary,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        headerActions()
        QuietIconButton(TruyenCloudIcons.Close, "Đóng", onDismiss)
    }
}
