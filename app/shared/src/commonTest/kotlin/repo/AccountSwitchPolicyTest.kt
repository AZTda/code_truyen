package com.truyencloud.shared.repo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountSwitchPolicyTest {

    @Test
    fun firstEverLoginKeepsWhateverTheGuestHad() {
        assertEquals(AccountSwitchAction.KEEP_DATA, AccountSwitchPolicy.onLogin("", "user_a"))
        assertEquals(AccountSwitchAction.KEEP_DATA, AccountSwitchPolicy.onLogin("  ", "user_a"))
        assertEquals(AccountSwitchAction.KEEP_DATA, AccountSwitchPolicy.onLogin("guest", "user_a"))
    }

    @Test
    fun sameAccountKeepsData() {
        assertEquals(AccountSwitchAction.KEEP_DATA, AccountSwitchPolicy.onLogin("user_a", "user_a"))
        assertEquals(AccountSwitchAction.KEEP_DATA, AccountSwitchPolicy.onLogin(" user_a ", "user_a"))
    }

    @Test
    fun differentAccountWipesBeforeSync() {
        assertEquals(AccountSwitchAction.WIPE_BEFORE_SYNC, AccountSwitchPolicy.onLogin("user_a", "user_b"))
    }

    @Test
    fun unknownNewIdAfterARealAccountIsTreatedAsDifferent() {
        // Thà xoá thừa còn hơn để tài khoản lạ thừa hưởng dữ liệu của người trước
        assertEquals(AccountSwitchAction.WIPE_BEFORE_SYNC, AccountSwitchPolicy.onLogin("user_a", ""))
    }

    @Test
    fun logoutRemembersTheUserOnlyWhenDataIsKept() {
        assertEquals("user_a", AccountSwitchPolicy.lastUserAfterLogout("user_a", wipedData = false))
        assertEquals("", AccountSwitchPolicy.lastUserAfterLogout("user_a", wipedData = true))
    }

    @Test
    fun wipedDeviceLetsTheNextAccountInWithoutAnotherWipe() {
        val last = AccountSwitchPolicy.lastUserAfterLogout("user_a", wipedData = true)
        assertEquals(AccountSwitchAction.KEEP_DATA, AccountSwitchPolicy.onLogin(last, "user_b"))
    }

    @Test
    fun keptDataStillProtectsAgainstTheNextAccount() {
        val last = AccountSwitchPolicy.lastUserAfterLogout("user_a", wipedData = false)
        assertEquals(AccountSwitchAction.WIPE_BEFORE_SYNC, AccountSwitchPolicy.onLogin(last, "user_b"))
        assertEquals(AccountSwitchAction.KEEP_DATA, AccountSwitchPolicy.onLogin(last, "user_a"))
    }

    @Test
    fun scenarioAThenBThenAAgain() {
        var last = ""
        val steps = listOf("user_a", "user_a", "user_b", "user_a")
        val actions = steps.map { id ->
            AccountSwitchPolicy.onLogin(last, id).also { last = id }
        }
        assertEquals(
            listOf(
                AccountSwitchAction.KEEP_DATA,
                AccountSwitchAction.KEEP_DATA,
                AccountSwitchAction.WIPE_BEFORE_SYNC,
                AccountSwitchAction.WIPE_BEFORE_SYNC
            ),
            actions
        )
    }

    // ---- Dữ liệu thuộc về tài khoản nào ----

    @Test
    fun dataBelongsToAnAccountWhileLoggedInOrWhileTheOwnerIsRemembered() {
        assertTrue(AccountSwitchPolicy.dataBelongsToAccount(hasSession = true, lastUserId = ""))
        // Phiên hết hạn (token bị bỏ) hoặc đăng xuất giữ dữ liệu: vẫn là dữ liệu của user_a -> xoá truyện phải ghi bia mộ
        assertTrue(AccountSwitchPolicy.dataBelongsToAccount(hasSession = false, lastUserId = "user_a"))
        assertFalse(AccountSwitchPolicy.dataBelongsToAccount(hasSession = false, lastUserId = ""))
        assertFalse(AccountSwitchPolicy.dataBelongsToAccount(hasSession = false, lastUserId = "  "))
        assertFalse(AccountSwitchPolicy.dataBelongsToAccount(hasSession = false, lastUserId = "guest"))
    }

    @Test
    fun theOwnerIsBackfilledFromTheStoredProfileWhateverTheTokenState() {
        assertEquals("user_a", AccountSwitchPolicy.ownerToBackfill(lastUserId = "", persistedProfileId = "user_a"))
        assertEquals("user_a", AccountSwitchPolicy.ownerToBackfill(lastUserId = "  ", persistedProfileId = " user_a "))
        // "guest" trong LAST_USER_ID coi như chưa có chủ
        assertEquals("user_a", AccountSwitchPolicy.ownerToBackfill(lastUserId = "guest", persistedProfileId = "user_a"))
    }

    @Test
    fun nothingToBackfillForGuestsOrWhenTheOwnerIsAlreadyKnown() {
        assertNull(AccountSwitchPolicy.ownerToBackfill(lastUserId = "", persistedProfileId = ""))
        assertNull(AccountSwitchPolicy.ownerToBackfill(lastUserId = "", persistedProfileId = "guest"))
        assertNull(AccountSwitchPolicy.ownerToBackfill(lastUserId = "user_a", persistedProfileId = "user_b"))
    }

    @Test
    fun aBackfilledOwnerStopsTheNextAccountFromInheritingThePreviousOwnersData() {
        // Máy chưa ghi chủ (token hỏng / đã đăng xuất ở bản cũ) nhưng hồ sơ vẫn ghi user_a: user_b KHÔNG được nhận dữ liệu đó.
        assertEquals(AccountSwitchAction.KEEP_DATA, AccountSwitchPolicy.onLogin("", "user_b"))
        val owner = AccountSwitchPolicy.ownerToBackfill("", "user_a")!!
        assertEquals(AccountSwitchAction.WIPE_BEFORE_SYNC, AccountSwitchPolicy.onLogin(owner, "user_b"))
        assertEquals(AccountSwitchAction.KEEP_DATA, AccountSwitchPolicy.onLogin(owner, "user_a"))
    }
}
