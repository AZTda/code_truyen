package com.truyencloud.shared.library

/**
 * Chuẩn hoá chuỗi ngắn (từ khoá tìm, tên truyện, lý do gợi ý) dùng chung cho Khám phá và gợi ý tìm kiếm.
 *
 * Tự duyệt từng ký tự bằng [Char.isWhitespace] thay vì Regex `\s`: nghĩa của `\s` khác nhau giữa JVM và Kotlin/Native
 * (dấu cách toàn khổ U+3000 hay gặp trong tên truyện Trung Quốc), còn ở đây Android và iOS cho cùng kết quả.
 */
internal object TextNormalize {

    /** Bỏ khoảng trắng hai đầu và gộp mọi dãy khoảng trắng (kể cả xuống dòng, tab) thành một dấu cách. */
    fun collapse(raw: String): String {
        val out = StringBuilder(raw.length)
        var pendingSpace = false
        for (ch in raw) {
            if (ch.isWhitespace()) {
                pendingSpace = true
                continue
            }
            if (pendingSpace && out.isNotEmpty()) out.append(' ')
            pendingSpace = false
            out.append(ch)
        }
        return out.toString()
    }

    /** Khoá so khớp không phân biệt hoa/thường (không bỏ dấu: "Kiếm" và "Kiem" là hai từ khoá khác nhau). */
    fun key(raw: String): String = collapse(raw).lowercase()
}
