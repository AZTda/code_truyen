package com.truyencloud.compose.screens.settings

import com.truyencloud.shared.api.ConnectionResult
import com.truyencloud.shared.api.ServerUrlCheck
import com.truyencloud.shared.api.ServerUrls

/** Trạng thái hộp thoại "Đổi mật khẩu". */
data class ChangePasswordState(
    val isOpen: Boolean = false,
    val oldPassword: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val succeeded: Boolean = false
)

/** Trạng thái mục "Nâng cao → Máy chủ". */
data class ServerSettingsState(
    /** Nội dung đang gõ trong ô địa chỉ. */
    val input: String = ServerUrls.DEFAULT,
    /** Kết quả kiểm tra định dạng của [input] (null khi chưa gõ gì). */
    val validation: ServerUrlCheck? = null,
    val isTesting: Boolean = false,
    val testResult: ConnectionResult? = null,
    /** Địa chỉ ĐANG DÙNG (đã lưu). */
    val savedUrl: String = ServerUrls.DEFAULT,
    /** Thông báo sau khi lưu / đặt lại. */
    val notice: String? = null
) {
    val isDirty: Boolean get() = input.trim().trimEnd('/') != savedUrl
    val isDefault: Boolean get() = savedUrl == ServerUrls.DEFAULT
}

enum class ClearScope { ALL, NOVEL }

/** Hộp thoại xác nhận trước khi xoá nội dung chương đã tải. */
data class StorageConfirm(
    val scope: ClearScope,
    /** Id truyện khi [scope] = NOVEL. */
    val novelId: String? = null,
    /** Tên truyện hoặc null khi xoá tất cả. */
    val novelTitle: String? = null,
    val chapterCount: Int,
    val bytes: Long
)
