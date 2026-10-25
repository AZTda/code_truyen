package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.SearchField
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

/**
 * Hành động trên thanh tiêu đề lớn "Từ vựng": tối đa 3 nút (Tìm, Đồng bộ, Thêm). Nhắc ôn và xuất Anki nằm trong
 * [VocabularyMoreSheet] để thanh không chật.
 *
 * @param showSearch chỉ Sổ từ mới có gì để tìm; các chế độ khác ẩn nút.
 */
@Composable
internal fun RowScope.VocabularyHeaderActions(
    showSearch: Boolean,
    isSyncing: Boolean,
    isSyncSuccess: Boolean,
    onSearch: () -> Unit,
    onSyncCloud: () -> Unit,
    onMore: () -> Unit
) {
    val colors = LocalCustomColors.current
    if (showSearch) {
        QuietIconButton(TruyenCloudIcons.Search, "Tìm từ vựng", onSearch)
    }
    if (isSyncing) {
        // Vẫn giữ ô 48dp để thanh không nhảy khi đồng bộ xong
        Box(
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = "Đang đồng bộ từ vựng" },
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        }
    } else {
        QuietIconButton(
            icon = if (isSyncSuccess) TruyenCloudIcons.CloudSyncDone else TruyenCloudIcons.CloudSync,
            contentDescription = if (isSyncSuccess) "Đã đồng bộ từ vựng" else "Đồng bộ từ vựng",
            onClick = onSyncCloud,
            tint = if (isSyncSuccess) colors.success else colors.textSecondary
        )
    }
    QuietIconButton(TruyenCloudIcons.More, "Thêm tùy chọn", onMore)
}

/** Hàng tìm kiếm toàn màn hình thay cho thanh tiêu đề: ô tìm kiểu iOS + "Hủy", chừa thanh trạng thái. */
@Composable
internal fun VocabularySearchHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    SearchField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = Spacing.pageH, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm),
        placeholder = "Tìm từ hoặc nghĩa tiếng Việt",
        onCancel = onCancel,
        requestFocusOnShow = true
    )
}
