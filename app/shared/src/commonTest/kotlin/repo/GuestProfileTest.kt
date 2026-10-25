package com.truyencloud.shared.repo

import com.truyencloud.shared.data.UserProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Hồ sơ sau khi đăng xuất / hết hạn / đổi máy chủ: không được mang danh tính hay quyền của tài khoản cũ. */
class GuestProfileTest {

    private val account = UserProfile(
        id = "user_a",
        email = "a@truyencloud.vn",
        displayName = "Người A",
        bio = "tiểu sử riêng",
        vipTier = "VIP",
        avatarUrl = "https://cdn.example/avatar-a.jpg",
        bannerUrl = "https://cdn.example/banner-a.jpg",
        preferredTheme = "sepia",
        readerFontSize = 22,
        readerLineHeight = 2.0f,
        serverUrl = "https://truyen.example",
        authProvider = "email",
        isLoggedIn = true,
        isAdmin = true
    )

    private val guest = GuestProfile.applyTo(account, "https://truyen.example")

    @Test
    fun identityIsCleared() {
        assertEquals(AccountSwitchPolicy.GUEST_ID, guest.id)
        assertEquals("", guest.email)
        assertEquals(GuestProfile.NAME, guest.displayName)
        assertEquals(GuestProfile.BIO, guest.bio)
        assertEquals(GuestProfile.TIER, guest.vipTier)
        assertEquals("guest", guest.authProvider)
    }

    @Test
    fun adminRightsAndLoginFlagAreDropped() {
        // Quyền quản trị còn sót lại sau đăng xuất sẽ mở lại các điều khiển dùng chung cho người dùng kế tiếp
        assertFalse(guest.isAdmin)
        assertFalse(guest.isLoggedIn)
    }

    @Test
    fun deviceSettingsSurvive() {
        assertEquals("sepia", guest.preferredTheme)
        assertEquals(22, guest.readerFontSize)
        assertEquals(2.0f, guest.readerLineHeight)
        assertEquals("https://truyen.example", guest.serverUrl)
    }

    @Test
    fun defaultImagesFollowTheServerInUse() {
        assertEquals("https://truyen.example/static/user_avatar_v2.jpg", guest.avatarUrl)
        assertEquals("https://truyen.example/static/user_banner_v2.jpg", guest.bannerUrl)
    }

    @Test
    fun applyingTwiceChangesNothing() {
        assertEquals(guest, GuestProfile.applyTo(guest, "https://truyen.example"))
    }

    @Test
    fun guestBioSavedByAnOlderBuildLosesItsEmojiOnLoad() {
        // Bản cũ lưu câu giới thiệu kèm ngôi sao (U+1F31F); viết bằng mã thoát để tệp nguồn không chứa biểu tượng cảm xúc
        assertEquals(GuestProfile.BIO, GuestProfile.cleanBio(GuestProfile.BIO + " \uD83C\uDF1F"))
        assertEquals(GuestProfile.BIO, GuestProfile.cleanBio(GuestProfile.BIO))
        assertEquals("tiểu sử riêng", GuestProfile.cleanBio("tiểu sử riêng"))
    }
}
