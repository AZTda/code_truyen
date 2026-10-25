package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.EmptyState
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.shared.practice.PracticeMode

/** Trạng thái khi chưa có đề luyện: vừa xong phiên, chưa có thẻ gắn câu gốc (điền khuyết), hoặc chưa có từ nào. */
@Composable
fun PracticeEmptyState(
    uiState: VocabularyUiState,
    onStartQuiz: () -> Unit,
    onSelectMode: (PracticeMode) -> Unit
) {
    val clozeReady = uiState.allWords.count { it.hasBookContext }
    val finished = uiState.practiceTotalCount > 0
    val needsBookContext = !finished && uiState.practiceMode == PracticeMode.CLOZE && clozeReady == 0

    EmptyState(
        message = when {
            finished -> "Xong phiên luyện"
            needsBookContext -> "Chưa có thẻ nào gắn câu gốc trong truyện"
            else -> "Chưa có từ nào để luyện"
        },
        subMessage = when {
            finished -> "Bạn đúng ${uiState.practiceCorrectCount}/${uiState.practiceTotalCount} câu."
            needsBookContext -> "Bài điền khuyết dùng chính câu văn nơi bạn gặp từ. " +
                "Hãy lưu thêm từ trong lúc đọc ở chế độ tiếng Anh, hoặc chuyển sang chế độ Gõ lại."
            else -> "Hãy lưu thêm từ vựng trong lúc đọc truyện."
        },
        icon = if (finished) TruyenCloudIcons.CheckCircle else TruyenCloudIcons.School,
        actionButton = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(text = "Thử quiz tình huống", onClick = onStartQuiz, modifier = Modifier.fillMaxWidth())
                if (needsBookContext) {
                    TonalButton(
                        text = "Chuyển sang Gõ lại",
                        onClick = { onSelectMode(PracticeMode.TYPING) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    )
}
