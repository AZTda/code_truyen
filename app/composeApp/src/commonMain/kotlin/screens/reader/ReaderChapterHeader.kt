package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.components.CappedFontScale
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

/** Nhãn nút ngôn ngữ dưới tên chương: chế độ đọc hiện tại (Tiếng Việt / Song ngữ / Tiếng Anh C1 / Hòa trộn). */
internal fun readerLanguageLabel(
    isBilingualMode: Boolean,
    isEnglishMode: Boolean,
    englishLevel: String,
    isProgressiveMode: Boolean = false
): String = when {
    isBilingualMode -> "Song ngữ"
    isEnglishMode -> "Tiếng Anh ${englishLevel.uppercase()}"
    isProgressiveMode -> "Hòa trộn"
    else -> "Tiếng Việt"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderChapterHeader(
    title: String,
    fontSize: Int,
    contentFont: FontFamily,
    isBilingualMode: Boolean,
    isEnglishMode: Boolean,
    englishLevel: String,
    onOpenRecap: () -> Unit,
    onToggleEnglishMode: () -> Unit,
    onToggleBilingualMode: () -> Unit,
    modifier: Modifier = Modifier,
    isProgressiveMode: Boolean = false
) {
    val colors = LocalCustomColors.current

    Column(modifier = modifier) {
        Text(
            text = title,
            color = colors.textPrimary,
            fontSize = (fontSize + 5).sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = contentFont,
            lineHeight = (fontSize + 11).sp,
            letterSpacing = 0.2.sp,
            modifier = Modifier.padding(bottom = Spacing.xs)
        )

        // Một hàng nút chữ yên tĩnh: chữ phụ, đổi sang màu nhấn khi chế độ ngôn ngữ đang bật. Dịch sang trái 12dp
        // (đệm ngang của nút) để chữ thẳng hàng với mép tên chương. Giới hạn phóng chữ để hàng không vượt quá phần
        // chỗ mà bộ chia trang đã trừ cho đầu chương (ReaderPagingEngine: 72dp).
        val inEnglish = isBilingualMode || isEnglishMode || isProgressiveMode
        CappedFontScale(max = 1.15f) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().offset(x = (-12).dp),
                verticalArrangement = Arrangement.Center
            ) {
                TextActionButton(text = "Tóm tắt chương", onClick = onOpenRecap, color = colors.textSecondary)
                TextActionButton(
                    text = readerLanguageLabel(isBilingualMode, isEnglishMode, englishLevel, isProgressiveMode),
                    onClick = {
                        if (isBilingualMode) {
                            onToggleEnglishMode()
                        } else if (isEnglishMode) {
                            onToggleBilingualMode()
                        } else {
                            onToggleEnglishMode()
                        }
                    },
                    color = if (inEnglish) colors.accent else colors.textSecondary
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
    }
}
