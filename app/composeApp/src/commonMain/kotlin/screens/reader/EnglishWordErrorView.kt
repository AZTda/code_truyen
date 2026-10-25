package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont
import com.truyencloud.compose.theme.Spacing

/** Tra từ lỗi: hiện từ, lý do thật, câu trong truyện và hai nút "Thử lại" / "Phát âm". Không bao giờ hiện nghĩa suy đoán. */
@Composable
fun EnglishWordErrorView(
    word: String,
    errorMessage: String?,
    contextSentence: String,
    onRetry: () -> Unit,
    onSpeakWord: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current

    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = word, style = AppText.wordTitle, color = colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = errorMessage ?: "Chưa tra được từ này.",
            style = AppText.secondary,
            color = colors.error,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Không hiển thị dữ liệu suy đoán để bạn không ghi nhớ nhầm.",
            style = AppText.captionRegular,
            color = colors.textSecondary,
            textAlign = TextAlign.Center
        )

        if (contextSentence.isNotBlank()) {
            Spacer(modifier = Modifier.height(Spacing.lg))
            Text(
                text = "“$contextSentence”",
                style = AppText.body.copy(fontFamily = ReaderFont, fontStyle = FontStyle.Italic),
                color = colors.textPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(InputShape)
                    .background(colors.card)
                    .padding(12.dp)
            )
        }

        Spacer(modifier = Modifier.height(Spacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PrimaryButton(text = "Thử lại", onClick = onRetry, modifier = Modifier.weight(1f), leadingIcon = TruyenCloudIcons.Refresh)
            TonalButton(
                text = "Phát âm",
                onClick = { onSpeakWord(word) },
                modifier = Modifier.weight(1f),
                leadingIcon = TruyenCloudIcons.VolumeUp
            )
        }
    }
}
