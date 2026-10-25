package com.truyencloud.shared.repo

/** Kiểm tra form đổi mật khẩu ở máy trước khi gọi máy chủ (máy chủ cũng kiểm lại, độ dài tối thiểu 8). */
object PasswordPolicy {
    const val MIN_LENGTH = 8

    /** Trả thông báo lỗi tiếng Việt, hoặc null nếu hợp lệ. */
    fun validateChange(oldPassword: String, newPassword: String, confirmation: String): String? = when {
        oldPassword.isEmpty() -> "Vui lòng nhập mật khẩu hiện tại."
        newPassword.length < MIN_LENGTH -> "Mật khẩu mới phải có ít nhất $MIN_LENGTH ký tự."
        newPassword == oldPassword -> "Mật khẩu mới phải khác mật khẩu hiện tại."
        newPassword != confirmation -> "Mật khẩu nhập lại không khớp."
        else -> null
    }
}
