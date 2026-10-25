package com.truyencloud.compose.screens.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.CardShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.SavedWord

/** Thẻ ôn từ lật hai mặt: mặt trước là từ + phiên âm + câu ví dụ che từ; mặt sau là nghĩa trong ngữ cảnh và gốc từ. Chạm thẻ để lật. */
@Composable
fun VocabularyFlipCard(
    currentWord: SavedWord,
    isFlipped: Boolean,
    onToggleFlip: () -> Unit,
    onSpeakWord: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val rotation by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(durationMillis = 350)
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 12f * density
            }
            .clip(CardShape)
            .background(colors.card)
            .border(1.dp, colors.hairline, CardShape)
            .clickable(role = Role.Button, onClickLabel = if (isFlipped) "Xem mặt trước" else "Xem đáp án", onClick = onToggleFlip)
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (rotation < 90f) {
            // Mặt trước: từ + phiên âm + loa + câu ví dụ che từ
            Text(
                text = currentWord.word,
                style = AppText.wordTitle.copy(fontSize = 30.sp, lineHeight = 36.sp),
                color = colors.textPrimary,
                textAlign = TextAlign.Center
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (currentWord.ipaUs.isNotBlank()) {
                    Text(text = currentWord.ipaUs, style = AppText.secondary, color = colors.textSecondary)
                }
                QuietIconButton(
                    icon = TruyenCloudIcons.VolumeUp,
                    contentDescription = "Phát âm",
                    onClick = { onSpeakWord(currentWord.word) },
                    tint = colors.accent,
                    iconSize = 20.dp
                )
            }

            if (currentWord.contextSentence.isNotBlank()) {
                Spacer(modifier = Modifier.height(Spacing.md))
                val maskedSentence = currentWord.contextSentence.replace(
                    Regex("(?i)\\b" + Regex.escape(currentWord.word) + "\\b"),
                    "________"
                )
                Text(
                    text = "“$maskedSentence”",
                    style = AppText.body.copy(fontFamily = ReaderFont, fontStyle = FontStyle.Italic),
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(Spacing.md))
            Text(text = "Chạm vào thẻ để xem đáp án", style = AppText.captionRegular, color = colors.textTertiary)
        } else {
            // Mặt sau: nghĩa trong ngữ cảnh + định nghĩa + gốc từ. Quay ngược 180° để chữ không bị lật gương
            Column(
                modifier = Modifier.graphicsLayer { rotationY = 180f },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = currentWord.contextMeaningVi,
                    style = AppText.sectionHeader.copy(fontSize = 20.sp, lineHeight = 26.sp),
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center
                )

                if (currentWord.englishDefinition.isNotBlank()) {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text(
                        text = currentWord.englishDefinition,
                        style = AppText.secondary.copy(fontStyle = FontStyle.Italic),
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }

                if (currentWord.etymology.isNotBlank()) {
                    Spacer(modifier = Modifier.height(Spacing.md))
                    Text(
                        text = "Gốc từ: ${currentWord.etymology.take(90)}…",
                        style = AppText.captionRegular,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = Spacing.sm)
                    )
                }
            }
        }
    }
}
