package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.NoticeBanner
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.components.icons.TruyenCloudIcons

/** Thông báo của bảng thuật ngữ: không tải được bảng máy chủ (vàng) và kết quả lần thêm / sửa gần nhất (có nút đóng). */
@Composable
internal fun GlossaryStatusLines(state: GlossaryUiState, onDismissMessage: () -> Unit) {
    if (state.serverNotice == null && state.message == null) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        state.serverNotice?.let { NoticeBanner(text = it, tone = NoticeTone.Warning) }
        state.message?.let { NoticeBanner(text = it, tone = NoticeTone.Info, onDismiss = onDismissMessage) }
    }
}

@Composable
internal fun GlossarySearchField(query: String, onQueryChange: (String) -> Unit) {
    ReaderInputField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = "Tìm nguyên văn hoặc cách dịch…",
        leadingIcon = TruyenCloudIcons.Search,
        clearable = true
    )
}
