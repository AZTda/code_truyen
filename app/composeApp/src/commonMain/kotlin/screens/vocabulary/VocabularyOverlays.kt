package com.truyencloud.compose.screens.vocabulary

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.truyencloud.compose.screens.practice.PronunciationSheet

/**
 * Các lớp phủ của màn Từ vựng (tùy chọn phụ, quiz, vi truyện, phòng luyện phát âm, nhắc ôn). Mỗi lớp tự hiện theo
 * trạng thái của ViewModel; chỉ tùy chọn phụ do màn cha điều khiển.
 */
@Composable
internal fun VocabularyOverlays(
    uiState: VocabularyUiState,
    viewModel: VocabularyViewModel,
    showMoreSheet: Boolean,
    onDismissMore: () -> Unit
) {
    if (showMoreSheet) {
        VocabularyMoreSheet(
            reminder = uiState.reminder,
            studyConfig = uiState.studyConfig,
            canExport = uiState.allWords.isNotEmpty(),
            onOpenAddWord = {
                onDismissMore()
                viewModel.openAddWordDialog()
            },
            onOpenStudyGoal = {
                onDismissMore()
                viewModel.openStudyGoalDialog()
            },
            onOpenReminder = {
                onDismissMore()
                viewModel.openReminderDialog()
            },
            onExportAnki = {
                onDismissMore()
                viewModel.exportAnkiDeck()
            },
            onDismiss = onDismissMore
        )
    }

    if (uiState.showStudyGoalDialog) {
        StudyGoalDialog(
            initial = uiState.studyConfig,
            onSave = viewModel::saveStudyConfig,
            onDismiss = viewModel::closeStudyGoalDialog
        )
    }

    if (uiState.showAddWordDialog) {
        AddCustomWordDialog(
            isLoading = uiState.isAddingCustomWord,
            errorMessage = uiState.addWordError,
            onSubmit = viewModel::addCustomWord,
            onDismiss = viewModel::closeAddWordDialog
        )
    }

    // Quiz tình huống (nối lại endpoint /english/practice-quiz). Khóa theo trạng thái đang tải: người dùng vuốt tờ đi
    // lúc đang dựng thì khi quiz sẵn sàng tờ mới được dựng lại thay vì kẹt ở trạng thái đã ẩn.
    if (uiState.isLoadingQuiz || uiState.quizQuestions.isNotEmpty() || uiState.quizError != null) {
        key(uiState.isLoadingQuiz) {
            QuizDialog(
                uiState = uiState,
                onAnswer = viewModel::answerQuiz,
                onNext = viewModel::nextQuizQuestion,
                onRetry = viewModel::startQuiz,
                onDismiss = viewModel::dismissQuiz
            )
        }
    }

    // Vi truyện ngữ cảnh do Gemini dệt
    if (uiState.contextStory != null || uiState.isWeavingStory) {
        ContextStoryDialog(
            isWeaving = uiState.isWeavingStory,
            story = uiState.contextStory,
            onDismiss = viewModel::dismissContextStory
        )
    }

    // Luyện phát âm
    uiState.pronunciationWord?.let { word ->
        PronunciationSheet(
            word = word.word,
            ipa = word.ipaUs.ifBlank { word.ipaUk },
            meaningVi = word.contextMeaningVi,
            englishApi = viewModel.englishApi,
            onSpeakWord = viewModel::speakWord,
            onScored = viewModel::onPronunciationScored,
            onDismiss = viewModel::closePronunciation
        )
    }

    // Nhắc ôn hằng ngày
    if (uiState.showReminderDialog) {
        ReviewReminderDialog(
            initial = uiState.reminder,
            notice = uiState.reminderNotice,
            onSave = viewModel::saveReminder,
            onDismiss = viewModel::closeReminderDialog
        )
    }
}
