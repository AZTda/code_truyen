package com.truyencloud.shared.practice

import com.truyencloud.shared.repo.EnglishLemmatizer
import kotlin.math.min

/**
 * Chấm điểm các bài luyện tập. Tách khỏi [PracticeEngine] (dựng đề) để mỗi tệp một trách nhiệm.
 *
 * Nguyên tắc chống "chơi lịch": bài luyện không bao giờ tự cho điểm Dễ chỉ vì người học gõ lại chữ đang
 * hiện trên màn hình, và bài nghe chép phải chứa được từ đích mới đạt điểm cao.
 */
object PracticeGrading {

    /**
     * Chấm câu trả lời gõ tay.
     *
     * Sai chính tả nhẹ (khoảng cách Levenshtein <= 1, hoặc <= 2 với từ dài)
     * vẫn tính là NHỚ ĐƯỢC — mục tiêu là đo trí nhớ từ vựng, không phải kiểm tra đánh máy.
     */
    fun gradeTyped(expected: String, actual: String): PracticeResult {
        val e = expected.trim().lowercase()
        val a = actual.trim().lowercase()

        if (a.isBlank()) {
            return PracticeResult(
                isCorrect = false, isNearMiss = false, expected = expected, actual = actual,
                suggestedRating = 1, feedback = "Chưa nhập câu trả lời."
            )
        }
        if (e == a) {
            return PracticeResult(
                isCorrect = true, isNearMiss = false, expected = expected, actual = actual,
                suggestedRating = 3, feedback = "Chính xác!"
            )
        }

        val normE = normalizeForCompare(e)
        val normA = normalizeForCompare(a)
        if (normE == normA) {
            return PracticeResult(
                isCorrect = true, isNearMiss = true, expected = expected, actual = actual,
                suggestedRating = 3, feedback = "Đúng rồi (chỉ khác dấu câu / khoảng trắng)."
            )
        }

        val distance = levenshtein(normE, normA)
        val tolerance = if (normE.length >= 8) 2 else 1
        if (distance <= tolerance) {
            return PracticeResult(
                isCorrect = true, isNearMiss = true, expected = expected, actual = actual,
                suggestedRating = 2,
                feedback = "Gần đúng — bạn nhớ được từ, chỉ sai $distance ký tự. Đáp án: $expected"
            )
        }

        return PracticeResult(
            isCorrect = false, isNearMiss = false, expected = expected, actual = actual,
            suggestedRating = 1, feedback = "Chưa đúng. Đáp án: $expected"
        )
    }

    /**
     * Chấm bài nghe chép: so theo từ, cho phép sai vài từ nhỏ.
     *
     * @param targetWord từ đích của thẻ. Nếu truyền vào mà bản chép KHÔNG chứa từ này (kể cả dạng chia /
     * sai chính tả nhẹ) thì điểm tối đa là Khó — chép đúng cả câu trừ chính từ cần học nghĩa là chưa học được từ đó.
     */
    fun gradeDictation(expected: String, actual: String, targetWord: String? = null): PracticeResult {
        val eWords = tokenize(expected)
        val aWords = tokenize(actual)
        if (aWords.isEmpty()) {
            return PracticeResult(false, false, expected, actual, 1, "Chưa nhập câu trả lời.")
        }

        val matched = countMatchingWords(eWords, aWords)
        val ratio = if (eWords.isEmpty()) 0.0 else matched.toDouble() / eWords.size
        val base = when {
            ratio >= 0.95 -> PracticeResult(true, false, expected, actual, 4, "Nghe chuẩn từng chữ!")
            ratio >= 0.80 -> PracticeResult(true, true, expected, actual, 3,
                "Tốt — nghe đúng ${(ratio * 100).toInt()}%. Câu gốc: $expected")
            ratio >= 0.55 -> PracticeResult(true, true, expected, actual, 2,
                "Nghe được ${(ratio * 100).toInt()}%. Hãy nghe lại câu gốc: $expected")
            else -> PracticeResult(false, false, expected, actual, 1,
                "Mới đúng ${(ratio * 100).toInt()}%. Câu gốc: $expected")
        }

        if (targetWord.isNullOrBlank() || containsTarget(aWords, targetWord)) return base
        return base.copy(
            isCorrect = base.isCorrect && base.suggestedRating >= 2,
            isNearMiss = true,
            suggestedRating = min(base.suggestedRating, 2),
            feedback = "Bạn chưa chép được từ cần học \"$targetWord\". Câu gốc: $expected"
        )
    }

    /**
     * Quy điểm phát âm THẬT (0..100, máy chủ chấm từ bản chép lời của bộ nhận dạng giọng nói) ra điểm FSRS đề xuất.
     * Không bao giờ quá Tốt: nói đúng một từ không chứng minh nhớ nghĩa của nó.
     */
    fun gradePronunciation(word: String, score: Int): PracticeResult {
        val rating = when {
            score >= 85 -> 3
            score >= 60 -> 2
            else -> 1
        }
        return PracticeResult(
            isCorrect = score >= 60,
            isNearMiss = score in 60..84,
            expected = word,
            actual = "",
            suggestedRating = rating,
            feedback = "Điểm phát âm: $score/100"
        )
    }

    private fun containsTarget(actualTokens: List<String>, targetWord: String): Boolean {
        val targets = tokenize(targetWord)
        if (targets.isEmpty()) return true
        return targets.all { t ->
            val lemma = EnglishLemmatizer.lemmatize(t)
            actualTokens.any { a ->
                a == t ||
                    EnglishLemmatizer.lemmatize(a) == lemma ||
                    (t.length >= 5 && levenshtein(a, t) <= 1)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Tiện ích
    // -----------------------------------------------------------------------

    private fun normalizeForCompare(s: String): String =
        s.lowercase()
            .map { if (it.isLetterOrDigit() || it == ' ' || it == '\'' || it == '-') it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ")

    private fun tokenize(s: String): List<String> =
        normalizeForCompare(s).split(' ').filter { it.isNotBlank() }

    private fun countMatchingWords(expected: List<String>, actual: List<String>): Int {
        val remaining = actual.toMutableList()
        var matched = 0
        for (w in expected) {
            val idx = remaining.indexOfFirst { it == w || levenshtein(it, w) <= 1 }
            if (idx >= 0) {
                matched++
                remaining.removeAt(idx)
            }
        }
        return matched
    }

    /** Khoảng cách Levenshtein, cài đặt hai hàng để tiết kiệm bộ nhớ. */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)

        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }
}
