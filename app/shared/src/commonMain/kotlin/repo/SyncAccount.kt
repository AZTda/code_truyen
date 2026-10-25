package com.truyencloud.shared.repo

/**
 * Khoá tài khoản suy ra từ token phiên, để lưu con trỏ đồng bộ RIÊNG cho từng tài khoản.
 *
 * Token có dạng `<payload_base64url>.<chữ_ký>`, payload là JSON có trường `sub` (id người
 * dùng) — id này không đổi giữa các lần đăng nhập, khác với cả chuỗi token. Chỉ ĐỌC payload
 * để lấy khoá (việc xác minh chữ ký là của máy chủ).
 */
object SyncAccount {
    const val GUEST = "guest"

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val SUB_REGEX = Regex("\"sub\"\\s*:\\s*\"([^\"]*)\"")

    fun keyFor(token: String): String {
        if (token.isBlank()) return GUEST
        val payload = token.substringBefore('.', "")
        val json = decodeBase64Url(payload)?.decodeToString()
        val sub = json?.let { SUB_REGEX.find(it)?.groupValues?.get(1) }?.takeIf { it.isNotBlank() }
        return sub ?: "t" + token.hashCode().toString(16)
    }

    /** Giải mã base64url (có hoặc không có dấu '='); null nếu sai định dạng. */
    internal fun decodeBase64Url(input: String): ByteArray? {
        val clean = input.trimEnd('=')
        if (clean.isEmpty()) return null
        val out = ArrayList<Byte>(clean.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            val v = ALPHABET.indexOf(ch)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
