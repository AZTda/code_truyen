package com.truyencloud.shared.library

import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.prefs.PrefKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Dọn phần dữ liệu gắn với tài khoản mà nằm trong cài đặt (bản lưu Khám phá + lịch sử tìm kiếm). */
class AccountScopedPrefsTest {

    private val prefs = FakeHomePrefs()
    private val cache = DiscoverCacheStore(prefs)

    private fun fill(account: String) {
        for (sort in DiscoverSort.entries) {
            val entry = DiscoverEntry(Novel(id = "n-$account-${sort.apiValue}", title = "Truyện"), "lý do")
            cache.save(sort, account, DiscoverResult(listOf(entry), personalized = true), savedAt = 1L)
        }
    }

    private fun held(account: String) = DiscoverSort.entries.count { cache.load(it, account) != null }

    @Test
    fun clearsEveryBoardOfEveryGivenAccountAndLeavesOthersAlone() {
        listOf("user_a", "user_b", "user_c").forEach(::fill)
        AccountScopedPrefs.clear(prefs, listOf("user_a", "user_b"))
        assertEquals(0, held("user_a"))
        assertEquals(0, held("user_b"))
        assertEquals(DiscoverSort.entries.size, held("user_c"))
    }

    @Test
    fun theGuestSnapshotsAreAlwaysClearedTogetherAndABlankIdMeansGuest() {
        fill("guest")
        AccountScopedPrefs.clear(prefs, listOf("user_a"))
        assertEquals(0, held("guest"))

        fill("guest")
        AccountScopedPrefs.clear(prefs, listOf("  "))
        assertEquals(0, held("guest"))
    }

    @Test
    fun dropsTheOldFormatAndTheSearchHistoryWhoeverOwnsIt() {
        HomePrefKeys.LEGACY_DISCOVER_CACHE_KEYS.forEach { prefs.data[it] = "{}" }
        SearchHistoryStore(prefs, ownerId = { "user_x" }).add("Kiếm Lai")
        assertNotNull(prefs.data[PrefKeys.SEARCH_HISTORY])

        AccountScopedPrefs.clear(prefs, emptyList())

        assertTrue(HomePrefKeys.LEGACY_DISCOVER_CACHE_KEYS.none { it in prefs.data })
        assertNull(prefs.data[PrefKeys.SEARCH_HISTORY], "lịch sử chỉ có một bản, của ai cũng bị xoá")
    }

    @Test
    fun doesNothingWithoutAStoreOrWhenTheStoreIsBroken() {
        AccountScopedPrefs.clear(null, listOf("user_a"))
        fill("user_a")
        prefs.failure = IllegalStateException("kho hỏng")
        AccountScopedPrefs.clear(prefs, listOf("user_a"))
    }
}
