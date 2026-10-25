package com.truyencloud.shared.update

sealed interface ApkCheck {
    data object Ok : ApkCheck

    /** Mã SHA-256 của tệp tải về khác với mã trong manifest (hoặc manifest có mã sai định dạng). */
    data object HashMismatch : ApkCheck

    data class SizeMismatch(val expected: Long, val actual: Long) : ApkCheck
}

object ApkIntegrity {
    const val HASH_MISMATCH_MESSAGE = "Tệp cập nhật không khớp mã kiểm tra"

    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    /** Chuẩn hoá mã băm của manifest: bỏ khoảng trắng, chữ thường. "" nghĩa là máy chủ không cung cấp. */
    fun normalizeHash(raw: String?): String = raw?.trim()?.lowercase().orEmpty()

    /**
     * Đối chiếu tệp đã tải với manifest.
     *
     * - Dung lượng: so khi manifest có (> 0) — bắt tệp bị cắt cụt hoặc bị thay.
     * - SHA-256: manifest có mã (khác rỗng) thì PHẢI khớp; mã sai định dạng cũng coi là không khớp.
     *   Manifest không có mã thì chỉ kiểm tra dung lượng (máy chủ cũ chưa tính được).
     */
    fun verify(
        expectedSha256: String?,
        expectedSizeBytes: Long,
        actualSha256: String,
        actualSizeBytes: Long
    ): ApkCheck {
        if (expectedSizeBytes > 0 && actualSizeBytes != expectedSizeBytes) {
            return ApkCheck.SizeMismatch(expectedSizeBytes, actualSizeBytes)
        }
        val expected = normalizeHash(expectedSha256)
        if (expected.isEmpty()) return ApkCheck.Ok
        if (!SHA256_HEX.matches(expected)) return ApkCheck.HashMismatch
        return if (expected == actualSha256.trim().lowercase()) ApkCheck.Ok else ApkCheck.HashMismatch
    }

    fun messageFor(check: ApkCheck): String? = when (check) {
        ApkCheck.Ok -> null
        ApkCheck.HashMismatch -> HASH_MISMATCH_MESSAGE
        is ApkCheck.SizeMismatch ->
            "Tệp cập nhật tải về không đúng dung lượng (nhận ${check.actual} byte, cần ${check.expected} byte). Vui lòng thử lại."
    }
}
