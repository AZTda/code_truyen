package com.truyencloud.compose.screens.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

/** Từ cần phát âm: từ (serif 28), phiên âm, nghĩa và nút nghe giọng chuẩn. */
@Composable
fun PronunciationTargetCard(
    word: String,
    ipa: String,
    meaningVi: String,
    onSpeakWord: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalCustomColors.current

    FlatCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = c.cardHigh,
        contentPadding = PaddingValues(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = word,
                style = AppText.wordTitle.copy(fontSize = 28.sp, lineHeight = 36.sp),
                color = c.textPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = ipa.ifBlank { "/$word/" },
                style = AppText.body,
                color = c.textSecondary,
                textAlign = TextAlign.Center
            )
            if (meaningVi.isNotBlank()) {
                Text(
                    text = meaningVi,
                    style = AppText.secondary,
                    color = c.textSecondary,
                    textAlign = TextAlign.Center
                )
            }
            TonalButton(
                text = "Nghe giọng chuẩn",
                onClick = { onSpeakWord(word) },
                modifier = Modifier.padding(top = 8.dp),
                leadingIcon = TruyenCloudIcons.VolumeUp
            )
        }
    }
}
