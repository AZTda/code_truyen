package com.truyencloud.compose.screens.home

import com.truyencloud.shared.data.UserProfile
import com.truyencloud.shared.library.HomePrefKeys
import com.truyencloud.shared.repo.AccountSwitchPolicy

/**
 * Tài khoản mà dữ liệu cá nhân của màn hình chính (Khám phá, gợi ý tìm kiếm) đang thuộc về. [id] đã chuẩn hoá
 * ("guest" cho khách); [loggedIn] quyết định có bảng "Dành cho bạn" hay không. Một trong hai đổi là phải làm lại từ đầu.
 */
internal data class HomeAccount(val id: String, val loggedIn: Boolean) {
    companion object {
        val GUEST = HomeAccount(AccountSwitchPolicy.GUEST_ID, loggedIn = false)

        fun of(profile: UserProfile) = HomeAccount(HomePrefKeys.accountSlot(profile.id), profile.isLoggedIn)
    }
}
