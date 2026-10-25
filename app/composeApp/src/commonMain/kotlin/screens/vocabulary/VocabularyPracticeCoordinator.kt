package com.truyencloud.compose.screens.vocabulary

import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.practice.PracticeEngine
import com.truyencloud.shared.practice.PracticeItem
import com.truyencloud.shared.practice.PracticeMode
import com.truyencloud.shared.practice.PracticeResult
import com.truyencloud.shared.repo.VocabularyRepository
import com.truyencloud.shared.srs.Rating
import com.truyencloud.shared.srs.ReviewPolicy
import com.truyencloud.shared.tts.TtsSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phòng luyện tập chủ động.
 *
 * Luyện tập KHÔNG tự đổi lịch: kết quả đi vào [VocabularyRepository.reviewWordDetailed] với
 * [ReviewPolicy.SCHEDULE_IF_DUE] — chỉ thẻ thật sự đến hạn (và hôm nay chưa ôn) mới được ghi lịch FSRS,
 * còn lại chỉ là một dòng nhật ký luyện tập. Nhờ vậy luyện lại một từ hay luyện từ chưa đến hạn
 * không làm phình khoảng cách ôn.
 */
class VocabularyPracticeCoordinator(
    private val vocabularyRepository: VocabularyRepository,
    private val ttsSpeaker: TtsSpeaker?,
    private val uiState: MutableStateFlow<VocabularyUiState>,
    private val scope: CoroutineScope,
    private val getCardShownAt: () -> Long,
    private val resetCardShownAt: () -> Unit
) {

    fun startPractice(mode: PracticeMode = PracticeMode.SMART) {
        val s = uiState.value
        // Ưu tiên các thẻ đang trong hàng đợi hôm nay (đến hạn nên kết quả được tính vào lịch); hết thì luyện cả sổ từ.
        val source = s.studyDeck.ifEmpty { s.allWords }.filter { !it.isSuspended }
        val pool = when (mode) {
            PracticeMode.SMART -> source.ifEmpty { s.allWords.filter { !it.isSuspended } }
            PracticeMode.CLOZE -> source.filter { PracticeEngine.isClozeReady(it) }
                .ifEmpty { s.allWords.filter { PracticeEngine.isClozeReady(it) } }
            PracticeMode.TYPING, PracticeMode.RECOGNITION -> source.filter { it.contextMeaningVi.isNotBlank() }
                .ifEmpty { s.allWords.filter { it.contextMeaningVi.isNotBlank() } }
            else -> source
        }

        uiState.update {
            it.copy(
                activeMode = VocabMode.PRACTICE,
                practiceMode = mode,
                practiceQueue = pool,
                practiceIndex = 0,
                practiceInput = "",
                practiceResult = null,
                practiceCorrectCount = 0,
                practiceTotalCount = 0
            )
        }
        buildCurrentPracticeItem()
    }

    /**
     * Dựng đề cho từ hiện tại. Không dựng được (thiếu câu gốc, thiếu nghĩa, ít từ để làm lựa chọn sai...) thì
     * thử dạng Gõ lại rồi Nhận diện — cả hai đều KHÔNG hiện đáp án trong đề — và nếu vẫn không được thì bỏ qua từ đó.
     */
    fun buildCurrentPracticeItem() {
        val s = uiState.value
        var index = s.practiceIndex
        var item: PracticeItem? = null
        while (index < s.practiceQueue.size && item == null) {
            item = buildItemFor(s.practiceQueue[index], s)
            if (item == null) index++
        }
        resetCardShownAt()
        uiState.update { it.copy(practiceIndex = index, practiceItem = item, practiceInput = "", practiceResult = null) }

        if (item != null && item.mode == PracticeMode.LISTENING) {
            item.audioText.takeIf { it.isNotBlank() }?.let { speakSentence(it) }
        }
    }

    private fun buildItemFor(word: SavedWord, s: VocabularyUiState): PracticeItem? {
        val collocations = vocabularyRepository.getCollocations(word).map { it.phrase }
        val others = s.allWords.filter { it.id != word.id }
        val collocationPool = others
            .flatMap { vocabularyRepository.getCollocations(it).map { c -> c.phrase } }
            .filter { it.isNotBlank() }
        val meaningPool = others.map { it.contextMeaningVi }.filter { it.isNotBlank() }

        return PracticeEngine.build(word, s.practiceMode, collocations, collocationPool, meaningPool)
            ?: PracticeEngine.build(word, PracticeMode.TYPING)
            ?: PracticeEngine.build(word, PracticeMode.RECOGNITION, meaningDistractors = meaningPool)
    }

    fun onPracticeInputChange(text: String) = uiState.update { it.copy(practiceInput = text) }

    fun replayPracticeAudio() {
        uiState.value.practiceItem?.audioText?.takeIf { it.isNotBlank() }?.let { speakSentence(it) }
    }

    fun submitPracticeAnswer() {
        val s = uiState.value
        val item = s.practiceItem ?: return
        if (s.practiceResult != null) return

        val result = when (item.mode) {
            // Nghe chép phải chép được cả TỪ ĐÍCH mới được điểm cao.
            PracticeMode.LISTENING -> PracticeEngine.gradeDictation(item.answer, s.practiceInput, item.word.word)
            // Phát âm chấm bằng giọng nói thật (PronunciationSheet), không bao giờ bằng chữ gõ.
            PracticeMode.PRONUNCIATION -> return
            else -> PracticeEngine.gradeTyped(item.answer, s.practiceInput)
        }
        record(item, result, responseMs())
    }

    fun selectPracticeOption(index: Int) {
        val s = uiState.value
        val item = s.practiceItem ?: return
        if (s.practiceResult != null) return
        val correct = index == item.correctOptionIndex
        val result = PracticeResult(
            isCorrect = correct,
            isNearMiss = false,
            expected = item.answer,
            actual = item.options.getOrElse(index) { "" },
            suggestedRating = if (correct) 3 else 1,
            feedback = if (correct) "Chính xác!" else "Đáp án đúng: ${item.answer}"
        )
        record(item, result, responseMs())
    }

    /** Điểm phát âm THẬT từ PronunciationSheet; chỉ ghi nhật ký luyện tập (không đổi lịch). */
    fun onPronunciationScored(word: SavedWord, score: Int) {
        val result = PracticeEngine.gradePronunciation(word.word, score)
        val item = uiState.value.practiceItem
        if (item != null && item.mode == PracticeMode.PRONUNCIATION && item.word.id == word.id && uiState.value.practiceResult == null) {
            uiState.update {
                it.copy(
                    practiceResult = result,
                    practiceCorrectCount = it.practiceCorrectCount + if (result.isCorrect) 1 else 0,
                    practiceTotalCount = it.practiceTotalCount + 1
                )
            }
        }
        scope.launch {
            vocabularyRepository.reviewWordDetailed(
                word, Rating.from(result.suggestedRating), PracticeMode.PRONUNCIATION.id, 0L, ReviewPolicy.PRACTICE_ONLY
            )
        }
    }

    private fun record(item: PracticeItem, result: PracticeResult, elapsed: Long) {
        uiState.update {
            it.copy(
                practiceResult = result,
                practiceCorrectCount = it.practiceCorrectCount + if (result.isCorrect) 1 else 0,
                practiceTotalCount = it.practiceTotalCount + 1
            )
        }
        scope.launch {
            vocabularyRepository.reviewWordDetailed(
                word = item.word,
                rating = Rating.from(result.suggestedRating),
                reviewMode = item.mode.id,
                responseMs = elapsed,
                policy = ReviewPolicy.SCHEDULE_IF_DUE
            )
        }
    }

    private fun responseMs(): Long {
        val shownAt = getCardShownAt()
        return if (shownAt > 0L) com.truyencloud.shared.data.getCurrentEpochMillis() - shownAt else 0L
    }

    fun openPronunciation(word: SavedWord) {
        uiState.update { it.copy(pronunciationWord = word) }
    }

    fun closePronunciation() {
        uiState.update { it.copy(pronunciationWord = null) }
    }

    fun nextPracticeItem() {
        val s = uiState.value
        if (s.practiceIndex + 1 >= s.practiceQueue.size) {
            uiState.update { it.copy(practiceItem = null, practiceResult = null) }
            return
        }
        uiState.update { it.copy(practiceIndex = it.practiceIndex + 1) }
        buildCurrentPracticeItem()
    }

    fun setPracticeMode(mode: PracticeMode) = startPractice(mode)

    private fun speakSentence(text: String) {
        ttsSpeaker?.speakWord(text.trim(), language = "en")
    }
}
