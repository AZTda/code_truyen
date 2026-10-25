package com.truyencloud.shared.practice

import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.repo.EnglishLemmatizer

/**
 * Các chế độ luyện tập chủ động.
 *
 * Vì sao cần: vòng học cũ chỉ có "lật thẻ rồi tự chấm là mình có nhớ không" —
 * đây là chế độ yếu nhất trong mọi chế độ ôn tập và là lý do người học
 * "thấy quen mặt chữ mà không dùng được". Các chế độ dưới đây bắt người học
 * phải SẢN SINH ra từ chứ không chỉ nhận diện nó.
 */
enum class PracticeMode(val id: String, val label: String, val description: String) {
    SMART("smart", "Thông minh", "Tự động chọn dạng bài theo độ thuộc của từng từ (Nhận diện -> Điền khuyết -> Gõ lại -> Nghe chép)"),
    RECOGNITION("recognition", "Nhận diện", "Thấy từ tiếng Anh, chọn nghĩa tiếng Việt đúng — nhẹ nhàng, hợp với thẻ mới"),
    CLOZE("cloze", "Điền khuyết", "Điền từ vào đúng câu trong truyện bạn đã đọc"),
    TYPING("typing", "Gõ lại", "Nhìn nghĩa tiếng Việt, gõ lại từ tiếng Anh"),
    LISTENING("listening", "Nghe chép", "Nghe câu rồi gõ lại — kỹ năng yếu nhất của người Việt"),
    COLLOCATION("collocation", "Cụm từ", "Chọn cụm từ đi với nhau đúng chuẩn bản ngữ"),
    PRONUNCIATION("pronunciation", "Phát âm", "Nói vào micro, máy nghe và chấm điểm từng âm — không chấm bằng gõ chữ")
}

/** Một lượt luyện tập đã được dựng sẵn để hiển thị. */
data class PracticeItem(
    val word: SavedWord,
    val mode: PracticeMode,
    /** Câu hiển thị, từ đích đã bị che bằng ___ (với chế độ điền khuyết). */
    val promptText: String = "",
    /** Nghĩa tiếng Việt gợi ý. */
    val hintVi: String = "",
    /** Đáp án đúng. */
    val answer: String = "",
    /** Văn bản để đọc lên (chế độ nghe chép). */
    val audioText: String = "",
    /** Các lựa chọn (chế độ cụm từ). */
    val options: List<String> = emptyList(),
    val correctOptionIndex: Int = 0
)

/** Kết quả chấm một lượt luyện tập. */
data class PracticeResult(
    val isCorrect: Boolean,
    /** Đúng nhưng sai chính tả nhẹ — vẫn tính là nhớ được. */
    val isNearMiss: Boolean,
    val expected: String,
    val actual: String,
    /** Điểm FSRS đề xuất: 1 Quên, 2 Khó, 3 Tốt, 4 Dễ. */
    val suggestedRating: Int,
    val feedback: String
)

object PracticeEngine {

    /** Ký tự che từ trong câu điền khuyết. */
    const val BLANK = "＿＿＿＿"

    /**
     * Chọn chế độ luyện phù hợp với mức độ thuộc của từ.
     *
     * Thẻ mới thì nhận diện cho nhẹ; càng thuộc càng phải sản sinh chủ động,
     * vì đó là lúc bài tập khó mới tạo ra giá trị ghi nhớ.
     */
    fun suggestMode(word: SavedWord, allowListening: Boolean = true): PracticeMode {
        val clozeOk = isClozeReady(word)
        return when {
            word.stability <= 0.0 -> PracticeMode.RECOGNITION
            word.stability < 3.0 -> PracticeMode.RECOGNITION
            clozeOk && word.stability < 15.0 -> PracticeMode.CLOZE
            word.stability < 30.0 -> PracticeMode.TYPING
            allowListening && clozeOk -> PracticeMode.LISTENING
            else -> PracticeMode.TYPING
        }
    }

    /**
     * Dựng một lượt luyện tập.
     * Trả về null nếu thẻ thiếu dữ liệu cho chế độ được yêu cầu (ví dụ chưa có
     * câu gốc trong truyện thì không tạo được bài điền khuyết).
     *
     * QUY TẮC: đáp án KHÔNG được xuất hiện trong đề. Nhận diện cho thấy từ tiếng Anh và bắt chọn NGHĨA
     * (đáp án nằm ở các lựa chọn); bản cũ hiện chính từ đó rồi bắt gõ lại đúng từ đó — gõ lại là đúng.
     *
     * @param meaningDistractors nghĩa tiếng Việt của các thẻ khác, làm lựa chọn sai cho chế độ Nhận diện.
     */
    fun build(
        word: SavedWord,
        mode: PracticeMode,
        collocations: List<String> = emptyList(),
        distractors: List<String> = emptyList(),
        meaningDistractors: List<String> = emptyList()
    ): PracticeItem? = when (mode) {
        PracticeMode.SMART -> {
            val target = suggestMode(word)
            build(word, target, collocations, distractors, meaningDistractors)
                ?: build(word, PracticeMode.CLOZE, collocations, distractors, meaningDistractors)
                ?: build(word, PracticeMode.TYPING)
                ?: build(word, PracticeMode.RECOGNITION, meaningDistractors = meaningDistractors)
        }

        PracticeMode.RECOGNITION -> buildRecognition(word, meaningDistractors)

        PracticeMode.CLOZE -> {
            val masked = maskWordInSentence(word.contextSentence, word.word)
            if (masked == null) null else PracticeItem(
                word = word,
                mode = mode,
                promptText = masked.text,
                hintVi = word.contextMeaningVi,
                answer = masked.answer,
                audioText = word.contextSentence
            )
        }

        PracticeMode.TYPING -> {
            if (word.contextMeaningVi.isBlank()) null else PracticeItem(
                word = word,
                mode = mode,
                promptText = word.contextMeaningVi,
                hintVi = firstLetterHint(word.word),
                answer = word.word
            )
        }

        PracticeMode.LISTENING -> {
            val sentence = word.contextSentence.ifBlank { word.word }
            PracticeItem(
                word = word,
                mode = mode,
                promptText = "",
                hintVi = word.contextMeaningVi,
                answer = sentence,
                audioText = sentence
            )
        }

        PracticeMode.COLLOCATION -> {
            val correct = collocations.firstOrNull { it.isNotBlank() }
            val pool = distractors
                .filter { it.isNotBlank() && it != correct }
                .distinct()
                .shuffled()
                .take(3)
            if (correct == null || pool.size < 2) {
                null
            } else {
                val options = (pool + correct).shuffled()
                PracticeItem(
                    word = word,
                    mode = mode,
                    promptText = word.word,
                    hintVi = word.contextMeaningVi,
                    answer = correct,
                    options = options,
                    correctOptionIndex = options.indexOf(correct)
                )
            }
        }

        // Phát âm KHÔNG chấm bằng chữ gõ: điểm đến từ bản chép lời thật của bộ nhận dạng giọng nói
        // (PronunciationSheet). Đề chỉ hiện từ + nút nghe mẫu / nút nói.
        PracticeMode.PRONUNCIATION -> PracticeItem(
            word = word,
            mode = mode,
            promptText = word.word,
            hintVi = word.contextMeaningVi,
            answer = word.word,
            audioText = word.word
        )
    }

    private fun buildRecognition(word: SavedWord, meaningDistractors: List<String>): PracticeItem? {
        val correct = word.contextMeaningVi.trim()
        if (correct.isBlank()) return null
        val pool = meaningDistractors
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals(correct, ignoreCase = true) }
            .distinct()
            .shuffled()
            .take(3)
        if (pool.size < 2) return null
        val options = (pool + correct).shuffled()
        return PracticeItem(
            word = word,
            mode = PracticeMode.RECOGNITION,
            promptText = word.word,
            hintVi = "",
            answer = correct,
            audioText = word.word,
            options = options,
            correctOptionIndex = options.indexOf(correct)
        )
    }

    /** Một câu đã che từ đích và chính chữ bị che (có thể là dạng chia: "running" khi từ là "run"). */
    data class MaskedSentence(val text: String, val answer: String)

    /**
     * Che từ đích trong câu.
     * Khớp theo ranh giới từ và không phân biệt hoa thường; nếu câu chỉ chứa DẠNG CHIA của từ
     * ("ran" / "running" khi thẻ là "run") thì che dạng đó và lấy chính nó làm đáp án.
     * Trả null nếu không tìm thấy.
     */
    fun maskWordInSentence(sentence: String, word: String): MaskedSentence? {
        if (sentence.isBlank() || word.isBlank()) return null
        val exact = maskExact(sentence, word)
        if (exact != null) return exact
        return maskInflected(sentence, word)
    }

    private fun maskExact(sentence: String, word: String): MaskedSentence? {
        val lower = sentence.lowercase()
        val target = word.lowercase()

        var searchFrom = 0
        while (true) {
            val idx = lower.indexOf(target, searchFrom)
            if (idx < 0) return null
            val before = if (idx == 0) ' ' else sentence[idx - 1]
            val afterIdx = idx + target.length
            val after = if (afterIdx >= sentence.length) ' ' else sentence[afterIdx]
            if (!before.isLetter() && !after.isLetter()) {
                return MaskedSentence(
                    text = sentence.substring(0, idx) + BLANK + sentence.substring(afterIdx),
                    answer = sentence.substring(idx, afterIdx)
                )
            }
            searchFrom = idx + 1
        }
    }

    private fun maskInflected(sentence: String, word: String): MaskedSentence? {
        val lemma = EnglishLemmatizer.lemmatize(word)
        val wordLower = word.trim().lowercase()
        val wordIsPossessive = wordLower.endsWith("'s") || wordLower.endsWith("’s")
        var i = 0
        while (i < sentence.length) {
            if (!sentence[i].isLetter()) { i++; continue }
            var j = i
            while (j < sentence.length && (sentence[j].isLetter() || sentence[j] == '\'' || sentence[j] == '’')) j++
            val token = sentence.substring(i, j)
            if (token.length > 2 && EnglishLemmatizer.lemmatize(token) == lemma) {
                val tokenLower = token.lowercase()
                val end = if (!wordIsPossessive && (tokenLower.endsWith("'s") || tokenLower.endsWith("’s"))) {
                    j - 2
                } else {
                    j
                }
                val maskedAnswer = sentence.substring(i, end)
                return MaskedSentence(sentence.substring(0, i) + BLANK + sentence.substring(end), maskedAnswer)
            }
            i = j
        }
        return null
    }

    /** Gợi ý chữ cái đầu và độ dài: "e _ _ _ _ _ _ _ _ (9 chữ)". */
    fun firstLetterHint(word: String): String {
        if (word.isBlank()) return ""
        val body = (1 until word.length).joinToString(" ") { "_" }
        return "${word.first()} $body (${word.length} chữ)"
    }

    /** Chấm câu trả lời gõ tay — xem [PracticeGrading.gradeTyped]. */
    fun gradeTyped(expected: String, actual: String): PracticeResult =
        PracticeGrading.gradeTyped(expected, actual)

    /** Chấm bài nghe chép; truyền [targetWord] để bắt buộc bản chép có từ đích — xem [PracticeGrading.gradeDictation]. */
    fun gradeDictation(expected: String, actual: String, targetWord: String? = null): PracticeResult =
        PracticeGrading.gradeDictation(expected, actual, targetWord)

    /** Quy điểm phát âm thật ra điểm FSRS đề xuất — xem [PracticeGrading.gradePronunciation]. */
    fun gradePronunciation(word: String, score: Int): PracticeResult =
        PracticeGrading.gradePronunciation(word, score)

    fun levenshtein(a: String, b: String): Int = PracticeGrading.levenshtein(a, b)

    /** Thẻ này dựng được bài điền khuyết không (câu gốc chứa từ hoặc dạng chia của nó). */
    fun isClozeReady(word: SavedWord): Boolean = maskWordInSentence(word.contextSentence, word.word) != null

    /** Số thẻ có thể luyện bằng chế độ điền khuyết (cần câu gốc trong truyện). */
    fun clozeReadyCount(words: List<SavedWord>): Int = words.count { isClozeReady(it) }
}
