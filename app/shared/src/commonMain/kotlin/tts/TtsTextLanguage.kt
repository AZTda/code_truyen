package com.truyencloud.shared.tts

import kotlin.concurrent.Volatile

/**
 * Nhận diện ngôn ngữ của một đoạn để chọn giọng / tham số `lang` cho máy chủ.
 *
 * Quyết định theo TỈ LỆ ký tự, không theo "có một ký tự là đổi": bản dịch tiên hiệp hay còn sót vài chữ Hán (tên
 * chiêu thức, câu thơ), còn bản Anh thì có tên riêng tiếng Việt ('Nguyễn'). Một ký tự lạ không được kéo cả đoạn
 * sang giọng tiếng Trung / tiếng Việt (đọc sai cả câu và máy chủ bỏ qua tốc độ).
 *
 * Đoạn quá ngắn hoặc không có chữ ("12", "Minh.", "Ha ha!") không đủ bằng chứng: đoán theo ngôn ngữ chủ đạo của văn bản
 * đang đọc ([useContextOf]) thay vì mặc định tiếng Anh — nếu không, tên người không dấu và số trong truyện Việt bị giọng Anh đọc.
 */
object TtsTextLanguage {
    /** Chữ Hán chiếm từ tỉ lệ này trên tổng số chữ cái thì cả đoạn (hay cả chương) được coi là tiếng Trung. */
    const val CHINESE_RATIO = 0.3f

    /** Chữ có dấu tiếng Việt chiếm từ tỉ lệ này thì đoạn là tiếng Việt (văn Việt thường ~30%, tên riêng lẻ chỉ vài %). */
    const val VIETNAMESE_RATIO = 0.15f

    /** Dưới tỉ lệ [VIETNAMESE_RATIO] mà còn dấu Việt (tên riêng) thì cần ít nhất ngần này từ Anh phổ biến mới coi là tiếng Anh. */
    private const val MIN_ENGLISH_WORDS_WITH_VIETNAMESE_NAME = 2

    /** Chương có ít nhất ngần này chữ có dấu tiếng Việt là có lời dịch / dẫn tiếng Việt, không phải bản gốc chưa dịch. */
    private const val MIN_VIETNAMESE_NARRATION = 10

    /** Một hai từ không dấu trùng từ Anh ("A!", "No.", "Do it") chưa đủ chắc là tiếng Anh: từ ba từ trở lên mới tin. */
    private const val MIN_WORDS_FOR_ENGLISH_EVIDENCE = 3

    /** Số đoạn lấy mẫu để tìm ngôn ngữ chủ đạo của một chương (đủ chắc, không tốn thời gian ở chương dài). */
    private const val CONTEXT_SAMPLE_SEGMENTS = 80

    private const val MIN_ASCII_VIETNAMESE_MARKERS = 2

    /** Từ tiếng Việt không dấu mà tiếng Anh gần như không bao giờ dùng (cùng danh sách với máy chủ: tts_language.py). */
    private val ASCII_VIETNAMESE_MARKERS: Set<String> = setOf(
        "khong", "nguoi", "duoc", "nhung", "cua", "roi", "cung", "toi", "voi", "nhu", "nhieu", "phai", "biet", "muon",
        "vua", "nhin", "ngay", "truoc", "chua", "cuoi", "thich"
    )

    private const val VI_LETTERS =
        "àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ"
    private val viLetters: Set<Char> = VI_LETTERS.toSet()

    private val enWords = Regex(
        "\\b(the|and|that|have|for|not|with|you|this|but|his|from|they|she|her|was|him|were|into|could|been|their|a|an|in|on|at|to|of|is|are|am|it|he|me|my|we|us|our|so|if|as|what|who|how|why|can|will|just|no|all|do|did|does|had|said|say|see|look|come|go|get|out|up|down|over|back|then|now|them|about|like|one|two|first|more|some|very|time)\\b",
        RegexOption.IGNORE_CASE
    )
    private val whitespace = Regex("\\s+")

    /** Ngôn ngữ chủ đạo của văn bản đang đọc ("vi" / "en" / "zh"); null = chưa biết. Do bộ đọc đặt khi nạp văn bản mới. */
    @Volatile
    private var context: String? = null

    fun isCjk(c: Char): Boolean = c in '一'..'鿿'

    private fun isVietnameseLetter(c: Char): Boolean = c.lowercaseChar() in viLetters

    /** Đếm chữ cái, chữ Hán và chữ có dấu tiếng Việt trong [text] (dừng sau [limit] chữ cái nếu [limit] > 0). */
    private class Counts(var letters: Int = 0, var cjk: Int = 0, var vietnamese: Int = 0)

    private fun count(text: String, into: Counts = Counts(), limit: Int = 0): Counts {
        for (c in text) {
            if (limit > 0 && into.letters >= limit) break
            when {
                isCjk(c) -> { into.cjk++; into.letters++ }
                c.isLetter() -> { into.letters++; if (isVietnameseLetter(c)) into.vietnamese++ }
            }
        }
        return into
    }

    /** STRONG: đủ bằng chứng. WEAK: chỉ là chữ Latin ngắn / không dấu. NONE: không có chữ cái nào (số, dấu câu). */
    private enum class Evidence { STRONG, WEAK, NONE }

    private class Verdict(val lang: String, val evidence: Evidence)

    private fun classify(text: String): Verdict {
        val whole = count(text)
        if (whole.cjk > 0 && whole.cjk.toFloat() / whole.letters >= CHINESE_RATIO) return Verdict("zh", Evidence.STRONG)
        if (whole.letters == 0) return Verdict("vi", Evidence.NONE)

        val sample = text.take(200)
        val n = count(sample)
        val englishHits = enWords.findAll(sample).count()
        if (n.vietnamese > 0) {
            if (n.vietnamese.toFloat() / n.letters >= VIETNAMESE_RATIO) return Verdict("vi", Evidence.STRONG)
            // Chỉ vài chữ có dấu (tên riêng như 'Nguyễn'): cần bằng chứng tiếng Anh rõ ràng, kẻo câu Việt ngắn có từ
            // trùng tiếng Anh ('to', 'no', 'do'...) bị đọc bằng giọng Anh
            val english = englishHits >= MIN_ENGLISH_WORDS_WITH_VIETNAMESE_NAME
            return Verdict(if (english) "en" else "vi", Evidence.STRONG)
        }
        val words = sample.split(whitespace).filter { it.isNotBlank() }
        // Tiếng Việt viết KHÔNG dấu ("Toi khong biet nguoi do la ai"): 'do' trùng từ Anh nên bị coi là tiếng Anh. Từ
        // Việt không dấu mà tiếng Anh gần như không dùng: từ hai từ trở lên và không ít hơn số từ Anh mới đủ chắc.
        val vietnameseHits = words.count { word -> word.filter { it.isLetter() }.lowercase() in ASCII_VIETNAMESE_MARKERS }
        if (vietnameseHits >= MIN_ASCII_VIETNAMESE_MARKERS && vietnameseHits >= englishHits) {
            return Verdict("vi", Evidence.STRONG)
        }
        if (englishHits > 0) {
            return Verdict("en", if (words.size >= MIN_WORDS_FOR_ENGLISH_EVIDENCE) Evidence.STRONG else Evidence.WEAK)
        }
        val ascii = words.all { w -> w.all { c -> c.code < 128 } }
        return Verdict(if (ascii) "en" else "vi", Evidence.WEAK)
    }

    /**
     * Ngôn ngữ giọng đọc của [text]. Đủ bằng chứng thì theo chính đoạn; không thì theo ngôn ngữ chủ đạo của văn bản đang
     * đọc (chưa biết thì như bản cũ: chữ Latin ngắn -> "en", chỉ số / dấu câu -> "vi"). Số trong văn bản tiếng Trung
     * theo giọng Trung, còn chữ Latin lẻ trong đó vẫn đọc theo tiếng Anh.
     */
    fun langParam(text: String): String {
        val verdict = classify(text)
        if (verdict.evidence == Evidence.STRONG) return verdict.lang
        val ctx = context ?: return verdict.lang
        return if (ctx == "zh" && verdict.evidence == Evidence.WEAK) verdict.lang else ctx
    }

    /** Chữ Hán chiếm phần lớn đoạn ([CHINESE_RATIO]); vài chữ Hán lẻ trong câu Việt / Anh thì không tính. */
    fun isChinese(text: String): Boolean = langParam(text) == "zh"

    fun isEnglish(text: String): Boolean = langParam(text) == "en"

    /** Ngôn ngữ chiếm nhiều nhất trong các đoạn đủ bằng chứng của [texts]; null nếu chưa đoạn nào đủ chắc. Hoà thì ưu tiên Việt. */
    fun dominantLanguage(texts: List<String>): String? {
        if (texts.isEmpty()) return null
        val step = (texts.size / CONTEXT_SAMPLE_SEGMENTS).coerceAtLeast(1)
        var vi = 0
        var en = 0
        var zh = 0
        var i = 0
        while (i < texts.size) {
            val text = texts[i]
            if (text.isNotBlank()) {
                val verdict = classify(text)
                if (verdict.evidence == Evidence.STRONG) {
                    when (verdict.lang) {
                        "zh" -> zh++
                        "en" -> en++
                        else -> vi++
                    }
                }
            }
            i += step
        }
        val best = maxOf(vi, en, zh)
        return when {
            best == 0 -> null
            vi == best -> "vi"
            en == best -> "en"
            else -> "zh"
        }
    }

    /** Bộ đọc nạp văn bản mới: các đoạn không đủ bằng chứng sẽ theo ngôn ngữ chủ đạo của văn bản này. */
    fun useContextOf(texts: List<String>) {
        context = dominantLanguage(texts)
    }

    fun clearContext() {
        context = null
    }

    /**
     * Chương hiển thị bản gốc tiếng Trung (chưa dịch)? Lấy mẫu vài nghìn ký tự đầu: vài chữ Hán lẻ trong bản dịch
     * (tên riêng, chú thích) không được tính là chưa dịch; bản dịch ngắn có đoạn thơ chữ Hán dài (tỉ lệ Hán cao) mà
     * vẫn còn lời dẫn tiếng Việt cũng vậy.
     */
    fun isMostlyChinese(paragraphs: List<String>, sampleChars: Int = 2000): Boolean {
        val n = Counts()
        for (p in paragraphs) {
            count(p, n, limit = sampleChars)
            if (n.letters >= sampleChars) break
        }
        if (n.letters == 0 || n.cjk.toFloat() / n.letters < CHINESE_RATIO) return false
        return n.vietnamese < MIN_VIETNAMESE_NARRATION
    }
}
