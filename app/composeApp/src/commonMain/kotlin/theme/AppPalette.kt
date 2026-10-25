package com.truyencloud.compose.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Bảng màu ngữ nghĩa của MỘT chủ đề (tối, sáng hoặc một chủ đề đọc sách). Lấy bản đang dùng bằng
 * `LocalCustomColors.current`; mọi thành phần đều đọc màu từ đây thay vì tô cứng hex.
 *
 * Tầng nền (thấp -> cao): [bg] -> [surface] -> [card] -> [cardHigh]. Tách lớp bằng bước màu và [hairline],
 * không viền màu, không đổ bóng. [outline] chỉ dành cho viền điều khiển (ô nhập, checkbox) và đạt >= 3:1.
 */
@Immutable
class AppPalette(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val card: Color,
    val cardHigh: Color,
    /** Đường kẻ mảnh 1dp giữa các hàng / quanh thẻ khi bước màu quá nhỏ. Chỉ để trang trí, không dùng cho điều khiển. */
    val hairline: Color,
    /** Viền điều khiển (>= 3:1 trên mọi tầng nền). */
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** Màu nhấn DUY NHẤT: hành động chính, trạng thái chọn, tiến độ, liên kết. */
    val accent: Color,
    /** Bản đậm hơn của [accent]: trạng thái nhấn xuống, chữ trên [accentSoft] của giao diện sáng. */
    val accentStrong: Color,
    /** Chữ / biểu tượng đặt TRÊN nền [accent]. */
    val onAccent: Color,
    /** Nền tô nhẹ (accent ở 10-14% độ đục) cho chip chọn, ô biểu tượng, nút phụ. Có alpha: đặt trên nền đặc. */
    val accentSoft: Color,
    /** Chữ / biểu tượng đặt TRÊN [accentSoft]. */
    val onAccentSoft: Color,
    val success: Color,
    val warning: Color,
    val error: Color
) {
    private val semanticSoftAlpha: Float get() = if (isDark) 0.16f else 0.12f
    val successSoft: Color get() = success.copy(alpha = semanticSoftAlpha)
    val warningSoft: Color get() = warning.copy(alpha = semanticSoftAlpha)
    val errorSoft: Color get() = error.copy(alpha = semanticSoftAlpha)

    /** Rãnh của SegmentedControl: màu thẻ (giao diện sáng: cardHigh để tách khỏi nền trắng). */
    val segmentTrack: Color get() = if (isDark) card else cardHigh

    /** Viên chọn của SegmentedControl: cardHigh (giao diện sáng: màu thẻ, tức trắng). */
    val segmentThumb: Color get() = if (isDark) cardHigh else card

    /** Lớp phủ mờ sau sheet / hộp thoại. */
    val scrim: Color get() = Color.Black.copy(alpha = if (isDark) 0.6f else 0.4f)
}

/** Bảng màu của chủ đề đang dùng; do [AppTheme] cung cấp. Mặc định là chủ đề tối. */
val LocalCustomColors = staticCompositionLocalOf { AppColors.dark }
