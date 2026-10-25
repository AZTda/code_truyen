package com.truyencloud.shared.repo

import com.truyencloud.shared.api.AuthApi
import com.truyencloud.shared.data.runNow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Đăng xuất và đổi tài khoản phải đẩy nốt thay đổi chưa đồng bộ TRƯỚC khi xoá token / dữ liệu; còn sót thì báo người dùng.
 * (Kho cài đặt = null, chủ sở hữu truyền qua lambda — quyết định giữ / xoá theo chủ đã có AccountSwitchPolicyTest.)
 */
class AccountSessionTest {

    private class FakePending(var left: Int, val failure: Exception? = null) : PendingChanges {
        var flushes = 0
        var counts = 0
        var tokenSeenByFlush: String? = null
        lateinit var token: () -> String

        override suspend fun pendingCount(): Int { counts++; return left }
        override suspend fun flushBounded(): Int {
            flushes++
            tokenSeenByFlush = token()
            failure?.let { throw it }
            return left
        }
    }

    private class Harness(token: String, val pending: FakePending?, lastUser: String = "") {
        var token = token
        val tokenWrites = mutableListOf<String>()
        val notices = mutableListOf<String>()
        var wipes = 0
        var wipeOrder = ""

        val session = AccountSession(
            authApi = AuthApi(),
            store = null,
            dataWiper = LocalDataWiper {
                wipes++
                wipeOrder = "token=[${this.token}]"
            },
            supabaseAuth = null,
            readProfile = { com.truyencloud.shared.data.UserProfile() },
            editProfile = { },
            readToken = { this.token },
            writeToken = { this.token = it; tokenWrites += it },
            dropRejectedToken = { },
            pendingChanges = { pending },
            postNotice = { notices += it },
            lastUserId = { lastUser }
        )

        init {
            pending?.token = { this.token }
        }
    }

    @Test
    fun logoutFlushesWhileTheTokenIsStillValidThenClearsIt() = runNow {
        val h = Harness("tokenA", FakePending(left = 0))
        h.session.logout(wipeLocalData = false)
        assertEquals("tokenA", h.pending!!.tokenSeenByFlush, "Đẩy nốt phải chạy TRƯỚC khi xoá token")
        assertEquals("", h.token)
        assertEquals(0, h.wipes, "Giữ dữ liệu thì không xoá")
        assertTrue(h.notices.isEmpty())
    }

    @Test
    fun wipeAfterAFullFlushIsSilentAndRunsWhileTheTokenIsAlreadyGone() = runNow {
        val h = Harness("tokenA", FakePending(left = 0))
        h.session.logout(wipeLocalData = true)
        assertEquals(1, h.wipes)
        assertEquals("token=[]", h.wipeOrder, "Token đã xoá trước khi xoá dữ liệu (như cũ)")
        assertTrue(h.notices.isEmpty(), "Đẩy hết rồi thì không có gì để báo")
    }

    @Test
    fun wipeWithChangesThatCouldNotBePushedStillHappensButTheUserIsTold() = runNow {
        val h = Harness("tokenA", FakePending(left = 3))
        h.session.logout(wipeLocalData = true)
        assertEquals(1, h.wipes, "Đăng xuất / xoá vẫn làm được dù không đẩy hết")
        assertEquals(1, h.notices.size)
        assertTrue(h.notices.single().contains("3"), h.notices.single())
        assertEquals(AccountNotices.lostOnLogout(3), h.notices.single())
    }

    @Test
    fun keepingTheDataNeverWipesNorWarns() = runNow {
        val h = Harness("tokenA", FakePending(left = 5))
        h.session.logout(wipeLocalData = false)
        assertEquals(0, h.wipes)
        assertTrue(h.notices.isEmpty())
        assertEquals(1, h.pending!!.flushes, "Vẫn đẩy nốt: sau khi token mất thì không đẩy được nữa")
    }

    @Test
    fun withoutATokenNothingIsPushedButTheLeftoversAreStillCounted() = runNow {
        val h = Harness("", FakePending(left = 2))
        h.session.logout(wipeLocalData = true)
        assertEquals(0, h.pending!!.flushes, "Không có token thì không gọi mạng")
        assertEquals(1, h.pending.counts)
        assertEquals(listOf(AccountNotices.lostOnLogout(2)), h.notices)
    }

    @Test
    fun aFlushThatThrowsNeverBlocksLogout() = runNow {
        val h = Harness("tokenA", FakePending(left = 4, failure = IllegalStateException("mất mạng")))
        h.session.logout(wipeLocalData = true)
        assertEquals("", h.token)
        assertEquals(1, h.wipes)
    }

    @Test
    fun logoutWorksWhenNothingIsAttached() = runNow {
        val h = Harness("tokenA", pending = null)
        h.session.logout(wipeLocalData = true)
        assertEquals("", h.token)
        assertEquals(1, h.wipes)
        assertFalse(h.notices.isNotEmpty())
    }

    // ---- Đổi tài khoản lúc đăng nhập ----

    @Test
    fun switchingAccountsFlushesWithTheOldTokenThenWipesAndTellsTheUserWhatWasLost() = runNow {
        val h = Harness("tokenA", FakePending(left = 2), lastUser = "user_a")
        h.session.wipeIfAccountSwitched("user_b")
        assertEquals("tokenA", h.pending!!.tokenSeenByFlush, "Còn token cũ thì đẩy nốt dữ liệu của tài khoản cũ trước khi xoá")
        assertEquals(1, h.wipes)
        assertEquals(listOf(AccountNotices.lostOnSwitch(2)), h.notices)
    }

    @Test
    fun switchingAccountsWithoutAnOldTokenOnlyCountsWhatWillBeLost() = runNow {
        val h = Harness("", FakePending(left = 6), lastUser = "user_a")
        h.session.wipeIfAccountSwitched("user_b")
        assertEquals(0, h.pending!!.flushes)
        assertEquals(1, h.wipes)
        assertEquals(listOf(AccountNotices.lostOnSwitch(6)), h.notices)
    }

    @Test
    fun switchingAfterEverythingWasPushedIsSilent() = runNow {
        val h = Harness("tokenA", FakePending(left = 0), lastUser = "user_a")
        h.session.wipeIfAccountSwitched("user_b")
        assertEquals(1, h.wipes)
        assertTrue(h.notices.isEmpty())
    }

    @Test
    fun theSameAccountOrAFirstLoginNeverWipesNorFlushes() = runNow {
        val same = Harness("tokenA", FakePending(left = 9), lastUser = "user_a")
        same.session.wipeIfAccountSwitched("user_a")
        val first = Harness("", FakePending(left = 9), lastUser = "")
        first.session.wipeIfAccountSwitched("user_a")
        for (h in listOf(same, first)) {
            assertEquals(0, h.wipes)
            assertEquals(0, h.pending!!.flushes)
            assertEquals(0, h.pending.counts)
            assertTrue(h.notices.isEmpty())
        }
    }
}

