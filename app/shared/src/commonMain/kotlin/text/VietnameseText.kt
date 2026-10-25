package com.truyencloud.shared.text

/**
 * Chuẩn hóa tiếng Việt để so khớp tìm kiếm không phân biệt dấu.
 *
 * commonMain không có java.text.Normalizer nên dùng bảng ánh xạ tĩnh —
 * đủ cho toàn bộ ký tự có dấu tiếng Việt (kể cả tổ hợp â/ă/ê/ô/ơ/ư + thanh).
 */
object VietnameseText {

    private val accentGroups = mapOf(
        'a' to "àáạảãâầấậẩẫăằắặẳẵ",
        'e' to "èéẹẻẽêềếệểễ",
        'i' to "ìíịỉĩ",
        'o' to "òóọỏõôồốộổỗơờớợởỡ",
        'u' to "ùúụủũưừứựửữ",
        'y' to "ỳýỵỷỹ",
        'd' to "đ",
    )

    private val foldMap: Map<Char, Char> = buildMap {
        for ((base, accented) in accentGroups) {
            for (ch in accented) {
                put(ch, base)
                put(ch.uppercaseChar(), base)
            }
        }
    }

    /** Hạ chữ thường + bỏ dấu: "Kiếm Lai" -> "kiem lai". */
    fun fold(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (ch in text.lowercase()) {
            // Chuỗi dạng NFD (dấu tách rời, hay gặp khi dán từ macOS/iOS):
            // bỏ các ký tự dấu kết hợp U+0300..U+036F
            if (ch in '̀'..'ͯ') continue
            sb.append(foldMap[ch] ?: ch)
        }
        return sb.toString()
    }

    /** Có chứa chữ Hán (cùng dải với has_cjk phía server). */
    fun hasCjk(text: String): Boolean = text.any { it.code in 0x4E00..0x9FFF }
}
