package com.truyencloud.shared.reader

/** Một cặp đoạn song ngữ do máy chủ trả về: đoạn tiếng Anh [english] và đoạn tiếng Việt [vietnamese] ngay sau nó. */
data class BilingualPair(val english: String, val vietnamese: String)

/**
 * Tách văn bản song ngữ của máy chủ (`[EN] đoạn Anh` rồi `[VI] đoạn Việt`, lần lượt) thành các CẶP.
 *
 * Khác với việc tách riêng hai danh sách rồi ghép theo chỉ số: nếu mô hình bỏ sót một đoạn `[VI]`, cặp
 * đó chỉ thiếu phần Việt (hiện trống) chứ các cặp phía sau không bị lệch hàng.
 */
object BilingualPairs {
    private const val EN = "[EN]"
    private const val VI = "[VI]"

    fun parse(text: String): List<BilingualPair> {
        if (text.isBlank()) return emptyList()
        val pairs = ArrayList<BilingualPair>()
        var english: StringBuilder? = null
        var vietnamese: StringBuilder? = null
        var target: StringBuilder? = null

        fun flush() {
            val en = english?.toString()?.trim().orEmpty()
            val vi = vietnamese?.toString()?.trim().orEmpty()
            if (en.isNotEmpty() || vi.isNotEmpty()) pairs += BilingualPair(en, vi)
            english = null
            vietnamese = null
            target = null
        }

        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith(EN, ignoreCase = true) -> {
                    flush()
                    english = StringBuilder(line.substring(EN.length).trim())
                    target = english
                }
                line.startsWith(VI, ignoreCase = true) -> {
                    // [VI] không có [EN] đứng trước (hoặc [VI] thứ hai liên tiếp) thì mở một cặp mới chỉ có phần Việt
                    if (vietnamese != null || english == null) flush()
                    vietnamese = StringBuilder(line.substring(VI.length).trim())
                    target = vietnamese
                }
                line.isNotEmpty() -> target?.let { if (it.isNotEmpty()) it.append('\n'); it.append(line) }
            }
        }
        flush()
        return pairs
    }

    /** Chỉ có ích khi có ít nhất một cặp đủ cả hai phần; nếu không, không nên coi là bản song ngữ căn hàng. */
    fun isUsable(pairs: List<BilingualPair>): Boolean =
        pairs.isNotEmpty() && pairs.count { it.english.isNotEmpty() && it.vietnamese.isNotEmpty() } * 2 >= pairs.size
}
