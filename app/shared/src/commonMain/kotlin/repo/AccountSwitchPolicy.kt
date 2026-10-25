package com.truyencloud.shared.repo

/** Xoá dữ liệu thuộc về người dùng trên máy (tủ sách, tiến độ, dấu trang, từ vựng, lịch ôn, thuật ngữ...). */
fun interface LocalDataWiper {
    suspend fun wipeUserData()
}

enum class AccountSwitchAction {
    /** Cùng tài khoản (hoặc chưa từng có tài khoản trên máy): giữ nguyên dữ liệu. */
    KEEP_DATA,

    /** Tài khoản KHÁC đăng nhập: phải xoá dữ liệu cũ TRƯỚC lần đồng bộ đầu tiên. */
    WIPE_BEFORE_SYNC
}

/**
 * Ngăn dữ liệu của tài khoản A bị trộn sang (và bị đẩy lên máy chủ bởi) tài khoản B trên cùng một máy.
 * Trước đây đăng xuất chỉ đặt lại hồ sơ, còn đăng nhập tài khoản khác thì đồng bộ bookmark đẩy toàn bộ
 * bookmark của người trước lên tài khoản người sau.
 */
object AccountSwitchPolicy {
    const val GUEST_ID = "guest"

    /**
     * @param lastUserId id tài khoản đăng nhập gần nhất trên máy ("" nếu chưa có hoặc đã xoá dữ liệu).
     * @param newUserId id tài khoản vừa đăng nhập.
     *
     * Khách dùng máy trước khi đăng nhập thì dữ liệu khách được giữ lại cho tài khoản đầu tiên.
     */
    fun onLogin(lastUserId: String, newUserId: String): AccountSwitchAction {
        val last = lastUserId.trim()
        if (last.isEmpty() || last == GUEST_ID) return AccountSwitchAction.KEEP_DATA
        return if (last == newUserId.trim()) AccountSwitchAction.KEEP_DATA else AccountSwitchAction.WIPE_BEFORE_SYNC
    }

    /** Sau đăng xuất: giữ dữ liệu thì nhớ id để lần đăng nhập sau so sánh; xoá dữ liệu thì quên. */
    fun lastUserAfterLogout(previousLastUserId: String, wipedData: Boolean): String =
        if (wipedData) "" else previousLastUserId

    /**
     * Dữ liệu trên máy có thuộc về một tài khoản không? Có khi đang có phiên, HOẶC đã ghi nhớ chủ sở hữu mà phiên đã hết
     * hạn / đăng xuất giữ dữ liệu. Khách thật sự (không phiên, không chủ) thì không đồng bộ nên không cần ghi bia mộ xoá —
     * ghi sẽ làm xoá lây sang tài khoản đầu tiên họ đăng nhập (dữ liệu khách được giữ lại cho tài khoản đó).
     */
    fun dataBelongsToAccount(hasSession: Boolean, lastUserId: String): Boolean {
        if (hasSession) return true
        val last = lastUserId.trim()
        return last.isNotEmpty() && last != GUEST_ID
    }

    /**
     * Chủ sở hữu cần ghi vào LAST_USER_ID khi nó còn trống: id hồ sơ đã lưu, trừ khi đó là khách. KHÔNG phụ thuộc token hay
     * cờ đăng nhập — token có thể không giải mã được (Keystore hỏng) hoặc đã hết hạn mà hồ sơ vẫn ghi đúng chủ; nếu
     * không ghi, tài khoản KHÁC đăng nhập sau đó sẽ nhận dữ liệu của người trước và đẩy nó lên tài khoản mới.
     * @return null nếu không có gì để ghi.
     */
    fun ownerToBackfill(lastUserId: String, persistedProfileId: String): String? {
        val last = lastUserId.trim()
        if (last.isNotEmpty() && last != GUEST_ID) return null
        return persistedProfileId.trim().takeIf { it.isNotEmpty() && it != GUEST_ID }
    }
}
