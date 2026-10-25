package com.truyencloud.compose.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.truyencloud.compose.theme.ThemeMode

/**
 * Ô chọn chế độ giao diện Hệ thống / Sáng / Tối cho Cài đặt và màn đọc: một [SegmentedControl] đã nối với [ThemeMode].
 * [storedTheme] là chuỗi đang lưu trong `UserProfile.preferredTheme` (giá trị cũ "oled", "sepia"... được xếp về Sáng / Tối);
 * [onSelect] nhận chế độ mới, lưu bằng `mode.stored`.
 */
@Composable
fun ThemeModeControl(
    storedTheme: String,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = ThemeMode.options
    SegmentedControl(
        options = modes.map { it.label },
        selectedIndex = ThemeMode.fromStored(storedTheme).ordinal,
        onSelect = { onSelect(modes[it]) },
        modifier = modifier
    )
}
