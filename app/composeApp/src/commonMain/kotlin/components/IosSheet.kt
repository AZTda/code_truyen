package com.truyencloud.compose.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.BottomSheetShape
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Tờ trượt từ dưới lên kiểu iOS: màu thẻ, bo 24 ở hai góc trên, thanh nắm 36x5dp ở giữa, tiêu đề tuỳ chọn 17sp SemiBold
 * căn giữa. Nội dung được chừa thanh điều hướng hệ thống ở đáy.
 *
 * @param skipPartiallyExpanded true (mặc định) = mở thẳng lên chiều cao nội dung, không dừng ở nửa chừng.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IosSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    skipPartiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    // SheetState là API thử nghiệm của Material nên không lộ ra chữ ký công khai: người gọi không phải @OptIn
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)
    val colors = LocalCustomColors.current
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = BottomSheetShape,
        containerColor = colors.card,
        contentColor = colors.textPrimary,
        tonalElevation = 0.dp,
        scrimColor = colors.scrim,
        contentWindowInsets = { WindowInsets(0.dp) },
        dragHandle = { SheetHandle(Modifier.padding(top = 8.dp, bottom = 12.dp)) }
    ) {
        if (title != null) {
            Text(
                text = title,
                style = AppText.sectionHeader,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
                    .semantics { heading() }
            )
        }
        content()
        Spacer(Modifier.navigationBarsPadding().padding(bottom = 16.dp))
    }
}
