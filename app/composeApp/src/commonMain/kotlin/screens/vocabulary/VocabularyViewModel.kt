package com.truyencloud.compose.screens.vocabulary

import com.truyencloud.shared.api.CollocationItem
import com.truyencloud.shared.api.EnglishApi
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.notify.ReviewReminderConfig
import com.truyencloud.shared.practice.PracticeMode
import com.truyencloud.shared.repo.StudyConfig
import com.truyencloud.shared.repo.VocabularyRepository
import com.truyencloud.shared.tts.TtsSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VocabularyViewModel(
    private val vocabularyRepository: VocabularyRepository,
    private val ttsSpeaker: TtsSpeaker? = null,
    val englishApi: EnglishApi? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {

    private val _uiState = MutableStateFlow(VocabularyUiState())
    val uiState: StateFlow<VocabularyUiState> = _uiState.asStateFlow()

    private var cardShownAt: Long = 0L

    private val realLifeCoordinator = VocabularyRealLifeCoordinator(
        vocabularyRepository = vocabularyRepository,
        uiState = _uiState,
        scope = scope
    )

    private val flashcardCoordinator = VocabularyFlashcardCoordinator(
        vocabularyRepository = vocabularyRepository,
        ttsSpeaker = ttsSpeaker,
        uiState = _uiState,
        scope = scope,
        realLifeCoordinator = realLifeCoordinator,
        speakWord = { speakWord(it) }
    )

    private val practiceCoordinator = VocabularyPracticeCoordinator(
        vocabularyRepository = vocabularyRepository,
        ttsSpeaker = ttsSpeaker,
        uiState = _uiState,
        scope = scope,
        getCardShownAt = { cardShownAt },
        resetCardShownAt = { cardShownAt = nowMs() }
    )

    private val quizCoordinator = VocabularyQuizCoordinator(vocabularyRepository, _uiState, scope)
    private val cloudCoordinator = VocabularyCloudCoordinator(vocabularyRepository, _uiState, scope)

    init {
        observeWords()
        observeSnapshot()
        observeDailyStats()
        cloudCoordinator.loadReminder()
    }

    private fun observeWords() {
        scope.launch {
            combine(
                vocabularyRepository.getAllWords(),
                vocabularyRepository.getDueWords(),
                vocabularyRepository.getNewWords()
            ) { all, due, fresh -> Triple(all, due, fresh) }
                .collect { (all, due, fresh) ->
                    val s = _uiState.value
                    val filtered = VocabularyDeckBuilder.applyFilterAndSearch(all, due, s.selectedFilter, s.searchQuery)
                    val books = all.groupBy { it.bookTitle.ifBlank { "Sách khác" } }
                    val domains = all.groupBy { vocabularyRepository.getWordDomain(it) }

                    _uiState.update { st ->
                        val base = st.copy(allWords = all, dueWords = due, newWords = fresh)
                        val deck = nextDeck(base)
                        base.copy(
                            filteredWords = filtered,
                            booksMap = books,
                            domainsMap = domains,
                            studyDeck = deck,
                            cardIndex = if (deck.isEmpty()) 0 else keepCardIndex(st.studyDeck, st.cardIndex, deck),
                            isCardFlipped = st.isCardFlipped && deck.isNotEmpty() && sameCurrentCard(st, deck),
                            selectedWord = st.selectedWord ?: all.firstOrNull()
                        ).let { VocabularyDeckBuilder.refreshPractice(it, all) }
                    }
                    flashcardCoordinator.refreshIntervalPreview()

                    if (s.selectedWord == null) {
                        all.firstOrNull()?.let { realLifeCoordinator.loadScenariosForWord(it) }
                    }
                }
        }
    }

    private fun observeSnapshot() {
        scope.launch {
            vocabularyRepository.studySnapshot().collect { snap ->
                _uiState.update { it.copy(snapshot = snap) }
                // Hạn mức ngày (thẻ mới đã giới thiệu, lượt ôn đã làm) đổi theo snapshot: dựng lại hàng đợi,
                // nhưng không bao giờ làm xáo bộ thẻ khi người học đang giữa phiên.
                if (isIdle(_uiState.value)) rebuildDeck()
            }
        }
    }

    private fun observeDailyStats() {
        scope.launch {
            vocabularyRepository.dailyStats(30).collect { stats ->
                _uiState.update { it.copy(dailyStats = stats) }
            }
        }
    }

    /** Đang giữa phiên (đã chấm ít nhất một thẻ và còn thẻ) hoặc đang ôn nhồi: không được dựng lại bộ thẻ. */
    private fun isIdle(s: VocabularyUiState): Boolean =
        !s.isCramMode && !(s.reviewedCountSession > 0 && s.studyDeck.isNotEmpty())

    private fun nextDeck(s: VocabularyUiState): List<SavedWord> =
        if (isIdle(s)) VocabularyDeckBuilder.buildQueue(vocabularyRepository, s.dueWords, s.newWords, s)
        else VocabularyDeckBuilder.refreshInPlace(s.studyDeck, s.allWords)

    /** Bộ thẻ được dựng lại: giữ nguyên thẻ đang xem nếu nó vẫn còn trong bộ mới. */
    private fun keepCardIndex(old: List<SavedWord>, oldIndex: Int, new: List<SavedWord>): Int {
        val currentId = old.getOrNull(oldIndex)?.id ?: return 0
        val at = new.indexOfFirst { it.id == currentId }
        return if (at >= 0) at else 0
    }

    private fun sameCurrentCard(old: VocabularyUiState, new: List<SavedWord>): Boolean {
        val currentId = old.studyDeck.getOrNull(old.cardIndex)?.id ?: return false
        return new.any { it.id == currentId }
    }

    fun getActiveDeck(): List<SavedWord> = _uiState.value.studyDeck

    // Flashcard delegation
    fun onCardShown() = flashcardCoordinator.onCardShown()
    fun flipCard() = flashcardCoordinator.flipCard()
    fun toggleAutoSpeak() = flashcardCoordinator.toggleAutoSpeak()
    fun toggleClozeReveal() = flashcardCoordinator.toggleClozeReveal()
    fun toggleReverseCard() = flashcardCoordinator.toggleReverseCard()
    fun undoLastReview() = flashcardCoordinator.undoLastReview()
    fun shuffleDeck() = flashcardCoordinator.shuffleDeck()
    fun nextCard() = flashcardCoordinator.nextCard()
    fun prevCard() = flashcardCoordinator.prevCard()
    fun reviewCard(rating: Int, mode: String = "recognition") = flashcardCoordinator.reviewCard(rating, mode)
    fun suspendCurrentCard() = flashcardCoordinator.suspendCurrentCard()
    fun refreshIntervalPreview() = flashcardCoordinator.refreshIntervalPreview()
    fun startCram() = flashcardCoordinator.startCram()
    fun exitCram() = flashcardCoordinator.exitCram()

    // Practice delegation
    fun startPractice(mode: PracticeMode = PracticeMode.SMART) = practiceCoordinator.startPractice(mode)
    fun onPracticeInputChange(text: String) = practiceCoordinator.onPracticeInputChange(text)
    fun replayPracticeAudio() = practiceCoordinator.replayPracticeAudio()
    fun submitPracticeAnswer() = practiceCoordinator.submitPracticeAnswer()
    fun openPronunciation(word: SavedWord) = practiceCoordinator.openPronunciation(word)
    fun closePronunciation() = practiceCoordinator.closePronunciation()
    fun onPronunciationScored(score: Int) {
        _uiState.value.pronunciationWord?.let { practiceCoordinator.onPronunciationScored(it, score) }
    }
    fun selectPracticeOption(index: Int) = practiceCoordinator.selectPracticeOption(index)
    fun nextPracticeItem() = practiceCoordinator.nextPracticeItem()
    fun setPracticeMode(mode: PracticeMode) = practiceCoordinator.setPracticeMode(mode)

    // Quiz delegation
    fun startQuiz() = quizCoordinator.startQuiz()
    fun answerQuiz(optionIndex: Int) = quizCoordinator.answerQuiz(optionIndex)
    fun nextQuizQuestion() = quizCoordinator.nextQuizQuestion()
    fun dismissQuiz() = quizCoordinator.dismissQuiz()

    // Navigation & filters
    fun selectMode(mode: VocabMode) {
        _uiState.update { it.copy(activeMode = mode, isCardFlipped = false) }
        when (mode) {
            VocabMode.REAL_LIFE -> {
                val s = _uiState.value
                if (s.selectedWord != null && s.scenarios == null) realLifeCoordinator.loadScenariosForWord(s.selectedWord)
            }
            VocabMode.PRACTICE -> if (_uiState.value.practiceItem == null) startPractice()
            VocabMode.FLASHCARDS -> {
                if (isIdle(_uiState.value)) rebuildDeck()
                flashcardCoordinator.onCardShown()
                flashcardCoordinator.refreshIntervalPreview()
            }
            else -> Unit
        }
    }

    fun selectFilter(filter: VocabFilter) {
        _uiState.update { s ->
            s.copy(
                selectedFilter = filter,
                filteredWords = VocabularyDeckBuilder.applyFilterAndSearch(s.allWords, s.dueWords, filter, s.searchQuery),
                cardIndex = 0,
                isCardFlipped = false
            )
        }
        rebuildDeck()
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { s ->
            s.copy(
                searchQuery = query,
                filteredWords = VocabularyDeckBuilder.applyFilterAndSearch(s.allWords, s.dueWords, s.selectedFilter, query),
                cardIndex = 0,
                isCardFlipped = false
            )
        }
    }

    fun setContextGroupMode(mode: ContextGroupMode) = _uiState.update { it.copy(contextGroupMode = mode) }

    fun selectBookFilter(bookTitle: String?) {
        _uiState.update { it.copy(selectedBook = bookTitle, selectedDomain = null, cardIndex = 0, isCardFlipped = false) }
        rebuildDeck()
    }

    fun selectDomainFilter(domain: String?) {
        _uiState.update { it.copy(selectedDomain = domain, selectedBook = null, cardIndex = 0, isCardFlipped = false) }
        rebuildDeck()
    }

    fun clearContextFilter() {
        _uiState.update { it.copy(selectedBook = null, selectedDomain = null, cardIndex = 0, isCardFlipped = false) }
        rebuildDeck()
    }

    /** Dựng lại hàng đợi từ DB (đổi bộ lọc, vào tab Ôn thẻ, hạn mức đổi) và bắt đầu một phiên mới. */
    private fun rebuildDeck() {
        _uiState.update { s ->
            val queue = VocabularyDeckBuilder.buildQueue(vocabularyRepository, s.dueWords, s.newWords, s)
            val at = keepCardIndex(s.studyDeck, s.cardIndex, queue)
            s.copy(
                isCramMode = false,
                reviewedCountSession = 0,
                studyDeck = queue,
                cardIndex = at,
                isCardFlipped = s.isCardFlipped && queue.isNotEmpty() && sameCurrentCard(s, queue)
            )
        }
        flashcardCoordinator.refreshIntervalPreview()
    }

    fun getWordDomain(word: SavedWord): String = vocabularyRepository.getWordDomain(word)

    // Context story
    fun weaveStoryForCurrentContext() = realLifeCoordinator.weaveStoryForCurrentContext()
    fun dismissContextStory() = realLifeCoordinator.dismissContextStory()

    // Real-life studio
    fun selectWordForRealLife(word: SavedWord) = realLifeCoordinator.selectWordForRealLife(word)
    fun onUserSentenceChange(text: String) = realLifeCoordinator.onUserSentenceChange(text)
    fun submitSentenceEvaluation() = realLifeCoordinator.submitSentenceEvaluation()

    // Flashcard Art & Collocations
    fun generateFlashcardArt(word: SavedWord) = realLifeCoordinator.generateFlashcardArt(word)
    fun loadFlashcardDetailsIfNeeded(word: SavedWord) = realLifeCoordinator.loadFlashcardDetailsIfNeeded(word)
    fun getCollocations(word: SavedWord): List<CollocationItem> = realLifeCoordinator.getCollocations(word)

    fun speakWord(text: String) {
        ttsSpeaker?.speakWord(text.trim(), language = "en")
    }

    fun deleteWord(word: SavedWord) {
        scope.launch { vocabularyRepository.deleteWord(word) }
    }

    fun toggleWordSuspended(word: SavedWord) {
        val nextSuspended = !word.isSuspended
        scope.launch {
            vocabularyRepository.setSuspended(word.id, nextSuspended)
            if (nextSuspended) {
                _uiState.update { s -> s.copy(studyDeck = s.studyDeck.filterNot { it.id == word.id }) }
            }
        }
    }

    // Cloud sync, Anki export, reminders & study goal
    fun syncWithCloud() = cloudCoordinator.syncWithCloud()
    fun exportAnkiDeck() = cloudCoordinator.exportAnkiDeck()
    fun openReminderDialog() = cloudCoordinator.openReminderDialog()
    fun closeReminderDialog() = cloudCoordinator.closeReminderDialog()
    fun saveReminder(config: ReviewReminderConfig) = cloudCoordinator.saveReminder(config)
    fun openStudyGoalDialog() = cloudCoordinator.openStudyGoalDialog()
    fun closeStudyGoalDialog() = cloudCoordinator.closeStudyGoalDialog()
    fun saveStudyConfig(config: StudyConfig) = cloudCoordinator.saveStudyConfig(config) { rebuildDeck() }
    fun openAddWordDialog() = cloudCoordinator.openAddWordDialog()
    fun closeAddWordDialog() = cloudCoordinator.closeAddWordDialog()
    fun addCustomWord(word: String, sentence: String, meaningVi: String) =
        cloudCoordinator.addCustomWord(word, sentence, meaningVi)

    private fun nowMs(): Long = com.truyencloud.shared.data.getCurrentEpochMillis()
}
