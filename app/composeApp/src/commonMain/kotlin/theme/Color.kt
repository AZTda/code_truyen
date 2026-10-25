package com.truyencloud.compose.theme

import androidx.compose.ui.graphics.Color

/**
 * Màu "Nordic x iOS": một màu nhấn xanh lạnh, các tầng nền phẳng (bg -> surface -> card -> cardHigh) ngăn bằng
 * đường kẻ mảnh. Giá trị đã đo tương phản WCAG (chữ >= 4.5:1, viền điều khiển >= 3:1); đổi giá trị nào thì chạy lại
 * script đo trước khi lưu.
 *
 * Trong giao diện hãy đọc bảng màu ĐANG DÙNG qua `LocalCustomColors.current` (đổi theo chủ đề). Các hằng `dark*` /
 * `light*` bên dưới chỉ dành cho chỗ không đọc được CompositionLocal (mẫu màu chủ đề, tham số mặc định).
 */
object AppColors {
    // ── Tối: "Đêm Bắc Âu" (gần đen thật cho OLED, thẻ nâng dần như bề mặt iOS) ──
    val darkBg = Color(0xFF07090B)
    val darkSurface = Color(0xFF12161A)
    val darkCard = Color(0xFF181D22)
    val darkCardHigh = Color(0xFF1F252B)
    val darkHairline = Color(0xFF232A31)
    val darkOutline = Color(0xFF66727C)
    val darkTextPrimary = Color(0xFFEAEEF1)
    val darkTextSecondary = Color(0xFFA6B0B8)
    val darkTextTertiary = Color(0xFF8A949D)
    val darkAccent = Color(0xFF5FAEF0)
    val darkAccentStrong = Color(0xFF4A97DB)
    val darkOnAccent = Color(0xFF06121D)
    val darkAccentSoft = Color(0x245FAEF0) // accent 14%
    val darkSuccess = Color(0xFF7BB899)
    val darkWarning = Color(0xFFD8B26E)
    val darkError = Color(0xFFE0857F)

    // ── Sáng: "Tuyết" ──
    // outline / textTertiary / success hơi đậm hơn bản thiết kế (7F8A94 / 65707A / 2F7A55) để đạt ngưỡng trên cardHigh.
    val lightBg = Color(0xFFF2F4F6)
    val lightSurface = Color(0xFFFFFFFF)
    val lightCard = Color(0xFFFFFFFF)
    val lightCardHigh = Color(0xFFE9EDF0)
    val lightHairline = Color(0xFFE1E5E9)
    val lightOutline = Color(0xFF7D8892)
    val lightTextPrimary = Color(0xFF12161A)
    val lightTextSecondary = Color(0xFF4A545D)
    val lightTextTertiary = Color(0xFF606B75)
    val lightAccent = Color(0xFF0A66C2)
    val lightAccentStrong = Color(0xFF0B58A6)
    val lightOnAccent = Color(0xFFFFFFFF)
    val lightAccentSoft = Color(0x1A0A66C2) // accent 10%
    val lightSuccess = Color(0xFF2D7752)
    val lightWarning = Color(0xFF8A6414)
    val lightError = Color(0xFFB3423B)

    // ── Màu chữ đè lên bìa chữ (nền bìa luôn tối nên chữ luôn sáng) ──
    val coverTitle = Color(0xFFF4F6F7)
    val coverSubtitle = Color(0xFFD6DCE0)

    // ── Bìa chữ khi truyện không có ảnh: 10 tông Bắc Âu dịu (vịnh, thông, đá phiến, bạch dương, đất sét, thạch
    // thảo, rêu, mực, hoàng hôn, đá), phẳng hoặc hai tông rất nhẹ. Chữ sáng trên mọi tông đều >= 4.5:1. ──
    val coverPalettes: List<List<Color>> = listOf(
        listOf(Color(0xFF35546B), Color(0xFF2C475C)), // vịnh xanh
        listOf(Color(0xFF2F5A4C), Color(0xFF274B3F)), // thông
        listOf(Color(0xFF3F4B5A), Color(0xFF364250)), // đá phiến
        listOf(Color(0xFF566269), Color(0xFF4B565D)), // bạch dương xám
        listOf(Color(0xFF7A4F42), Color(0xFF6E463A)), // đất sét
        listOf(Color(0xFF5F5473), Color(0xFF554B68)), // thạch thảo
        listOf(Color(0xFF55633F), Color(0xFF4B5837)), // rêu
        listOf(Color(0xFF223040), Color(0xFF1A2634)), // mực
        listOf(Color(0xFF4A4F78), Color(0xFF404568)), // hoàng hôn
        listOf(Color(0xFF645E55), Color(0xFF59534B)), // đá
    )

    // ── Bảng màu hoàn chỉnh của hai chủ đề chính (xem AppPalette) ──
    val dark: AppPalette = AppPalette(
        isDark = true,
        bg = darkBg, surface = darkSurface, card = darkCard, cardHigh = darkCardHigh,
        hairline = darkHairline, outline = darkOutline,
        textPrimary = darkTextPrimary, textSecondary = darkTextSecondary, textTertiary = darkTextTertiary,
        accent = darkAccent, accentStrong = darkAccentStrong, onAccent = darkOnAccent,
        accentSoft = darkAccentSoft, onAccentSoft = darkAccent,
        success = darkSuccess, warning = darkWarning, error = darkError
    )

    val light: AppPalette = AppPalette(
        isDark = false,
        bg = lightBg, surface = lightSurface, card = lightCard, cardHigh = lightCardHigh,
        hairline = lightHairline, outline = lightOutline,
        textPrimary = lightTextPrimary, textSecondary = lightTextSecondary, textTertiary = lightTextTertiary,
        accent = lightAccent, accentStrong = lightAccentStrong, onAccent = lightOnAccent,
        accentSoft = lightAccentSoft, onAccentSoft = lightAccentStrong,
        success = lightSuccess, warning = lightWarning, error = lightError
    )
}
