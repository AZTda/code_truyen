package com.truyencloud.shared.export

/**
 * Thoát ký tự cho từng định dạng xuất từ vựng. Nội dung thẻ lấy từ truyện cào về và từ AI nên coi là thù địch:
 * không được làm hỏng tệp, cũng không được biến thành lệnh khi mở bằng bảng tính.
 */
internal object ExportEscaping {

    /**
     * Ký tự đầu khiến Excel / Google Sheets hiểu ô là CÔNG THỨC (CSV injection): `=`, `+`, `-`, `@` và (theo OWASP) tab, CR.
     */
    private const val FORMULA_TRIGGERS = "=+-@\t\r"

    /**
     * Ô CSV: thoát dấu phẩy / nháy kép / xuống dòng, và vô hiệu hoá công thức bằng cách thêm MỘT DẤU NHÁY ĐƠN ở đầu ô
     * bắt đầu bằng `=`, `+`, `-`, `@` (khuyến nghị của OWASP): `=HYPERLINK(...)` thành `'=HYPERLINK(...)` — hiện nguyên văn
     * như chữ, không chạy. Hệ quả có chủ ý: một định nghĩa thật sự bắt đầu bằng "-" (vd. "-ness: hậu tố") sẽ có thêm dấu `'`
     * ở đầu trong bảng tính; muốn bản sạch thì dùng định dạng JSON / Markdown / Anki.
     */
    fun csv(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in FORMULA_TRIGGERS) "'$value" else value
        val quoted = safe.replace("\"", "\"\"")
        return if (quoted.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$quoted\"" else quoted
    }

    /**
     * Chuỗi JSON (đã kèm dấu nháy kép). MỌI ký tự điều khiển U+0000..U+001F đều phải thoát (RFC 8259): bản cũ chỉ thoát
     * `\n \r \t` nên backspace / form feed / NUL trong định nghĩa hay câu ngữ cảnh làm cả tệp bị bộ phân tích nghiêm ngặt từ chối.
     */
    fun json(value: String): String = buildString(value.length + 2) {
        append('"')
        for (c in value) {
            when {
                c == '\\' -> append("\\\\")
                c == '"' -> append("\\\"")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }

    /** Ô bảng Markdown: `|` sẽ tách cột nên phải thoát (kèm `\` để `\|` có sẵn trong dữ liệu không thành dấu tách); xuống dòng -> dấu cách. */
    fun markdownCell(value: String): String =
        value.replace("\\", "\\\\").replace("|", "\\|").replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
}
