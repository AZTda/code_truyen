package com.truyencloud.compose.screens.vocabulary

import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.repo.VocabularyRepository
import com.truyencloud.shared.srs.Rating
import com.truyencloud.shared.srs.ReviewPolicy
import com.truyencloud.shared.tts.TtsSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phiên Ôn thẻ (lặp lại ngắt quãng FSRS).
 *
 * - Chỉ chấm điểm SAU KHI lật thẻ (nút hoặc vuốt) — bản cũ cho vuốt phải là "Dễ" ngay cả khi chưa nhìn đáp án.
 * - Thẻ bấm Quên xuất hiện lại sau ~3 thẻ, thẻ Khó đang ở bước học ngắn sau ~6 thẻ (chưa chắc thuộc thì gặp lại
 *   ngay trong phiên, không đợi ngày mai).
 * - Hết thẻ đến hạn là HẾT phiên ("Hoàn thành hôm nay"). Muốn ôn thêm thì chọn ôn nhồi — chế độ này KHÔNG ghi lịch FSRS.
 */
class VocabularyFlashcardCoordinator(
    private val vocabularyRepository: VocabularyRepository,
    private val ttsSpeaker: TtsSpeaker?,
    private val uiState: MutableStateFlow<VocabularyUiState>,
    private val scope: CoroutineScope,
    private val realLifeCoordinator: VocabularyRealLifeCoordinator,
    private val speakWord: (String) -> Unit
) {
    private var cardShownAt: Long = 0L
    private var writing = false

    private data class UndoSnapshot(
        val previousWord: SavedWord,
        val logId: Long,
        val deckBefore: List<SavedWord>,
        val cardIndexBefore: Int,
        val reviewedCountBefore: Int
    )

    private var lastUndo: UndoSnapshot? = null

    fun currentCard(): SavedWord? {
        val deck = uiState.value.studyDeck
        if (deck.isEmpty()) return null
        return deck.getOrNull(uiState.value.cardIndex.coerceIn(0, deck.size - 1))
    }

    fun refreshIntervalPreview() {
        val s = uiState.value
        val word = currentCard()
        // Ôn nhồi không đổi lịch nên không hứa "5 phút / 4 ngày" trên các nút.
        val preview = if (word == null || s.isCramMode) emptyMap() else vocabularyRepository.previewIntervals(word)
        uiState.update { it.copy(intervalPreview = preview) }
    }

    fun onCardShown() {
        cardShownAt = nowMs()
    }

    fun flipCard() {
        val willFlip = !uiState.value.isCardFlipped
        uiState.update { it.copy(isCardFlipped = willFlip) }
        if (willFlip) {
            refreshIntervalPreview()
            if (uiState.value.isAutoSpeak) currentCard()?.let { speakWord(it.word) }
        }
    }

    fun toggleAutoSpeak() = uiState.update { it.copy(isAutoSpeak = !it.isAutoSpeak) }
    fun toggleClozeReveal() = uiState.update { it.copy(isClozeRevealed = !it.isClozeRevealed) }
    fun toggleReverseCard() = uiState.update {
        it.copy(isReverseCard = !it.isReverseCard, isCardFlipped = false, isClozeRevealed = false)
    }

    fun undoLastReview() {
        val snap = lastUndo ?: return
        if (writing) return
        lastUndo = null
        uiState.update {
            it.copy(
                studyDeck = snap.deckBefore,
                cardIndex = snap.cardIndexBefore.coerceIn(0, (snap.deckBefore.size - 1).coerceAtLeast(0)),
                isCardFlipped = false,
                isClozeRevealed = false,
                reviewedCountSession = snap.reviewedCountBefore,
                canUndoReview = false
            )
        }
        scope.launch {
            runCatching { vocabularyRepository.undoReview(snap.previousWord, snap.logId) }
            refreshIntervalPreview()
        }
    }

    fun shuffleDeck() {
        uiState.update { s ->
            s.copy(
                studyDeck = s.studyDeck.shuffled(),
                cardIndex = 0,
                isCardFlipped = false,
                isClozeRevealed = false,
                isShuffled = !s.isShuffled
            )
        }
        refreshIntervalPreview()
    }

    fun nextCard() = moveCard(1)
    fun prevCard() = moveCard(-1)

    private fun moveCard(delta: Int) {
        val deck = uiState.value.studyDeck
        if (deck.isEmpty()) return
        uiState.update { s ->
            val idx = ((s.cardIndex + delta) % deck.size + deck.size) % deck.size
            s.copy(cardIndex = idx, isCardFlipped = false, isClozeRevealed = false)
        }
        cardShownAt = nowMs()
        refreshIntervalPreview()
        currentCard()?.let { realLifeCoordinator.loadFlashcardDetailsIfNeeded(it) }
    }

    fun reviewCard(rating: Int, mode: String = "recognition") {
        val state = uiState.value
        val word = currentCard() ?: return
        // Chỉ chấm sau khi đã lật thẻ: chặn cả nút bấm lẫn vuốt khi người học chưa xem đáp án.
        if (!state.isCardFlipped || writing) return
        writing = true

        val cram = state.isCramMode
        val grade = Rating.from(normalizeRating(rating))
        val elapsed = if (cardShownAt > 0L) nowMs() - cardShownAt else 0L

        scope.launch {
            try {
                val deckBefore = state.studyDeck
                val indexBefore = state.cardIndex
                val reviewedBefore = state.reviewedCountSession
                val result = vocabularyRepository.reviewWordDetailed(
                    word = word,
                    rating = grade,
                    reviewMode = if (cram) "cram" else if (state.isReverseCard) "recall" else mode,
                    responseMs = elapsed,
                    policy = if (cram) ReviewPolicy.PRACTICE_ONLY else ReviewPolicy.SCHEDULE
                )
                lastUndo = UndoSnapshot(
                    previousWord = result.previousWord,
                    logId = result.logId,
                    deckBefore = deckBefore,
                    cardIndexBefore = indexBefore,
                    reviewedCountBefore = reviewedBefore
                )
                val stepCard = result.scheduled && result.outcome.intervalDays == 0L
                uiState.update { s -> advance(s, word.id, result.word, grade, stepCard).copy(canUndoReview = true) }
            } finally {
                writing = false
            }
            cardShownAt = nowMs()
            refreshIntervalPreview()
            currentCard()?.let { realLifeCoordinator.loadFlashcardDetailsIfNeeded(it) }
        }
    }

    /** Bỏ thẻ vừa chấm khỏi đầu hàng đợi; nếu còn phải gặp lại trong phiên thì chèn lại sau vài thẻ. */
    private fun advance(
        s: VocabularyUiState,
        wordId: String,
        updated: SavedWord,
        grade: Rating,
        stepCard: Boolean
    ): VocabularyUiState {
        val deck = s.studyDeck.toMutableList()
        val at = deck.indexOfFirst { it.id == wordId }
        if (at >= 0) {
            deck.removeAt(at)
            val gap = requeueGap(grade, stepCard, s.isCramMode)
            if (gap != null) deck.add(minOf(at + gap, deck.size), updated)
        }
        val next = if (at >= 0) at else s.cardIndex
        return s.copy(
            studyDeck = deck,
            cardIndex = if (deck.isEmpty()) 0 else next.coerceIn(0, deck.size - 1),
            isCardFlipped = false,
            isClozeRevealed = false,
            reviewedCountSession = s.reviewedCountSession + 1
        )
    }

    /** null = không gặp lại trong phiên. */
    private fun requeueGap(grade: Rating, stepCard: Boolean, cram: Boolean): Int? = when {
        grade == Rating.AGAIN && (stepCard || cram) -> AGAIN_GAP
        grade == Rating.HARD && (stepCard || cram) -> HARD_GAP
        else -> null
    }

    /** Vuốt phải / nút Tốt... đều đi qua đây; 0 (quy ước cũ) là Quên. */
    private fun normalizeRating(raw: Int): Int = when (raw) {
        0 -> Rating.AGAIN.value
        in 1..4 -> raw
        else -> Rating.GOOD.value
    }

    fun suspendCurrentCard() {
        val word = currentCard() ?: return
        scope.launch {
            vocabularyRepository.setSuspended(word.id, true)
            uiState.update { s -> s.copy(studyDeck = s.studyDeck.filterNot { it.id == word.id }) }
        }
    }

    // -----------------------------------------------------------------------
    // Ôn nhồi (không ghi lịch)
    // -----------------------------------------------------------------------

    fun startCram() {
        val s = uiState.value
        val pool = VocabularyDeckBuilder.cramPool(s.allWords, s, vocabularyRepository, nowMs())
        if (pool.isEmpty()) return
        uiState.update {
            it.copy(
                isCramMode = true,
                studyDeck = pool,
                cardIndex = 0,
                isCardFlipped = false,
                isClozeRevealed = false,
                reviewedCountSession = 0,
                intervalPreview = emptyMap()
            )
        }
        cardShownAt = nowMs()
        currentCard()?.let { realLifeCoordinator.loadFlashcardDetailsIfNeeded(it) }
    }

    fun exitCram() {
        uiState.update { s ->
            s.copy(
                isCramMode = false,
                studyDeck = VocabularyDeckBuilder.buildQueue(vocabularyRepository, s.dueWords, s.newWords, s),
                cardIndex = 0,
                isCardFlipped = false,
                isClozeRevealed = false,
                reviewedCountSession = 0
            )
        }
        refreshIntervalPreview()
    }

    private fun nowMs(): Long = com.truyencloud.shared.data.getCurrentEpochMillis()

    private companion object {
        const val AGAIN_GAP = 3
        const val HARD_GAP = 6
    }
}
