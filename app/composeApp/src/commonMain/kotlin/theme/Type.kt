package com.truyencloud.compose.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Chữ đọc truyện và TÊN SÁCH / CHƯƠNG. Không dùng serif cho nhãn giao diện. */
val ReaderFont = FontFamily.Serif

/** Phông giao diện: San Francisco trên iOS, Roboto trên Android (phông mặc định của hệ thống). */
val UIFont = FontFamily.Default

/**
 * Các kiểu chữ có tên của thiết kế (sp / dòng). Giãn chữ luôn 0, không in hoa nhãn nhỏ: viết hoa chữ đầu câu.
 * Dùng: `Text("Tủ sách", style = AppText.largeTitle, color = colors.textPrimary)`.
 */
object AppText {
    /** Tiêu đề lớn của 5 tab chính; thu về [largeTitleCollapsed] khi cuộn. */
    val largeTitle = TextStyle(fontFamily = UIFont, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = 0.sp)
    val largeTitleCollapsed = TextStyle(fontFamily = UIFont, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.sp)
    val screenTitle = TextStyle(fontFamily = UIFont, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = 0.sp)
    val sectionHeader = TextStyle(fontFamily = UIFont, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.sp)
    val body = TextStyle(fontFamily = UIFont, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp)
    val bodyStrong = TextStyle(fontFamily = UIFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp)
    val secondary = TextStyle(fontFamily = UIFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
    val secondaryStrong = TextStyle(fontFamily = UIFont, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
    val caption = TextStyle(fontFamily = UIFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp)
    val captionRegular = TextStyle(fontFamily = UIFont, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp)
    val tabLabel = TextStyle(fontFamily = UIFont, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp)
    val button = TextStyle(fontFamily = UIFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp)

    /** Nhóm trong danh sách inset: tiêu đề 13sp Medium, chú thích 12sp. */
    val groupHeader = secondaryStrong
    val groupFooter = captionRegular

    /** Tên sách trên thẻ: serif SemiBold 15/20 (tối đa 2 dòng). */
    val bookTitle = TextStyle(fontFamily = ReaderFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp)
    val bookTitleCompact = TextStyle(fontFamily = ReaderFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
    val author = captionRegular

    /** Từ vựng trong danh sách: serif 18 SemiBold. */
    val wordTitle = TextStyle(fontFamily = ReaderFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp)
}

/**
 * Typography của Material ánh xạ từ [AppText]. `bodyLarge` là kiểu mặc định của mọi `Text` trần nên dùng phông
 * giao diện (không còn serif) và giãn dòng theo em: Text chỉ đổi fontSize vẫn có giãn dòng cân đối với cỡ chữ của nó.
 */
val AppTypography = Typography(
    displayLarge = AppText.largeTitle,
    displayMedium = AppText.largeTitle.copy(fontSize = 28.sp, lineHeight = 34.sp),
    displaySmall = AppText.screenTitle,
    headlineLarge = AppText.screenTitle,
    headlineMedium = AppText.screenTitle.copy(fontSize = 20.sp, lineHeight = 26.sp),
    headlineSmall = AppText.sectionHeader,
    titleLarge = AppText.sectionHeader,
    titleMedium = AppText.body.copy(fontWeight = FontWeight.Medium),
    titleSmall = AppText.secondaryStrong,
    bodyLarge = AppText.body.copy(lineHeight = 1.47.em),
    bodyMedium = AppText.secondary.copy(lineHeight = 1.38.em),
    bodySmall = AppText.captionRegular.copy(lineHeight = 1.33.em),
    labelLarge = AppText.button,
    labelMedium = AppText.caption,
    labelSmall = AppText.tabLabel
)
