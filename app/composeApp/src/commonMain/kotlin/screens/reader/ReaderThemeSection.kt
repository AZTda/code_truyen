package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.ReaderThemePicker
import com.truyencloud.compose.components.SegmentedControl
import com.truyencloud.compose.components.ThemeModeControl

/**
 * Giao diện của màn đọc: chế độ Hệ thống / Sáng / Tối của ứng dụng, năm nền đọc (Đen OLED, Than chì, Giấy, Sepia, Rừng thông) và kiểu chữ.
 * Chế độ và nền đọc cùng ghi vào một giá trị `themeMode` đã lưu: chọn một nền đọc thì chế độ hiện "Sáng" hoặc "Tối" theo độ sáng của nền đó.
 */
@Composable
internal fun ReaderThemeSection(
    themeMode: String,
    fontFamilyType: String,
    onThemeChange: (String) -> Unit,
    onFontFamilyChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = ReaderSheetInset),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        ReaderSettingBlock(label = "Giao diện") {
            ThemeModeControl(storedTheme = themeMode, onSelect = { onThemeChange(it.stored) })
        }
        ReaderSettingBlock(label = "Nền đọc") {
            ReaderThemePicker(selectedMode = themeMode, onSelect = onThemeChange)
        }
        ReaderSettingBlock(label = "Kiểu chữ") {
            SegmentedControl(
                options = listOf("Có chân", "Không chân"),
                selectedIndex = if (fontFamilyType == "serif") 0 else 1,
                onSelect = { onFontFamilyChange(if (it == 0) "serif" else "sans") }
            )
        }
    }
}
