package com.truyencloud.compose.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

/**
 * Chọn bảng màu từ chuỗi chủ đề đã lưu. "system" (mặc định) và mọi giá trị lạ theo chế độ sáng / tối của hệ điều
 * hành; "dark" / "light" cố định; "oled" / "slate" / "forest" / "sepia" / "parchment" là chủ đề đọc sách.
 */
fun resolvePalette(themeMode: String, systemDark: Boolean): AppPalette = when (themeMode) {
    "dark" -> AppColors.dark
    "light" -> AppColors.light
    "oled" -> ReaderPalettes.oled
    "slate" -> ReaderPalettes.slate
    "forest" -> ReaderPalettes.forest
    "sepia" -> ReaderPalettes.sepia
    "parchment" -> ReaderPalettes.parchment
    else -> if (systemDark) AppColors.dark else AppColors.light
}

/**
 * Gói giao diện Nordic cho cả cây Compose: bảng màu qua [LocalCustomColors], Material color scheme / typography /
 * shapes tương ứng, và màu chữ mặc định (Text không đặt màu vẫn đọc được).
 *
 * @param themeMode chuỗi lưu trong `UserProfile.preferredTheme` hoặc [ThemeMode.stored]; xem [resolvePalette].
 */
@Composable
fun AppTheme(
    themeMode: String = ThemeMode.DEFAULT_STORED,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val palette = remember(themeMode, systemDark) { resolvePalette(themeMode, systemDark) }
    val colorScheme = remember(palette) { palette.toMaterialColorScheme() }

    CompositionLocalProvider(
        LocalCustomColors provides palette,
        LocalContentColor provides palette.textPrimary
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
