package com.truyencloud.compose.screens.vocabulary

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.truyencloud.compose.components.EmptyState
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppAnimations
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.api.CollocationItem
import com.truyencloud.shared.data.SavedWord

@Composable
internal fun FlashcardsView(
    uiState: VocabularyUiState,
    deck: List<SavedWord> = emptyList(),
    onClearContextFilter: () -> Unit = {},
    onFlip: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onReview: (Int) -> Unit,
    onSpeak: (String) -> Unit,
    onToggleAutoSpeak: () -> Unit,
    onToggleClozeReveal: () -> Unit,
    onShuffle: () -> Unit,
    onToggleReverseCard: () -> Unit = {},
    onUndo: () -> Unit = {},
    onGenerateArt: (SavedWord) -> Unit,
    onLoadDetails: (SavedWord) -> Unit,
    onGetCollocations: (SavedWord) -> List<CollocationItem>,
    onStartCram: () -> Unit = {},
    onExitCram: () -> Unit = {},
    onOpenPractice: () -> Unit = {},
    bottomPadding: Dp = Spacing.lg
) {
    // Hết thẻ thì HẾT — không còn lùi về "tất cả từ" (bản cũ làm phiên ôn không bao giờ kết thúc).
    if (deck.isEmpty()) {
        if (uiState.allWords.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    message = "Sổ từ đang trống",
                    subMessage = "Chạm vào từ tiếng Anh trong lúc đọc truyện để lưu vào đây.",
                    icon = TruyenCloudIcons.School
                )
            }
        } else {
            FlashcardsCompleteView(uiState, onStartCram, onExitCram, onOpenPractice, onUndo, bottomPadding)
        }
        return
    }

    val currentIndex = uiState.cardIndex.coerceIn(0, deck.size - 1)
    val currentWord = deck[currentIndex]
    val isGeneratingThisWord = uiState.generatingArtWords.contains(currentWord.word)

    LaunchedEffect(currentWord) {
        onLoadDetails(currentWord)
    }

    val rotation by animateFloatAsState(
        targetValue = if (uiState.isCardFlipped) 180f else 0f,
        animationSpec = AppAnimations.slowTween,
        label = "cardFlip3D"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.pageH)
            .padding(bottom = bottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (uiState.isCramMode) FlashcardCramBanner(onExit = onExitCram)
        FlashcardContextBanner(uiState, onClearContextFilter)

        FlashcardProgressHeader(
            uiState = uiState,
            deck = deck,
            currentWord = currentWord,
            onToggleAutoSpeak = onToggleAutoSpeak,
            onShuffle = onShuffle,
            onToggleReverseCard = onToggleReverseCard,
            onUndo = onUndo
        )

        Spacer(modifier = Modifier.height(Spacing.sm))

        FlashcardInteractiveCard(
            modifier = Modifier.weight(1f),
            word = currentWord,
            rotation = rotation,
            canRate = uiState.isCardFlipped,
            isClozeRevealed = uiState.isClozeRevealed,
            isGeneratingArt = isGeneratingThisWord,
            isReverseCard = uiState.isReverseCard,
            cachedArt = uiState.cardArtCache[currentWord.word],
            collocations = onGetCollocations(currentWord),
            onFlip = onFlip,
            onReview = onReview,
            onSpeak = onSpeak,
            onToggleClozeReveal = onToggleClozeReveal,
            onGenerateArt = onGenerateArt
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        FlashcardRatingBar(
            isFlipped = uiState.isCardFlipped,
            intervalPreview = uiState.intervalPreview,
            onReview = onReview,
            onFlip = onFlip,
            onPrev = onPrev,
            onNext = onNext
        )
    }
}
