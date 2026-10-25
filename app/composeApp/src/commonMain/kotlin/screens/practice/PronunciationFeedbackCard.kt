package com.truyencloud.compose.screens.practice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.CardShape
import com.truyencloud.compose.theme.ChipShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.api.PronunciationEvaluateResponse

/** Kết quả chấm phát âm THẬT. Chỉ được gọi khi máy chủ đã chấm một bản chép lời có nội dung (có điểm). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PronunciationFeedbackCard(
    evaluation: PronunciationEvaluateResponse,
    score: Int,
    modifier: Modifier = Modifier
) {
    val c = LocalCustomColors.current
    val good = score >= 80

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        // Điểm: số lớn màu ngữ nghĩa (xanh từ 80 điểm, vàng dưới đó) kèm lời nhận xét
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(if (good) c.successSoft else c.warningSoft)
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        score >= 90 -> "Xuất sắc! Máy nghe đúng từ của bạn"
                        score >= 75 -> "Rất tốt, phát âm rõ ràng"
                        score >= 50 -> "Khá, cần luyện thêm vài âm"
                        else -> "Chưa khớp, hãy nghe mẫu rồi đọc lại"
                    },
                    style = AppText.bodyStrong,
                    color = c.textPrimary
                )
                if (evaluation.feedbackVi.isNotBlank()) {
                    Text(
                        text = evaluation.feedbackVi,
                        style = AppText.secondary,
                        color = c.textSecondary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(start = Spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "$score", style = AppText.screenTitle, color = if (good) c.success else c.warning)
                Text(text = "/100", style = AppText.captionRegular, color = c.textSecondary)
            }
        }

        // Căn chỉnh THẬT giữa từ đích và những gì máy nghe được
        if (evaluation.phonemes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = if (evaluation.alignmentUnit == "word") "Từng từ: đích so với máy nghe" else "Từng chữ cái: đích so với máy nghe",
                    style = AppText.secondaryStrong,
                    color = c.textSecondary,
                    modifier = Modifier.semantics { heading() }
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    evaluation.phonemes.forEach { ph ->
                        val (fill, textColor) = when (ph.status) {
                            "correct" -> c.successSoft to c.success
                            "acceptable" -> c.warningSoft to c.warning
                            else -> c.errorSoft to c.error
                        }
                        Column(
                            modifier = Modifier
                                .widthIn(min = 44.dp)
                                .clip(ChipShape)
                                .background(fill)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(text = ph.symbol, style = AppText.bodyStrong, color = textColor)
                            Text(
                                text = when (ph.op) {
                                    "missing" -> "thiếu"
                                    "sub" -> ph.heard.ifBlank { "?" }
                                    else -> "${ph.score}%"
                                },
                                style = AppText.captionRegular,
                                color = c.textSecondary
                            )
                        }
                    }
                }
            }
        }

        // Âm chuẩn tham khảo (không có điểm)
        if (evaluation.referencePhonemes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Âm chuẩn tham khảo",
                    style = AppText.secondaryStrong,
                    color = c.textSecondary,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    text = evaluation.referencePhonemes.joinToString("  ·  ") { it.symbol },
                    style = AppText.body,
                    color = c.textPrimary
                )
            }
        }

        // Lỗi thường gặp
        if (evaluation.commonMistakes.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CardShape)
                    .background(c.cardHigh)
                    .padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                evaluation.commonMistakes.forEach { mistake ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(text = "•  ", style = AppText.secondary, color = c.textSecondary)
                        Text(text = mistake, style = AppText.secondary, color = c.textPrimary, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
