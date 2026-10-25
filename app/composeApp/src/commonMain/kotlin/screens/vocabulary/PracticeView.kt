package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import com.truyencloud.compose.screens.home.imeAboveTabBar
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.practice.PracticeItem
import com.truyencloud.shared.practice.PracticeMode

private val PRACTICE_MODES = listOf(
    PracticeMode.SMART,
    PracticeMode.CLOZE,
    PracticeMode.TYPING,
    PracticeMode.RECOGNITION,
    PracticeMode.LISTENING,
    PracticeMode.COLLOCATION,
    PracticeMode.PRONUNCIATION
)

/**
 * Phòng luyện tập chủ động.
 *
 * Khác với chế độ lật thẻ (chỉ NHẬN DIỆN), ở đây người học phải SẢN SINH ra từ:
 * điền vào đúng câu trong truyện, gõ lại từ nghĩa tiếng Việt, hoặc nghe rồi chép.
 * Kết quả mỗi bài được đưa thẳng vào lịch FSRS nên luyện tập và ôn tập là một vòng.
 */
@Composable
fun PracticeView(
    uiState: VocabularyUiState,
    onSelectMode: (PracticeMode) -> Unit,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onNext: () -> Unit,
    onReplayAudio: () -> Unit,
    onSelectOption: (Int) -> Unit,
    onSpeak: (String) -> Unit,
    onStartQuiz: () -> Unit,
    onOpenPronunciation: ((SavedWord) -> Unit)? = null,
    bottomPadding: Dp = Spacing.lg
) {
    val colors = LocalCustomColors.current
    val item = uiState.practiceItem
    val scrollState = rememberScrollState()
    // Sang câu mới thì về đầu trang: ở màn hẹp người học đã cuộn xuống nút "Câu tiếp theo", không để họ thấy giữa đề
    // (khóa theo chỉ số + id thẻ: thẻ được làm mới sau khi chấm vẫn là câu cũ nên không kéo trang lên giữa chừng)
    LaunchedEffect(uiState.practiceIndex, item?.word?.id, item?.mode) { scrollState.scrollTo(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imeAboveTabBar()
            .verticalScroll(scrollState)
            .padding(bottom = bottomPadding)
    ) {
        ChipScrollRow(count = PRACTICE_MODES.size, selectedIndex = PRACTICE_MODES.indexOf(uiState.practiceMode)) {
            items(PRACTICE_MODES) { mode ->
                FilterChip8(
                    label = mode.label,
                    selected = uiState.practiceMode == mode,
                    onClick = { onSelectMode(mode) }
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = Spacing.pageH)) {
            Text(text = uiState.practiceMode.description, style = AppText.secondary, color = colors.textSecondary)
            Spacer(Modifier.height(Spacing.md))

            if (uiState.practiceTotalCount > 0) {
                PracticeProgressRow(uiState)
                Spacer(Modifier.height(Spacing.sm))
            }

            if (item == null) {
                PracticeEmptyState(uiState = uiState, onStartQuiz = onStartQuiz, onSelectMode = onSelectMode)
            } else {
                PracticeSession(
                    uiState = uiState,
                    item = item,
                    onInputChange = onInputChange,
                    onSubmit = onSubmit,
                    onNext = onNext,
                    onReplayAudio = onReplayAudio,
                    onSelectOption = onSelectOption,
                    onSpeak = onSpeak,
                    onOpenPronunciation = onOpenPronunciation
                )
            }
        }
    }
}

@Composable
private fun PracticeProgressRow(uiState: VocabularyUiState) {
    val c = LocalCustomColors.current
    val accuracy = uiState.practiceCorrectCount * 100 / uiState.practiceTotalCount
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = "Câu ${uiState.practiceIndex + 1}/${uiState.practiceQueue.size}",
            style = AppText.secondary,
            color = c.textSecondary
        )
        Text(
            text = "Đúng $accuracy% (${uiState.practiceCorrectCount}/${uiState.practiceTotalCount})",
            style = AppText.secondaryStrong,
            color = if (accuracy >= 70) c.success else c.warning
        )
    }
}

/** Đề + ô trả lời + kết quả + nút hành động của lượt luyện đang làm. */
@Composable
private fun ColumnScope.PracticeSession(
    uiState: VocabularyUiState,
    item: PracticeItem,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onNext: () -> Unit,
    onReplayAudio: () -> Unit,
    onSelectOption: (Int) -> Unit,
    onSpeak: (String) -> Unit,
    onOpenPronunciation: ((SavedWord) -> Unit)?
) {
    val c = LocalCustomColors.current
    val result = uiState.practiceResult
    val isPronunciation = item.mode == PracticeMode.PRONUNCIATION

    PracticePromptCard(item = item, onReplayAudio = onReplayAudio)
    Spacer(Modifier.height(Spacing.lg))

    when {
        // Phát âm chấm bằng giọng nói thật trong phòng luyện AI: không có ô gõ chữ để lấy điểm (xem mô tả chế độ ở trên)
        isPronunciation -> Unit
        item.options.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            item.options.forEachIndexed { idx, option ->
                val state = when {
                    result == null -> AnswerState.Idle
                    idx == item.correctOptionIndex -> AnswerState.Correct
                    result.actual == option -> AnswerState.Wrong
                    else -> AnswerState.Idle
                }
                AnswerTile(
                    text = option,
                    state = state,
                    enabled = result == null,
                    onClick = { onSelectOption(idx) }
                )
            }
        }
        else -> VocabularyTextInput(
            value = uiState.practiceInput,
            onValueChange = onInputChange,
            placeholder = when (item.mode) {
                PracticeMode.LISTENING -> "Gõ lại câu bạn vừa nghe..."
                PracticeMode.CLOZE -> "Từ còn thiếu là..."
                else -> "Gõ từ tiếng Anh..."
            },
            enabled = result == null,
            singleLine = item.mode != PracticeMode.LISTENING,
            minHeight = if (item.mode == PracticeMode.LISTENING) 96.dp else 52.dp
        )
    }

    Spacer(Modifier.height(Spacing.md))

    if (result != null) {
        PracticeResultCard(result = result, item = item, onSpeak = onSpeak)
        Spacer(Modifier.height(Spacing.md))
    }

    PrimaryButton(
        text = when {
            result != null -> "Câu tiếp theo"
            isPronunciation -> "Nói vào micro"
            else -> "Kiểm tra"
        },
        onClick = {
            when {
                result != null -> onNext()
                isPronunciation -> onOpenPronunciation?.invoke(item.word)
                else -> onSubmit()
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
    if (isPronunciation && result == null) {
        TextActionButton(
            text = "Bỏ qua từ này",
            onClick = onNext,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}
