package com.truyencloud.compose.screens.vocabulary

import com.truyencloud.shared.repo.VocabularyRepository
import com.truyencloud.shared.srs.Rating
import com.truyencloud.shared.srs.ReviewPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Quiz tình huống do AI sinh (endpoint /english/practice-quiz). */
class VocabularyQuizCoordinator(
    private val vocabularyRepository: VocabularyRepository,
    private val uiState: MutableStateFlow<VocabularyUiState>,
    private val scope: CoroutineScope
) {
    fun startQuiz() {
        val words = uiState.value.studyDeck.ifEmpty { uiState.value.allWords }
            .take(5).map { it.word }
        if (words.isEmpty()) return

        uiState.update {
            it.copy(
                isLoadingQuiz = true, quizQuestions = emptyList(), quizIndex = 0,
                quizSelectedOption = null, quizCorrectCount = 0, quizError = null
            )
        }
        scope.launch {
            try {
                val res = vocabularyRepository.generateQuiz(words)
                val valid = res.validQuestions
                uiState.update {
                    it.copy(
                        isLoadingQuiz = false,
                        quizQuestions = valid,
                        quizError = if (valid.isEmpty()) "Chưa tạo được câu hỏi. Vui lòng thử lại." else null
                    )
                }
            } catch (e: Exception) {
                uiState.update {
                    it.copy(isLoadingQuiz = false, quizError = e.message ?: "Không kết nối được máy chủ.")
                }
            }
        }
    }

    fun answerQuiz(optionIndex: Int) {
        val s = uiState.value
        val q = s.quizQuestions.getOrNull(s.quizIndex) ?: return
        if (s.quizSelectedOption != null) return
        val correct = optionIndex == q.correctAnswerIndex
        uiState.update {
            it.copy(
                quizSelectedOption = optionIndex,
                quizCorrectCount = it.quizCorrectCount + if (correct) 1 else 0
            )
        }
        val word = s.allWords.firstOrNull { it.word.equals(q.targetWord, ignoreCase = true) }
        if (word != null) {
            scope.launch {
                // Quiz trắc nghiệm chỉ đổi lịch khi thẻ thật sự đến hạn; ngoài ra là luyện tập.
                vocabularyRepository.reviewWordDetailed(
                    word, if (correct) Rating.GOOD else Rating.AGAIN, "quiz", 0L, ReviewPolicy.SCHEDULE_IF_DUE
                )
            }
        }
    }

    fun nextQuizQuestion() {
        uiState.update {
            if (it.quizIndex < it.quizQuestions.size) {
                it.copy(quizIndex = it.quizIndex + 1, quizSelectedOption = null)
            } else {
                it.copy(quizSelectedOption = null)
            }
        }
    }

    fun dismissQuiz() = uiState.update {
        it.copy(quizQuestions = emptyList(), quizIndex = 0, quizSelectedOption = null, quizError = null)
    }
}
