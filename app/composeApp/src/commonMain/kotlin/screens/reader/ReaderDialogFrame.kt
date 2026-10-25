package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.HairlineDivider
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.DialogShape
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Khung hộp thoại dài của màn đọc (tạo / sửa dấu trang, dịch lại bằng AI): tiêu đề + nút đóng cố định ở trên, thân cuộn
 * được ở giữa, hàng nút GHIM ở đáy — nút chính luôn thấy dù chữ phóng 1.3 lần, màn thấp hay bàn phím đang mở (cửa sổ Dialog
 * co lại theo bàn phím nên thân tự cuộn). Bo [DialogShape], nền cardHigh đặc, lề 24dp quanh thẻ.
 *
 * [header] nằm bên trái nút đóng (dùng `Modifier.weight(1f)` cho phần chữ); [actions] là các nút trong một hàng, chia đều
 * bằng `weight`. Đường kẻ mảnh trên hàng nút chỉ hiện khi thân thực sự bị tràn (đang có phần bị cuộn khuất).
 */
@Composable
internal fun ReaderDialogFrame(
    onDismiss: () -> Unit,
    maxWidth: Dp,
    header: @Composable RowScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalCustomColors.current
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .padding(24.dp)
            .widthIn(max = maxWidth)
            .fillMaxWidth()
            .clip(DialogShape)
            .background(colors.cardHigh)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            header()
            QuietIconButton(TruyenCloudIcons.Close, "Đóng", onDismiss)
        }
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(scroll)
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
            content = content
        )
        // maxValue = Int.MAX_VALUE cho tới khi đo xong: chỉ vẽ đường kẻ khi đã biết chắc thân bị tràn
        if (scroll.maxValue in 1 until Int.MAX_VALUE) HairlineDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = actions
        )
    }
}
