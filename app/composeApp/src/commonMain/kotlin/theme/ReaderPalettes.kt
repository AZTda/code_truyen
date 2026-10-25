package com.truyencloud.compose.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Bảng màu đầy đủ của các chủ đề đọc sách. Nền + chữ chính giữ đúng bảng cũ; các tầng surface / card / chữ phụ
 * suy ra từ đó và đã đo tương phản. Màu nhấn và màu ngữ nghĩa lấy từ chủ đề tối hoặc sáng gần nhất.
 */
private fun readerPalette(
    base: AppPalette,
    bg: Color,
    surface: Color,
    card: Color,
    cardHigh: Color,
    hairline: Color,
    outline: Color,
    textPrimary: Color,
    textSecondary: Color,
    textTertiary: Color,
    // Nền giấy ấm có tầng cardHigh đậm hơn trắng nên màu nhấn / ngữ nghĩa phải đậm hơn một chút mới đạt 4.5:1
    accent: Color = base.accent,
    accentStrong: Color = base.accentStrong,
    success: Color = base.success,
    warning: Color = base.warning,
    error: Color = base.error
) = AppPalette(
    isDark = base.isDark,
    bg = bg, surface = surface, card = card, cardHigh = cardHigh,
    hairline = hairline, outline = outline,
    textPrimary = textPrimary, textSecondary = textSecondary, textTertiary = textTertiary,
    accent = accent, accentStrong = accentStrong, onAccent = base.onAccent,
    accentSoft = base.accentSoft, onAccentSoft = if (base.isDark) accent else accentStrong,
    success = success, warning = warning, error = error
)

object ReaderPalettes {
    /** Đen OLED: nền đen tuyệt đối để tắt điểm ảnh. */
    val oled: AppPalette = readerPalette(
        base = AppColors.dark,
        bg = Color(0xFF000000), surface = Color(0xFF0B0D0F), card = Color(0xFF111417), cardHigh = Color(0xFF181C20),
        hairline = Color(0xFF21262B), outline = Color(0xFF66727C),
        textPrimary = Color(0xFFDDD9D0), textSecondary = Color(0xFF9AA2AC), textTertiary = Color(0xFF7F8791)
    )

    /** Than chì: xanh đá phiến đậm. */
    val slate: AppPalette = readerPalette(
        base = AppColors.dark,
        bg = Color(0xFF13161C), surface = Color(0xFF191D24), card = Color(0xFF20252E), cardHigh = Color(0xFF262C36),
        hairline = Color(0xFF2D3441), outline = Color(0xFF6C7889),
        textPrimary = Color(0xFFD4D8E0), textSecondary = Color(0xFF9CA5B4), textTertiary = Color(0xFF9099A9)
    )

    /** Rừng thông: xanh lục gần đen. */
    val forest: AppPalette = readerPalette(
        base = AppColors.dark,
        bg = Color(0xFF0C1411), surface = Color(0xFF111B17), card = Color(0xFF16231D), cardHigh = Color(0xFF1C2D26),
        hairline = Color(0xFF253A31), outline = Color(0xFF668477),
        textPrimary = Color(0xFFCFE0D7), textSecondary = Color(0xFF9DB5A8), textTertiary = Color(0xFF86A294)
    )

    /** Sepia: giấy ngà ấm. */
    val sepia: AppPalette = readerPalette(
        base = AppColors.light,
        bg = Color(0xFFF4EDE2), surface = Color(0xFFFBF7EE), card = Color(0xFFFBF7EE), cardHigh = Color(0xFFE9DFCB),
        hairline = Color(0xFFDFD6C2), outline = Color(0xFF7D6F58),
        textPrimary = Color(0xFF332A1F), textSecondary = Color(0xFF5F5342), textTertiary = Color(0xFF6B5E4B),
        accent = Color(0xFF0A63BC), accentStrong = Color(0xFF0B55A1),
        success = Color(0xFF296D4B), warning = Color(0xFF7F5C12), error = Color(0xFFA53D36)
    )

    /** Giấy: giấy da sáng, gần trắng. */
    val parchment: AppPalette = readerPalette(
        base = AppColors.light,
        bg = Color(0xFFFAF6EE), surface = Color(0xFFFFFDF8), card = Color(0xFFFFFDF8), cardHigh = Color(0xFFEFE8D8),
        hairline = Color(0xFFE5DDCB), outline = Color(0xFF7A6E5A),
        textPrimary = Color(0xFF25211B), textSecondary = Color(0xFF5A5245), textTertiary = Color(0xFF675E4E),
        success = Color(0xFF2B724F), warning = Color(0xFF846013), error = Color(0xFFAC3F39)
    )
}

/** Một chủ đề đọc sách để vẽ ô chọn: [mode] là giá trị lưu / truyền vào [AppTheme]. */
@Immutable
class ReaderThemeOption(val mode: String, val label: String, val palette: AppPalette) {
    val bg: Color get() = palette.bg
    val text: Color get() = palette.textPrimary
}

object ReaderThemes {
    /** Năm chủ đề đọc sách theo thứ tự hiển thị. */
    val all: List<ReaderThemeOption> = listOf(
        ReaderThemeOption("oled", "Đen OLED", ReaderPalettes.oled),
        ReaderThemeOption("slate", "Than chì", ReaderPalettes.slate),
        ReaderThemeOption("parchment", "Giấy", ReaderPalettes.parchment),
        ReaderThemeOption("sepia", "Sepia", ReaderPalettes.sepia),
        ReaderThemeOption("forest", "Rừng thông", ReaderPalettes.forest)
    )
}
