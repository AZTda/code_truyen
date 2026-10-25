package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.components.SegmentedControl
import com.truyencloud.compose.theme.LocalCustomColors

private val LineSpacingOptions = listOf(1.5f to "1.5x", 1.8f to "1.8x", 2.2f to "2.2x")
private val PageMarginOptions = listOf(12 to "Gọn", 18 to "Chuẩn", 26 to "Rộng")

/** Cỡ chữ, chế độ đọc (cuộn dọc / lật trang), giãn dòng và lề trang. Mọi thay đổi áp dụng ngay để xem trước trên trang phía sau. */
@Composable
internal fun ReaderTypographySection(
    fontSize: Int,
    isPaginatedMode: Boolean,
    lineSpacingFactor: Float,
    pageMargin: Int,
    onFontSizeChange: (Int) -> Unit,
    onTogglePaginatedMode: (Boolean) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onPageMarginChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    // Giá trị lưu có thể nằm giữa các mức (lề mặc định 16, giãn dòng 2.0...): chọn mức GẦN NHẤT để luôn có một ô sáng
    val spacingIndex = LineSpacingOptions.indices.minByOrNull { kotlin.math.abs(lineSpacingFactor - LineSpacingOptions[it].first) } ?: 0
    val marginIndex = PageMarginOptions.indices.minByOrNull { kotlin.math.abs(pageMargin - PageMarginOptions[it].first) } ?: 0

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = ReaderSheetInset),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        ReaderSettingBlock(label = "Cỡ chữ", trailing = "$fontSize") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Hai chữ A chỉ minh hoạ nhỏ - lớn, bỏ khỏi cây hỗ trợ tiếp cận (thanh trượt đã có mô tả riêng)
                Text("A", fontSize = 13.sp, color = colors.textSecondary, modifier = Modifier.clearAndSetSemantics { })
                Slider(
                    value = fontSize.toFloat(),
                    onValueChange = { onFontSizeChange(it.toInt()) },
                    valueRange = 14f..30f,
                    steps = 15,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.outline.copy(alpha = 0.5f),
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .semantics { contentDescription = "Cỡ chữ" }
                )
                Text("A", fontSize = 24.sp, color = colors.textSecondary, modifier = Modifier.clearAndSetSemantics { })
            }
        }
        ReaderSettingBlock(label = "Chế độ đọc") {
            SegmentedControl(
                options = listOf("Cuộn dọc", "Lật trang"),
                selectedIndex = if (isPaginatedMode) 1 else 0,
                onSelect = { onTogglePaginatedMode(it == 1) }
            )
        }
        ReaderSettingBlock(label = "Giãn dòng") {
            SegmentedControl(
                options = LineSpacingOptions.map { it.second },
                selectedIndex = spacingIndex,
                onSelect = { onLineSpacingChange(LineSpacingOptions[it].first) }
            )
        }
        ReaderSettingBlock(label = "Lề trang") {
            SegmentedControl(
                options = PageMarginOptions.map { it.second },
                selectedIndex = marginIndex,
                onSelect = { onPageMarginChange(PageMarginOptions[it].first) }
            )
        }
    }
}
