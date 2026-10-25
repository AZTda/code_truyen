package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

/**
 * Thẻ cuối chương: "Hết chương N", tên chương, nút chính sang chương kế (hoặc khối "hết truyện" ở chương cuối) và hai nút phụ
 * (chương trước, tóm tắt chương). Thẻ phẳng màu thẻ, không viền màu, không gradient.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderEndOfChapterCard(
    currentChapterNumber: Int,
    title: String,
    contentFont: FontFamily,
    onNextChapter: () -> Unit,
    onPrevChapter: () -> Unit,
    onOpenRecap: () -> Unit,
    endOfBook: EndOfBookUi = EndOfBookUi(),
    onCheckNewChapters: () -> Unit = {},
    onEnableAlerts: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current

    FlatCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(Spacing.lg)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Hết chương $currentChapterNumber",
                style = AppText.secondaryStrong,
                color = colors.textSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontFamily = contentFont,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(Spacing.md))

            if (endOfBook.isLastChapter) {
                // Chương cuối đã biết: không mời sang "Chương N+1" (chưa tồn tại) mà hỏi chương mới / thông báo
                ReaderEndOfBookPanel(endOfBook, onCheckNewChapters, onEnableAlerts)
            } else {
                PrimaryButton(
                    text = "Đọc chương ${currentChapterNumber + 1}",
                    onClick = onNextChapter,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(Spacing.xs))

            // Hai việc phụ nằm trong một hàng nút chữ yên tĩnh (tự xuống dòng khi chữ lớn)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.Center
            ) {
                if (currentChapterNumber > 1) {
                    TextActionButton(text = "Chương ${currentChapterNumber - 1}", onClick = onPrevChapter)
                }
                TextActionButton(text = "Tóm tắt chương", onClick = onOpenRecap)
            }
        }
    }
}
