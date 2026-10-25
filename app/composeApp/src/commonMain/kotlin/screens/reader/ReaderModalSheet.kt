package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.SheetHandle
import com.truyencloud.compose.theme.BottomSheetShape
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Tờ trượt dạng cửa sổ riêng (ModalBottomSheet: có nút Back hệ thống và vuốt xuống để đóng) cho tra từ, phân tích câu và sổ từ vựng.
 * Cùng diện mạo với [ReaderSheet]: nền [readerSheetColor], bo 24, thanh nắm 36x5dp, tiêu đề 17sp và nút đóng 48dp.
 * SheetState không lộ ra ngoài; gỡ khỏi cây giao diện (hoặc gọi [onDismiss]) để đóng.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderModalSheet(
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    headerActions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalCustomColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        shape = BottomSheetShape,
        containerColor = readerSheetColor(),
        contentColor = colors.textPrimary,
        tonalElevation = 0.dp,
        scrimColor = colors.scrim,
        contentWindowInsets = { WindowInsets(0.dp) },
        dragHandle = { SheetHandle(Modifier.padding(top = 8.dp, bottom = 4.dp)) }
    ) {
        ReaderSheetHeader(title = title, subtitle = subtitle, onDismiss = onDismiss, headerActions = headerActions)
        content()
        Spacer(Modifier.navigationBarsPadding().padding(bottom = 16.dp))
    }
}
