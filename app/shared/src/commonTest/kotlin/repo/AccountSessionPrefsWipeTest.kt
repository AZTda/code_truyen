package com.truyencloud.shared.repo

import com.truyencloud.shared.api.AuthApi
import com.truyencloud.shared.api.UserFacingException
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.UserProfile
import com.truyencloud.shared.data.runNow
import com.truyencloud.shared.library.DiscoverCacheStore
import com.truyencloud.shared.library.DiscoverEntry
import com.truyencloud.shared.library.DiscoverResult
import com.truyencloud.shared.library.DiscoverSort
import com.truyencloud.shared.library.FakeHomePrefs
import com.truyencloud.shared.library.HomePrefKeys
import com.truyencloud.shared.library.SearchHistoryStore
import com.truyencloud.shared.prefs.PrefKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bản lưu Khám phá và lịch sử tìm kiếm nằm trong CÀI ĐẶT, mà xoá dữ liệu người dùng chỉ dọn Room: đường xoá của
 * [AccountSession] phải dọn thêm phần này, nếu không tài khoản kế tiếp thừa hưởng gợi ý và từ khoá của người trước.
 */
class AccountSessionPrefsWipeTest {

    private class Harness(
        profileId: String = "user_a",
        lastUser: String = "user_a",
        private val wipeFails: Boolean = false,
        withWiper: Boolean = true
    ) {
        val prefs = FakeHomePrefs()
        var wipes = 0

        val session = AccountSession(
            authApi = AuthApi(),
            store = null,
            dataWiper = if (withWiper) LocalDataWiper { wipes++; if (wipeFails) error("đĩa đầy") } else null,
            supabaseAuth = null,
            readProfile = { UserProfile(id = profileId) },
            editProfile = { },
            readToken = { "tokenA" },
            writeToken = { },
            dropRejectedToken = { },
            accountPrefs = prefs,
            lastUserId = { lastUser }
        )

        /** Bản lưu đủ các bảng cho từng tài khoản + bản lưu kiểu cũ + lịch sử tìm của [historyOwner]. */
        fun seed(accounts: List<String>, historyOwner: String = "user_a") {
            val cache = DiscoverCacheStore(prefs)
            for (account in accounts) {
                for (sort in DiscoverSort.entries) {
                    val entry = DiscoverEntry(Novel(id = "n-$account-${sort.apiValue}", title = "Truyện"), "Vì bạn đọc X")
                    cache.save(sort, account, DiscoverResult(listOf(entry), personalized = true), savedAt = 1L)
                }
            }
            HomePrefKeys.LEGACY_DISCOVER_CACHE_KEYS.forEach { prefs.data[it] = """{"savedAt":1,"novels":[{"id":"old","title":"Cũ"}]}""" }
            SearchHistoryStore(prefs, ownerId = { historyOwner }).add("Từ khoá riêng tư")
        }

        fun snapshots(account: String) =
            DiscoverSort.entries.count { HomePrefKeys.discoverCacheKey(it, account) in prefs.data }

        fun hasHistory() = PrefKeys.SEARCH_HISTORY in prefs.data
        fun hasLegacy() = HomePrefKeys.LEGACY_DISCOVER_CACHE_KEYS.any { it in prefs.data }
    }

    private val allSorts = DiscoverSort.entries.size

    @Test
    fun logoutWithWipeRemovesTheAccountsSnapshotsHistoryAndTheOldFormat() {
        val h = Harness(profileId = "user_a", lastUser = "user_a")
        h.seed(listOf("user_a", "guest", "user_c"))
        assertEquals(allSorts, h.snapshots("user_a"))

        runNow { h.session.logout(wipeLocalData = true) }

        assertEquals(1, h.wipes)
        assertEquals(0, h.snapshots("user_a"), "bản lưu của tài khoản vừa bị xoá dữ liệu")
        assertEquals(0, h.snapshots("guest"))
        assertFalse(h.hasHistory())
        assertFalse(h.hasLegacy())
        assertEquals(allSorts, h.snapshots("user_c"), "tài khoản không liên quan không bị đụng tới")
    }

    @Test
    fun logoutThatKeepsTheDataKeepsTheSnapshotsAndHistoryToo() {
        val h = Harness()
        h.seed(listOf("user_a", "guest"))
        runNow { h.session.logout(wipeLocalData = false) }
        assertEquals(0, h.wipes)
        assertEquals(allSorts, h.snapshots("user_a"))
        assertEquals(allSorts, h.snapshots("guest"))
        assertTrue(h.hasHistory())
    }

    @Test
    fun switchingToAnotherAccountClearsThePreviousOnesStateBeforeTheNewOneSyncs() {
        val h = Harness(profileId = "user_a", lastUser = "user_a")
        h.seed(listOf("user_a", "user_b"))

        runNow { h.session.wipeIfAccountSwitched("user_b") }

        assertEquals(1, h.wipes)
        assertEquals(0, h.snapshots("user_a"))
        assertEquals(0, h.snapshots("user_b"), "bản lưu cũ của tài khoản vào cũng không còn khớp với dữ liệu mới")
        assertFalse(h.hasHistory())
        assertFalse(h.hasLegacy())
    }

    @Test
    fun anExpiredSessionThatKeptTheDataStillClearsTheRealOwnersSnapshots() {
        // Phiên A hết hạn: hồ sơ về khách nhưng LAST_USER_ID vẫn là A; B đăng nhập → dữ liệu của A bị xoá
        val h = Harness(profileId = "guest", lastUser = "user_a")
        h.seed(listOf("user_a", "guest"))
        runNow { h.session.wipeIfAccountSwitched("user_b") }
        assertEquals(0, h.snapshots("user_a"))
        assertEquals(0, h.snapshots("guest"))
    }

    @Test
    fun theSameAccountOrAFirstLoginClearsNothing() {
        val same = Harness(profileId = "user_a", lastUser = "user_a")
        same.seed(listOf("user_a"))
        runNow { same.session.wipeIfAccountSwitched("user_a") }
        val first = Harness(profileId = "guest", lastUser = "")
        first.seed(listOf("guest"), historyOwner = "guest")
        runNow { first.session.wipeIfAccountSwitched("user_a") }

        for (h in listOf(same, first)) {
            assertEquals(0, h.wipes)
            assertTrue(h.hasHistory())
        }
        assertEquals(allSorts, same.snapshots("user_a"))
        assertEquals(allSorts, first.snapshots("guest"), "dữ liệu khách được giữ lại cho tài khoản đầu tiên")
    }

    @Test
    fun aFailedWipeLeavesEverythingInPlaceBecauseTheRoomDataIsStillThere() {
        val switching = Harness(wipeFails = true)
        switching.seed(listOf("user_a"))
        assertFailsWith<UserFacingException> { runNow { switching.session.wipeIfAccountSwitched("user_b") } }
        assertEquals(allSorts, switching.snapshots("user_a"))
        assertTrue(switching.hasHistory())

        val loggingOut = Harness(wipeFails = true)
        loggingOut.seed(listOf("user_a"))
        runNow { loggingOut.session.logout(wipeLocalData = true) }
        assertEquals(allSorts, loggingOut.snapshots("user_a"))
        assertTrue(loggingOut.hasHistory())
    }

    @Test
    fun withoutAWiperNothingWasWipedSoNothingIsCleared() {
        val h = Harness(withWiper = false)
        h.seed(listOf("user_a"))
        runNow { h.session.logout(wipeLocalData = true) }
        assertEquals(allSorts, h.snapshots("user_a"))
        assertTrue(h.hasHistory())
    }

    @Test
    fun theNextAccountStartsCleanAfterAWipe() {
        val h = Harness(profileId = "user_a", lastUser = "user_a")
        h.seed(listOf("user_a"))
        runNow { h.session.wipeIfAccountSwitched("user_b") }

        val cache = DiscoverCacheStore(h.prefs)
        DiscoverSort.entries.forEach { assertNull(cache.load(it, "user_a"), it.apiValue) }
        DiscoverSort.entries.forEach { assertNull(cache.load(it, "user_b"), it.apiValue) }
        assertTrue(SearchHistoryStore(h.prefs, ownerId = { "user_b" }).recent().isEmpty())
        assertTrue(SearchHistoryStore(h.prefs, ownerId = { "user_a" }).recent().isEmpty())
    }

    @Test
    fun sanityCheckTheSeedReallyPopulatesWhatTheWipeIsExpectedToRemove() {
        val h = Harness()
        h.seed(listOf("user_a"))
        assertNotNull(DiscoverCacheStore(h.prefs).load(DiscoverSort.FOR_YOU, "user_a"))
        assertEquals(listOf("Từ khoá riêng tư"), SearchHistoryStore(h.prefs, ownerId = { "user_a" }).recent())
    }
}
