package com.truyencloud.compose.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.library.SearchSuggestionKind
import com.truyencloud.shared.library.SearchSuggestionSection

/**
 * Gợi ý khi chưa gõ gì: mỗi nhóm một tiêu đề (13sp Medium chữ phụ) cùng các chip tự xuống dòng. Nội dung do
 * SearchSuggestionBuilder dựng riêng cho tài khoản đang dùng (tìm gần đây, từ tủ sách, gợi ý cho bạn); danh sách mặc định
 * chỉ là phần điền khi tài khoản chưa có nhiều thứ cá nhân. Nhóm "Tìm gần đây" có thêm chữ "Xóa lịch sử".
 */
@Composable
internal fun SearchSuggestions(
    sections: List<SearchSuggestionSection>,
    onSuggestionClick: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        // Cuộn được: tối đa 12 chip chia ba nhóm có tiêu đề, gặp bàn phím mở hoặc màn hình thấp là tràn
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.pageH, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        sections.forEach { section ->
            Column {
                SuggestionHeading(
                    text = section.heading,
                    onClear = if (section.kind == SearchSuggestionKind.RECENT) onClearHistory else null
                )
                // Mỗi chip đã nằm trong ô chạm cao 48dp nên các hàng không cần cách nhau thêm
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    section.chips.forEach { chip ->
                        FilterChip8(label = chip, selected = false, onClick = { onSuggestionClick(chip) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionHeading(text: String, onClear: (() -> Unit)?) {
    Row(
        // Mọi tiêu đề cao tối thiểu như nhau để các nhóm xếp đều nhịp
        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = AppText.groupHeader,
            color = LocalCustomColors.current.textSecondary,
            modifier = Modifier.weight(1f).semantics { heading() }
        )
        if (onClear != null) {
            // Nút chữ có đệm ngang 12dp: dịch sang phải để chữ thẳng với mép phải của các chip
            TextActionButton(text = "Xóa lịch sử", onClick = onClear, modifier = Modifier.offset(x = 12.dp))
        }
    }
}
