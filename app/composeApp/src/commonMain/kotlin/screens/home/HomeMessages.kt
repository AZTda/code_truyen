package com.truyencloud.compose.screens.home

import com.truyencloud.shared.api.TruyenCloudApi

/**
 * Câu báo cho các việc máy chủ chỉ cho người đã đăng nhập làm (nạp tệp, cào liên kết). Khách mà cứ gọi thì
 * máy chủ trả 401 và FriendlyErrors sẽ nói "phiên đăng nhập đã hết hạn" — sai với người chưa từng đăng nhập,
 * nên chặn sớm bằng câu nói đúng việc cần làm.
 */
internal object HomeMessages {
    const val IMPORT_NEEDS_LOGIN =
        "Cần đăng nhập để nạp tệp sách lên tài khoản của bạn. Hãy đăng nhập trong Cài đặt rồi thử lại."
    const val LINK_NEEDS_LOGIN =
        "Cần đăng nhập để thêm truyện từ liên kết web. Hãy đăng nhập trong Cài đặt rồi thử lại."

    /** Chưa có mã đăng nhập (chế độ khách). */
    fun isGuest(): Boolean = TruyenCloudApi.authToken.isBlank()
}
