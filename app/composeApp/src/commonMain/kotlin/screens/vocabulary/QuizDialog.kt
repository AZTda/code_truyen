package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.IosSheet
import com.truyencloud.compose.components.NoticeBanner
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

/**
 * Quiz tình huống đời sống, mở dưới dạng tờ trượt từ dưới lên.
 *
 * Endpoint `/v1/english/practice-quiz` đã tồn tại từ trước nhưng KHÔNG hề được
 * gọi ở bất kỳ đâu trong ứng dụng; đây là phần giao diện nối lại tính năng đó.
 */
@Composable
fun QuizDialog(
    uiState: VocabularyUiState,
    onAnswer: (Int) -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val c = LocalCustomColors.current

    IosSheet(onDismissRequest = onDismiss, title = "Quiz tình huống") {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.pageH),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            val error = uiState.quizError
            val question = uiState.quizQuestions.getOrNull(uiState.quizIndex)
            when {
                uiState.isLoadingQuiz -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Spacing.md))
                    Text(text = "Đang dựng tình huống từ vốn từ của bạn...", style = AppText.secondary, color = c.textSecondary)
                }

                error != null -> {
                    NoticeBanner(text = error, tone = NoticeTone.Error)
                    PrimaryButton(text = "Thử lại", onClick = onRetry, modifier = Modifier.fillMaxWidth())
                }

                uiState.quizQuestions.isEmpty() -> {
                    Text(text = "Chưa có câu hỏi nào.", style = AppText.body, color = c.textSecondary)
                }

                question == null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(TruyenCloudIcons.CheckCircle, contentDescription = null, tint = c.success, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = "Hoàn thành! Đúng ${uiState.quizCorrectCount}/${uiState.quizQuestions.size} câu.",
                            style = AppText.sectionHeader,
                            color = c.textPrimary
                        )
                    }
                    PrimaryButton(text = "Đóng", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                }

                else -> QuizQuestionBody(uiState, question, onAnswer, onNext)
            }
            Spacer(Modifier.height(Spacing.xs))
        }
    }
}

@Composable
private fun QuizQuestionBody(
    uiState: VocabularyUiState,
    q: com.truyencloud.shared.api.QuizQuestion,
    onAnswer: (Int) -> Unit,
    onNext: () -> Unit
) {
    val c = LocalCustomColors.current
    val answered = uiState.quizSelectedOption != null

    Text(
        text = "Câu ${uiState.quizIndex + 1}/${uiState.quizQuestions.size}",
        style = AppText.secondary,
        color = c.textSecondary
    )
    Text(
        text = q.scenario,
        style = AppText.body,
        color = c.textPrimary,
        modifier = Modifier.fillMaxWidth().clip(InputShape).background(c.cardHigh).padding(Spacing.md)
    )
    Text(text = q.question, style = AppText.sectionHeader, color = c.textPrimary)

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        q.options.forEachIndexed { idx, option ->
            val state = when {
                answered && idx == q.correctAnswerIndex -> AnswerState.Correct
                answered && uiState.quizSelectedOption == idx -> AnswerState.Wrong
                else -> AnswerState.Idle
            }
            AnswerTile(text = option, state = state, enabled = !answered, onClick = { onAnswer(idx) })
        }
    }

    if (answered) {
        if (q.explanationVi.isNotBlank()) {
            Text(text = q.explanationVi, style = AppText.secondary, color = c.textSecondary)
        }
        PrimaryButton(
            text = if (uiState.quizIndex + 1 >= uiState.quizQuestions.size) "Xem kết quả" else "Câu tiếp theo",
            onClick = onNext,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
