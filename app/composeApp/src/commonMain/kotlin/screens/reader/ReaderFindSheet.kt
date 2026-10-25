package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.SearchField
import com.truyencloud.compose.components.SheetHandle
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.BottomSheetShape
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Thanh tìm trong chương, dính đáy màn hình và nâng lên cùng bàn phím: ô tìm kiểu iOS (kính lúp, nút xoá, chữ "Hủy" để đóng), dòng
 * kết quả và hai nút chuyển kết quả. Ô tìm tự mở bàn phím khi hiện.
 */
@Composable
fun ReaderFindSheet(
    query: String,
    matchCount: Int,
    currentMatch: Int,
    onQueryChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val noMatch = query.isNotBlank() && matchCount == 0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(BottomSheetShape)
            .background(readerSheetColor())
            .navigationBarsPadding()
            .imePadding()
    ) {
        SheetHandle(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 8.dp))
        SearchField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.padding(start = ReaderSheetInset, end = 4.dp),
            placeholder = "Tìm trong chương",
            onSearch = { if (matchCount > 0) onNext() },
            onCancel = onDismiss,
            requestFocusOnShow = true
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = ReaderSheetInset, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when {
                    query.isBlank() -> "Nhập từ khóa để tìm trong nội dung đã tải"
                    noMatch -> "Không tìm thấy trong chương này"
                    else -> "${currentMatch + 1}/$matchCount đoạn khớp"
                },
                style = AppText.secondary,
                color = if (noMatch) colors.warning else colors.textSecondary,
                modifier = Modifier.weight(1f)
            )
            QuietIconButton(TruyenCloudIcons.ExpandLess, "Kết quả trước", onPrevious, enabled = matchCount > 0, tint = colors.accent)
            QuietIconButton(TruyenCloudIcons.ExpandMore, "Kết quả sau", onNext, enabled = matchCount > 0, tint = colors.accent)
        }
    }
}
