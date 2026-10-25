package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.api.ComprehensionQuestionItem
import com.truyencloud.shared.reader.AiErrors

/**
 * Trắc nghiệm đọc hiểu. [errorMessage] khác null (máy chủ chỉ tạo được bộ câu hỏi mẫu, hoặc gọi lỗi) thì hiện thẻ lỗi kèm
 * nút [onReload] thay vì bộ câu hỏi chung chung.
 */
@Composable
fun ComprehensionSheet(
    chapterTitle: String,
    questions: List<ComprehensionQuestionItem>,
    answers: Map<String, Int>,
    isSubmitted: Boolean,
    score: Int,
    isLoading: Boolean,
    onAnswerQuestion: (questionId: String, optionIndex: Int) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
    onReload: () -> Unit = {}
) {
    val colors = LocalCustomColors.current

    ReaderSheet(
        onDismiss = onDismiss,
        title = "Trắc nghiệm đọc hiểu",
        subtitle = chapterTitle.ifBlank { "Nội dung chương vừa đọc" },
        serifSubtitle = chapterTitle.isNotBlank(),
        modifier = modifier
    ) {
        Box(modifier = Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = ReaderSheetInset)) {
            when {
                isLoading -> AiLoadingPanel("Đang tạo câu hỏi đọc hiểu tiếng Anh…", height = 200.dp)
                errorMessage != null -> AiErrorPanel(
                    title = "Chưa tạo được câu hỏi",
                    detail = errorMessage,
                    onRetry = if (errorMessage == AiErrors.MSG_LOGIN_REQUIRED) null else onReload,
                    onClose = onDismiss
                )
                questions.isEmpty() -> Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Chưa có câu hỏi cho chương này. Vui lòng thử lại sau.",
                        style = AppText.secondary,
                        color = colors.textSecondary
                    )
                }
                else -> Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    if (isSubmitted) ComprehensionScoreCard(score = score, total = questions.size)

                    questions.forEachIndexed { qIndex, q ->
                        ComprehensionQuestionCard(
                            index = qIndex + 1,
                            total = questions.size,
                            question = q,
                            selectedOption = answers[q.id],
                            isSubmitted = isSubmitted,
                            onSelectOption = { optIdx -> onAnswerQuestion(q.id, optIdx) }
                        )
                    }

                    ComprehensionActionBar(
                        isSubmitted = isSubmitted,
                        answeredAll = questions.all { answers.containsKey(it.id) },
                        questionCount = questions.size,
                        onSubmit = onSubmit,
                        onRetry = onRetry,
                        onDismiss = onDismiss
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                }
            }
        }
    }
}
