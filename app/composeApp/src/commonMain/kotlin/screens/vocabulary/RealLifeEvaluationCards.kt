package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont
import com.truyencloud.shared.api.SentenceEvaluateResponse

/** Kết quả AI chấm câu người học: điểm (nhãn có chữ), góp ý, câu đã sửa và cách nói của người bản xứ. */
@Composable
fun EvaluationResultCard(
    evaluation: SentenceEvaluateResponse,
    onSpeak: () -> Unit
) {
    val c = LocalCustomColors.current
    val scoreTone = when {
        evaluation.score >= 8 -> LabelTone.Success
        evaluation.score >= 6 -> LabelTone.Warning
        else -> LabelTone.Error
    }

    FlatCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Kết quả", style = AppText.bodyStrong, color = c.textPrimary)
                StatusLabel(text = "Điểm ${evaluation.score}/10", tone = scoreTone)
            }

            Text(text = evaluation.feedbackVi, style = AppText.body, color = c.textPrimary)

            if (evaluation.correctedSentence.isNotBlank() && evaluation.correctedSentence != evaluation.userSentence) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionLabel("Câu đã sửa ngữ pháp")
                    Text(
                        text = evaluation.correctedSentence,
                        style = AppText.body.copy(fontFamily = ReaderFont),
                        color = c.textPrimary
                    )
                }
            }

            if (evaluation.nativeVersion.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(InputShape)
                        .background(c.cardHigh)
                        .padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("Cách nói của người bản xứ", Modifier.weight(1f))
                        QuietIconButton(TruyenCloudIcons.VolumeUp, "Nghe cách nói của người bản xứ", onSpeak)
                    }
                    Column(
                        modifier = Modifier.padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "“${evaluation.nativeVersion}”",
                            style = AppText.body.copy(fontFamily = ReaderFont),
                            color = c.textPrimary
                        )
                        if (evaluation.nativeExplanationVi.isNotBlank()) {
                            Text(text = evaluation.nativeExplanationVi, style = AppText.secondary, color = c.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

/** Một câu đã luyện trước đó: câu của bạn, điểm và cách nói bản xứ (nếu có). */
@Composable
fun PastSentenceCard(item: SentenceEvaluateResponse) {
    val c = LocalCustomColors.current

    FlatCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "“${item.userSentence}”",
                    style = AppText.secondary.copy(fontFamily = ReaderFont),
                    color = c.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(text = "${item.score}/10", style = AppText.secondaryStrong, color = c.textSecondary)
            }
            if (item.nativeVersion.isNotBlank()) {
                Text(text = "Bản xứ: ${item.nativeVersion}", style = AppText.captionRegular, color = c.textTertiary)
            }
        }
    }
}
