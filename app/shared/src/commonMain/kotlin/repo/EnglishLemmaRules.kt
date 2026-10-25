package com.truyencloud.shared.repo

/**
 * Luật hậu tố của [EnglishLemmatizer]: -s / -es / -ies, -ed / -ied, -ing.
 *
 * Bản cũ chỉ cắt chữ rồi đoán "e" bằng độ dài nên sinh ra: playing -> "plaie", reading -> "reade",
 * sitting -> "site", smiled -> "smil", horses -> "hors", news -> "new", during -> "dure". Các luật dưới đây
 * tách rõ từng nhóm lỗi: (1) phụ âm kép do thêm hậu tố, (2) "e" câm bị nuốt, (3) -ses/-xes/-ches...
 */
internal object EnglishLemmaRules {

    private const val VOWELS = "aeiou"
    private const val DOUBLING = "bdgmnprt"

    // -----------------------------------------------------------------------
    // Danh từ số nhiều / động từ ngôi thứ ba (-s)
    // -----------------------------------------------------------------------

    fun fromPlural(w: String): String {
        if (w.endsWith("ies") && w.length > 4) {
            // movies -> movie (không phải "movy"), cities -> city
            return if (w.dropLast(1) in EnglishLemmaLexicon.IE_NOUNS) w.dropLast(1) else w.dropLast(3) + "y"
        }
        if (!w.endsWith("s") || w.endsWith("ss") || w.endsWith("us") || w.endsWith("is")) return w
        val plain = w.dropLast(1)
        val base = when {
            // waltzes -> waltz, blitzes -> blitz (khác "sizes" -> "size": chữ z đơn giữ "e")
            w.endsWith("sses") || w.endsWith("xes") || w.endsWith("zzes") || w.endsWith("tzes") || w.endsWith("shes") ->
                w.dropLast(2)
            w.endsWith("ches") -> if (plain in EnglishLemmaLexicon.CHE_WORDS) plain else w.dropLast(2)
            w.endsWith("uses") -> if (plain in EnglishLemmaLexicon.USE_WORDS) plain else w.dropLast(2)
            w.endsWith("oes") -> if (plain in EnglishLemmaLexicon.OE_WORDS) plain else w.dropLast(2)
            // horses -> horse, cases -> case, rises -> rise, changes -> change
            else -> plain
        }
        // "feelings" -> "feeling" -> "feel": để feel / feeling / feelings cùng một thẻ.
        return if (base.endsWith("ing") && base.length > 5 && base !in EnglishLemmaLexicon.BASE_FORMS) fromIng(base) else base
    }

    // -----------------------------------------------------------------------
    // Quá khứ / phân từ (-ed, -ied)
    // -----------------------------------------------------------------------

    fun fromEd(w: String): String {
        if (w.endsWith("ied") && w.length > 4) return w.dropLast(3) + "y"
        if (w.endsWith("eed")) {
            // agreed -> agree; need / feed / speed / indeed là nguyên thể, giữ nguyên
            val asVerb = w.dropLast(1)
            return if (asVerb in EnglishLemmaLexicon.EE_VERBS) asVerb else w
        }
        if (w.length <= 4) return w
        return restoreStem(w.dropLast(2))
    }

    // -----------------------------------------------------------------------
    // Hiện tại phân từ (-ing)
    // -----------------------------------------------------------------------

    fun fromIng(w: String): String {
        val stem = w.dropLast(3)
        // king / ring / thing / bring / string / spring: gốc quá ngắn hoặc không có nguyên âm -> không phải động từ chia.
        if (stem.length < 2 || stem.none { it in VOWELS || it == 'y' }) return w
        return restoreStem(stem)
    }

    // -----------------------------------------------------------------------
    // Khôi phục gốc từ phần còn lại sau khi bỏ -ed / -ing
    // -----------------------------------------------------------------------

    /** `stem` là chữ đã bỏ hậu tố, vd "stopp", "smil", "hop", "play", "creat". */
    fun restoreStem(stem: String): String {
        val n = stem.length
        if (n < 2) return stem
        val last = stem[n - 1]

        // 1) Phụ âm kép do thêm hậu tố: stopp -> stop, sitt -> sit (nhưng add, egg, err giữ nguyên)
        if (n >= 3 && last == stem[n - 2] && last in DOUBLING) {
            return if (stem in EnglishLemmaLexicon.NATURAL_DOUBLES) stem else stem.dropLast(1)
        }
        // 2) "ll" kiểu Anh: controlled -> control (called -> call giữ nguyên vì "cal" không nằm trong danh sách)
        if (n >= 4 && last == 'l' && stem[n - 2] == 'l' && stem.dropLast(1) in EnglishLemmaLexicon.LL_UNDOUBLE) {
            return stem.dropLast(1)
        }
        // 3) Từ điển ngoại lệ
        if (stem in EnglishLemmaLexicon.NO_E_STEMS) return stem
        if (stem in EnglishLemmaLexicon.SILENT_E_STEMS) return stem + "e"
        if ("${stem}e" in EnglishLemmaLexicon.NG_E_WORDS) return stem + "e"
        // 4) Luật chung
        return if (needsSilentE(stem)) stem + "e" else stem
    }

    private fun isVowel(c: Char) = c in VOWELS

    /** 'y' chỉ là nguyên âm khi theo sau một phụ âm (rhyme, try); đếm số nhóm nguyên âm. */
    fun syllables(s: String): Int {
        var count = 0
        var inGroup = false
        for ((i, c) in s.withIndex()) {
            val vowel = isVowel(c) || (c == 'y' && i > 0 && !isVowel(s[i - 1]))
            if (vowel && !inGroup) count++
            inGroup = vowel
        }
        return count
    }

    /** Đúng một nguyên âm đơn đứng giữa hai phụ âm ở cuối, một âm tiết: hop, smil, writ, tap. */
    private fun isShortCvc(s: String): Boolean {
        val n = s.length
        if (n < 3 || syllables(s) != 1) return false
        val last = s[n - 1]
        if (isVowel(last) || last in "wxy") return false
        return isVowel(s[n - 2]) && !isVowel(s[n - 3])
    }

    private fun needsSilentE(s: String): Boolean {
        val n = s.length
        val last = s[n - 1]
        val prev = s[n - 2]
        val third = if (n >= 3) s[n - 3] else ' '
        val multi = syllables(s) >= 2
        // "C + nguyên âm + hậu tố" ở từ nhiều âm tiết: chỉ tin khi trước nguyên âm là phụ âm (loại oo, ai, ea...).
        val cv = n >= 3 && !isVowel(third)

        return when (last) {
            'c', 'v', 'u' -> true                       // danc, lov, continu
            'z' -> prev != 'z' && prev != 't'           // gaz, sneez (buzz, waltz giữ nguyên: waltzed -> waltz, cùng thẻ với waltzes)
            's' -> prev != 's'                          // clos, rais, us (focus / bias nằm trong NO_E_STEMS)
            'g' -> when {
                prev == 'n' || prev == 'o' -> false     // belong (change nằm trong NG_E_WORDS), catalog
                prev in "dlr" || isVowel(prev) -> true  // judg, charg, manag
                else -> false
            }
            'l' -> prev in "bcdfgkpstz" || (!multi && isShortCvc(s))   // settl, handl; smil
            'k' -> (multi && prev in "aou" && cv) || (!multi && isShortCvc(s)) // provok, awak; bak
            't' -> when {
                prev == 'a' && multi && (cv || third == 'u') -> true     // relat, translat, evaluat
                prev == 'u' && multi && cv -> true                       // distribut, execut
                else -> isShortCvc(s)                                     // hat, not, quit
            }
            'd' -> (multi && prev in "aiou" && cv) || isShortCvc(s)      // decid, includ, explod; fad
            'm' -> (multi && prev in "au" && cv) || isShortCvc(s)        // assum, renam; nam
            'p' -> (multi && prev == 'a' && cv) || isShortCvc(s)         // escap; shap
            'r' -> (multi && prev in "aiu" && cv) || isShortCvc(s)       // prepar, desir, captur; shar
            'n' -> (multi && prev == 'i' && cv) || isShortCvc(s)         // determin, examin; lin
            'b' -> (multi && prev == 'i' && cv) || isShortCvc(s)         // describ, imbib; prob
            'x', 'w', 'y', 'h', 'e', 'a', 'i', 'o' -> false
            else -> isShortCvc(s)                                        // f, j, q... : chỉ từ một âm tiết
        }
    }
}
