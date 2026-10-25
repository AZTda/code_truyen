package com.truyencloud.shared.repo

/**
 * Bộ chuẩn hóa và rút gọn từ tiếng Anh (Lemmatization & Normalization).
 *
 * Rút gọn về dạng nguyên thể ngay trên thiết bị, không gọi mạng. Thứ tự tra:
 *  1. dạng bất quy tắc ("ran" -> "run", "went" -> "go", "better" -> "good", "children" -> "child");
 *  2. từ vốn đã là nguyên thể nhưng trông như đã chia ("news", "during", "evening");
 *  3. luật hậu tố -s/-es/-ies, -ed/-ied, -ing (xem [EnglishLemmaRules]).
 *
 * Khoá lemma này dùng để gộp thẻ: lưu "running" khi đã có thẻ "run" thì gắn thêm câu ngữ cảnh
 * vào thẻ cũ thay vì tạo thẻ trùng.
 */
object EnglishLemmatizer {

    fun normalizeWord(word: String): String =
        word.lowercase().trim().trim('"', '\'', '.', ',', '!', '?', ';', ':', '(', ')', '[', ']', '“', '”', '‘', '’')

    fun lemmatize(raw: String): String {
        var w = normalizeWord(raw)
        // Sở hữu cách: "John's" -> "john"
        if (w.endsWith("'s") || w.endsWith("’s")) w = w.dropLast(2)
        if (w.isEmpty()) return w

        EnglishLemmaLexicon.IRREGULAR[w]?.let { return it }
        if (w in EnglishLemmaLexicon.BASE_FORMS) return w
        if (w.length <= 3) return w
        // Chỉ chữ cái a-z: tiếng Việt / Trung / số / từ ghép có gạch nối giữ nguyên.
        if (!w.all { it in 'a'..'z' }) return w

        return when {
            w.endsWith("ing") -> EnglishLemmaRules.fromIng(w)
            w.endsWith("ed") -> EnglishLemmaRules.fromEd(w)
            // "peoples" -> "people" -> "person", "thoughts" -> "thought" -> "think"
            w.endsWith("s") -> EnglishLemmaRules.fromPlural(w).let { EnglishLemmaLexicon.IRREGULAR[it] ?: it }
            else -> w
        }
    }
}
