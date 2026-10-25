package com.truyencloud.shared.tts

/**
 * Dựng URL luồng giọng đọc `/v1/tts/stream` dùng chung cho Android và iOS.
 *
 * Bản iOS cũ mã hoá bằng `URLQueryAllowedCharacterSet`, bộ ký tự này CHO PHÉP `&`, `=`, `+`, `;`:
 * một đoạn văn có dấu `&` bị cắt cụt tại đó, dấu `+` bị máy chủ hiểu thành khoảng trắng.
 */
object TtsStreamUrl {
    private const val HEX = "0123456789ABCDEF"

    /** Mã hoá thành phần query theo RFC 3986: chỉ giữ `A-Z a-z 0-9 - . _ ~`, còn lại thành %XX (UTF-8). */
    fun encodeComponent(value: String): String {
        val sb = StringBuilder(value.length * 3)
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            if (isUnreserved(c)) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
        }
        return sb.toString()
    }

    private fun isUnreserved(c: Int): Boolean =
        c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
            c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code

    /**
     * Cloudflare và uvicorn đều từ chối dòng yêu cầu dài quá 16 KB, và tiêu đề (token...) cũng nằm trong hạn mức đó. Chữ
     * Việt / Hán mã hoá thành 6-9 ký tự mỗi chữ nên hai đoạn dài là đủ vượt: yêu cầu bị từ chối là giọng AI "hỏng" và cả
     * phiên chuyển sang giọng máy. Gợi ý tiền nạp chỉ là tối ưu nên bị bỏ trước.
     */
    const val MAX_URL_LENGTH = 12_000

    /**
     * Máy chủ (VieNeu) luôn trả âm thanh 1.0x và bỏ qua `speed`; tốc độ do trình phát của app áp. URL gửi CỐ ĐỊNH
     * `speed=1.0` nên đổi tốc độ không đổi URL / khoá bộ đệm và không bắt máy chủ dựng lại (máy chủ đời trước cũng đọc 1.0x).
     */
    const val SERVER_SPEED = "1.0"

    fun build(host: String, text: String, prefetchNext: String?, voiceId: String): String {
        val lang = TtsTextLanguage.langParam(text)
        val sb = StringBuilder()
        sb.append(host.trimEnd('/'))
            .append("/v1/tts/stream?text=").append(encodeComponent(text))
            .append("&voice=").append(encodeComponent(voiceId))
            .append("&speed=").append(SERVER_SPEED)
            .append("&lang=").append(lang)
        // Máy chủ tiền nạp đoạn kế bằng CHÍNH `lang` của đoạn này: đoạn kế khác ngôn ngữ thì bỏ gợi ý, kẻo làm ấm sai giọng
        if (!prefetchNext.isNullOrBlank() && TtsTextLanguage.langParam(prefetchNext) == lang) {
            val hint = "&prefetch_next=" + encodeComponent(prefetchNext)
            if (sb.length + hint.length <= MAX_URL_LENGTH) sb.append(hint)
        }
        return sb.toString()
    }
}
