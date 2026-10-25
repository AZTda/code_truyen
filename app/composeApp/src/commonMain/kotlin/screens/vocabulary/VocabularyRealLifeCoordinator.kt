package com.truyencloud.compose.screens.vocabulary

import com.truyencloud.shared.api.CollocationItem
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.repo.VocabularyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VocabularyRealLifeCoordinator(
    private val vocabularyRepository: VocabularyRepository,
    private val uiState: MutableStateFlow<VocabularyUiState>,
    private val scope: CoroutineScope
) {

    fun weaveStoryForCurrentContext() {
        val s = uiState.value
        val words = s.studyDeck.map { it.word }.ifEmpty { s.allWords.map { it.word } }
        if (words.isEmpty()) return

        uiState.update { it.copy(isWeavingStory = true, contextStory = null) }
        scope.launch {
            try {
                val story = vocabularyRepository.weaveContextStory(
                    words = words.take(5),
                    bookTitle = s.selectedBook ?: "",
                    domain = s.selectedDomain ?: ""
                )
                uiState.update { it.copy(isWeavingStory = false, contextStory = story) }
            } catch (_: Exception) {
                uiState.update { it.copy(isWeavingStory = false) }
            }
        }
    }

    fun dismissContextStory() = uiState.update { it.copy(contextStory = null) }

    fun selectWordForRealLife(word: SavedWord) {
        uiState.update {
            it.copy(
                selectedWord = word,
                activeMode = VocabMode.REAL_LIFE,
                userSentenceInput = "",
                sentenceEvaluation = null,
                evaluationError = null
            )
        }
        loadScenariosForWord(word)
    }

    fun loadScenariosForWord(word: SavedWord) {
        uiState.update {
            it.copy(
                isLoadingScenarios = true,
                scenarios = null,
                scenariosError = null,
                practicedSentences = vocabularyRepository.getPracticedSentences(word)
            )
        }
        scope.launch {
            try {
                val res = vocabularyRepository.getLifeScenarios(word)
                uiState.update {
                    it.copy(
                        isLoadingScenarios = false,
                        scenarios = if (res.isFallback) null else res,
                        scenariosError = if (res.isFallback) {
                            res.error.ifBlank { "Chưa tạo được tình huống. Vui lòng thử lại." }
                        } else null
                    )
                }
            } catch (e: Exception) {
                uiState.update {
                    it.copy(isLoadingScenarios = false, scenariosError = e.message ?: "Lỗi kết nối.")
                }
            }
        }
    }

    fun onUserSentenceChange(text: String) = uiState.update { it.copy(userSentenceInput = text) }

    fun submitSentenceEvaluation() {
        val s = uiState.value
        val word = s.selectedWord ?: return
        val sentence = s.userSentenceInput.trim()
        if (sentence.isBlank()) return

        uiState.update { it.copy(isEvaluatingSentence = true, sentenceEvaluation = null, evaluationError = null) }
        scope.launch {
            try {
                val evaluation = vocabularyRepository.evaluateSentence(word.word, sentence)
                if (evaluation.isFallback) {
                    uiState.update {
                        it.copy(
                            isEvaluatingSentence = false,
                            evaluationError = evaluation.error.ifBlank {
                                "Chưa chấm được câu này. Vui lòng thử lại."
                            }
                        )
                    }
                } else {
                    vocabularyRepository.saveUserSentence(word, evaluation)
                    uiState.update {
                        it.copy(
                            isEvaluatingSentence = false,
                            sentenceEvaluation = evaluation,
                            practicedSentences = (listOf(evaluation) + it.practicedSentences).take(10)
                        )
                    }
                }
            } catch (e: Exception) {
                uiState.update {
                    it.copy(isEvaluatingSentence = false, evaluationError = e.message ?: "Lỗi kết nối.")
                }
            }
        }
    }

    fun generateFlashcardArt(word: SavedWord) {
        if (word.word in uiState.value.generatingArtWords) return
        uiState.update { it.copy(generatingArtWords = it.generatingArtWords + word.word) }
        scope.launch {
            try {
                vocabularyRepository.getOrGenerateFlashcardArt(word)?.let { res ->
                    uiState.update { s -> s.copy(cardArtCache = s.cardArtCache + (word.word to res)) }
                }
            } finally {
                uiState.update { it.copy(generatingArtWords = it.generatingArtWords - word.word) }
            }
        }
    }

    fun loadFlashcardDetailsIfNeeded(word: SavedWord) {
        if (uiState.value.cardArtCache.containsKey(word.word)) return
        if (word.flashcardArtUrl.isBlank()) return
        scope.launch {
            try {
                vocabularyRepository.getOrGenerateFlashcardArt(word)?.let { res ->
                    uiState.update { s -> s.copy(cardArtCache = s.cardArtCache + (word.word to res)) }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun getCollocations(word: SavedWord): List<CollocationItem> {
        uiState.value.cardArtCache[word.word]?.collocations?.takeIf { it.isNotEmpty() }?.let { return it }
        return vocabularyRepository.getCollocations(word)
    }
}
