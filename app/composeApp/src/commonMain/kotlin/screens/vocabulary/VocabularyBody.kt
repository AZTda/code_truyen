package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.NoticeBanner
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.theme.Spacing

/**
 * Thân màn Từ vựng dưới thanh tiêu đề lớn: thông báo đồng bộ, bộ chọn chế độ (ghim cố định) và nội dung chế độ đang chọn.
 * Mỗi chế độ là một vùng cuộn duy nhất lấp đầy phần còn lại nên thanh tiêu đề thu gọn theo ngón tay.
 */
@Composable
internal fun VocabularyBody(
    uiState: VocabularyUiState,
    viewModel: VocabularyViewModel,
    bottomPadding: Dp
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Banner kết quả đồng bộ: xanh = xong, đỏ = lỗi, thông tin = chưa đăng nhập
        uiState.syncMessage?.let { message ->
            NoticeBanner(
                text = message,
                tone = when (uiState.syncTone) {
                    SyncTone.SUCCESS -> NoticeTone.Success
                    SyncTone.ERROR -> NoticeTone.Error
                    SyncTone.INFO -> NoticeTone.Info
                },
                modifier = Modifier.padding(horizontal = Spacing.pageH, vertical = 4.dp)
            )
        }

        VocabularyModeSwitcher(
            activeMode = uiState.activeMode,
            onSelectMode = viewModel::selectMode,
            modifier = Modifier.padding(bottom = Spacing.xs)
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (uiState.activeMode) {
                VocabMode.NOTEBOOK -> NotebookContent(uiState, viewModel, bottomPadding)

                VocabMode.FLASHCARDS -> {
                    LaunchedEffect(uiState.cardIndex, uiState.activeMode) {
                        viewModel.onCardShown()
                    }
                    FlashcardsView(
                        uiState = uiState,
                        deck = viewModel.getActiveDeck(),
                        onClearContextFilter = viewModel::clearContextFilter,
                        onFlip = viewModel::flipCard,
                        onNext = viewModel::nextCard,
                        onPrev = viewModel::prevCard,
                        onReview = viewModel::reviewCard,
                        onSpeak = viewModel::speakWord,
                        onToggleAutoSpeak = viewModel::toggleAutoSpeak,
                        onToggleClozeReveal = viewModel::toggleClozeReveal,
                        onShuffle = viewModel::shuffleDeck,
                        onToggleReverseCard = viewModel::toggleReverseCard,
                        onUndo = viewModel::undoLastReview,
                        onGenerateArt = viewModel::generateFlashcardArt,
                        onLoadDetails = viewModel::loadFlashcardDetailsIfNeeded,
                        onGetCollocations = viewModel::getCollocations,
                        onStartCram = viewModel::startCram,
                        onExitCram = viewModel::exitCram,
                        onOpenPractice = { viewModel.selectMode(VocabMode.PRACTICE) },
                        bottomPadding = bottomPadding
                    )
                }

                VocabMode.PRACTICE -> PracticeView(
                    uiState = uiState,
                    onSelectMode = viewModel::setPracticeMode,
                    onInputChange = viewModel::onPracticeInputChange,
                    onSubmit = viewModel::submitPracticeAnswer,
                    onNext = viewModel::nextPracticeItem,
                    onReplayAudio = viewModel::replayPracticeAudio,
                    onSelectOption = viewModel::selectPracticeOption,
                    onSpeak = viewModel::speakWord,
                    onStartQuiz = viewModel::startQuiz,
                    onOpenPronunciation = viewModel::openPronunciation,
                    bottomPadding = bottomPadding
                )

                VocabMode.REAL_LIFE -> RealLifeStudioView(
                    uiState = uiState,
                    onSelectWord = viewModel::selectWordForRealLife,
                    onSentenceChange = viewModel::onUserSentenceChange,
                    onSubmitSentence = viewModel::submitSentenceEvaluation,
                    onGenerateArt = viewModel::generateFlashcardArt,
                    onSpeak = viewModel::speakWord,
                    bottomPadding = bottomPadding
                )
            }
        }
    }
}

/** Sổ từ: nhấn "Ôn thẻ" theo truyện / chủ đề thì lọc rồi chuyển sang chế độ Ôn thẻ, giống bản cũ. */
@Composable
internal fun NotebookContent(
    uiState: VocabularyUiState,
    viewModel: VocabularyViewModel,
    bottomPadding: Dp,
    showOverview: Boolean = true
) {
    NotebookView(
        uiState = uiState,
        onFilterSelect = viewModel::selectFilter,
        onContextGroupModeSelect = viewModel::setContextGroupMode,
        onSelectBook = { book ->
            viewModel.selectBookFilter(book)
            viewModel.selectMode(VocabMode.FLASHCARDS)
        },
        onSelectDomain = { domain ->
            viewModel.selectDomainFilter(domain)
            viewModel.selectMode(VocabMode.FLASHCARDS)
        },
        onWeaveStoryForBook = { book ->
            viewModel.selectBookFilter(book)
            viewModel.weaveStoryForCurrentContext()
        },
        onWeaveStoryForDomain = { domain ->
            viewModel.selectDomainFilter(domain)
            viewModel.weaveStoryForCurrentContext()
        },
        onSpeak = viewModel::speakWord,
        onSelectForRealLife = viewModel::selectWordForRealLife,
        onGenerateArt = viewModel::generateFlashcardArt,
        onLoadDetails = viewModel::loadFlashcardDetailsIfNeeded,
        onToggleSuspend = viewModel::toggleWordSuspended,
        onDelete = viewModel::deleteWord,
        onOpenStudyGoal = viewModel::openStudyGoalDialog,
        onOpenAddWord = viewModel::openAddWordDialog,
        bottomPadding = bottomPadding,
        showOverview = showOverview
    )
}
