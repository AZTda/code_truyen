package com.truyencloud.shared.prefs

import kotlin.io.encoding.Base64

/**
 * Định dạng chuỗi lưu token đã mã hoá AES-GCM: `v1:<iv>:<ciphertext+tag>` (Base64 an toàn cho URL).
 *
 * Tách riêng khỏi phần Keystore để kiểm thử định dạng mà không cần thiết bị. Tiền tố phiên bản cho phép
 * đổi thuật toán sau này mà vẫn nhận ra (và bỏ) bản cũ thay vì giải mã sai ra rác.
 */
object TokenEnvelope {
    private const val VERSION = "v1"
    private const val SEPARATOR = ':'
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BYTES = 16

    class Parts(val iv: ByteArray, val cipherText: ByteArray)

    fun encode(iv: ByteArray, cipherText: ByteArray): String {
        require(iv.isNotEmpty()) { "IV trống" }
        return VERSION + SEPARATOR + Base64.UrlSafe.encode(iv) + SEPARATOR + Base64.UrlSafe.encode(cipherText)
    }

    /** Trả null nếu chuỗi không phải bao thư hợp lệ (sai tiền tố, thiếu phần, Base64 hỏng, IV/ciphertext sai độ dài). */
    fun decode(stored: String?): Parts? {
        if (stored.isNullOrEmpty()) return null
        val pieces = stored.split(SEPARATOR)
        if (pieces.size != 3 || pieces[0] != VERSION) return null
        return try {
            val iv = Base64.UrlSafe.decode(pieces[1])
            val cipherText = Base64.UrlSafe.decode(pieces[2])
            if (iv.size != GCM_IV_BYTES || cipherText.size <= GCM_TAG_BYTES) null else Parts(iv, cipherText)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
