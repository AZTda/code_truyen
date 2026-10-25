package com.truyencloud.shared.library

import com.truyencloud.shared.data.Novel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bản lưu Khám phá RIÊNG từng tài khoản: tài khoản B không bao giờ thấy danh sách đã lưu của tài khoản A. */
class DiscoverCacheStoreTest {

    private fun novel(id: String) = Novel(id = id, title = "Truyện $id", titleVi = "Việt $id")
    private fun result(vararg pairs: Pair<String, String>, personalized: Boolean = true) =
        DiscoverResult(pairs.map { (id, reason) -> DiscoverEntry(novel(id), reason) }, personalized)

    // ---- Khoá ----

    @Test
    fun everyAccountAndSortGetsItsOwnKey() {
        val accounts = listOf("user_a", "user_b", "guest", "a|recent", "recent", "")
        val keys = accounts.flatMap { account -> DiscoverSort.entries.map { HomePrefKeys.discoverCacheKey(it, account) } }
        // "" và "guest" là cùng một khách nên trùng nhau; còn lại phải khác hẳn
        assertEquals((accounts.size - 1) * DiscoverSort.entries.size, keys.toSet().size)
        assertEquals(
            HomePrefKeys.discoverCacheKey(DiscoverSort.RECENT, "guest"),
            HomePrefKeys.discoverCacheKey(DiscoverSort.RECENT, "  ")
        )
        assertNotEquals(
            HomePrefKeys.discoverCacheKey(DiscoverSort.FOR_YOU, "user_a"),
            HomePrefKeys.discoverCacheKey(DiscoverSort.FOR_YOU, "user_b")
        )
    }

    @Test
    fun newKeysNeverCollideWithTheOldAccountAgnosticOnes() {
        assertEquals(
            listOf("home_discover_cache_recent", "home_discover_cache_popular"),
            HomePrefKeys.LEGACY_DISCOVER_CACHE_KEYS,
            "định dạng khoá 1.1.x"
        )
        val all = listOf("user_a", "guest", "recent", "popular", "for_you")
            .flatMap { HomePrefKeys.discoverCacheKeys(it) }
        assertTrue(all.none { it in HomePrefKeys.LEGACY_DISCOVER_CACHE_KEYS })
        assertTrue(all.all { it.startsWith(HomePrefKeys.DISCOVER_CACHE_PREFIX) })
    }

    @Test
    fun discoverCacheKeysCoverEverySort() {
        val keys = HomePrefKeys.discoverCacheKeys("user_a")
        assertEquals(DiscoverSort.entries.size, keys.size)
        assertEquals(DiscoverSort.entries.map { HomePrefKeys.discoverCacheKey(it, "user_a") }, keys)
    }

    // ---- Định dạng bản lưu ----

    @Test
    fun anOldSnapshotWithoutReasonsStillDecodes() {
        val old = """{"savedAt":1234,"novels":[{"id":"a","title":"A","titleVi":"Ai"},{"id":"b","title":"B"}]}"""
        val snap = assertNotNull(DiscoverCache.decode(old))
        assertEquals(1234L, snap.savedAt)
        assertEquals(listOf("a", "b"), snap.novels.map { it.id })
        assertTrue(snap.reasons.isEmpty())
        assertFalse(snap.personalized)
        assertEquals(listOf("", ""), snap.entries.map { it.reason })
    }

    @Test
    fun aListWithoutReasonsIsStoredInTheCompactOldFormat() {
        val raw = DiscoverCache.encode(listOf(Novel(id = "a", title = "A")), savedAt = 5L)
        assertEquals("""{"savedAt":5,"novels":[{"id":"a","title":"A"}]}""", raw)
        assertFalse("reasons" in raw)
        assertFalse("personalized" in raw)
    }

    @Test
    fun reasonsAndThePersonalizedFlagSurviveARoundTrip() {
        val entries = listOf(DiscoverEntry(novel("a"), "Vì bạn đọc X"), DiscoverEntry(novel("b")), DiscoverEntry(novel("c"), "Cùng tác giả"))
        val snap = assertNotNull(DiscoverCache.decode(DiscoverCache.encodeEntries(entries, savedAt = 77L, personalized = true)))
        assertEquals(77L, snap.savedAt)
        assertTrue(snap.personalized)
        assertEquals(listOf("a", "b", "c"), snap.novels.map { it.id })
        assertEquals(listOf("Vì bạn đọc X", "", "Cùng tác giả"), snap.entries.map { it.reason })
    }

    @Test
    fun reasonsOfDroppedOrUnknownNovelsAreDiscardedOnDecode() {
        val raw = """{"savedAt":5,"novels":[{"id":"a","title":"A"},{"id":"","title":""}],
            "reasons":{"a":"giữ","ghost":"mồ côi","":"rỗng"},"personalized":true}"""
        val snap = assertNotNull(DiscoverCache.decode(raw))
        assertEquals(mapOf("a" to "giữ"), snap.reasons)
        assertNull(DiscoverCache.decode("""{"savedAt":5,"novels":[],"reasons":{"a":"x"}}"""))
    }

    @Test
    fun theCapStillAppliesWhenReasonsAreStored() {
        val many = (1..100).map { DiscoverEntry(novel("n$it"), "lý do $it") }
        val snap = assertNotNull(DiscoverCache.decode(DiscoverCache.encodeEntries(many, 1L, personalized = true)))
        assertEquals(DiscoverCache.MAX_ENTRIES, snap.novels.size)
        assertEquals(DiscoverCache.MAX_ENTRIES, snap.reasons.size)
    }

    // ---- Kho theo tài khoản ----

    @Test
    fun anotherAccountNeverSeesTheSnapshotOfTheFirst() {
        val store = DiscoverCacheStore(FakeHomePrefs())
        store.save(DiscoverSort.FOR_YOU, "user_a", result("a1" to "Vì bạn đọc X"), savedAt = 10L)

        val own = assertNotNull(store.load(DiscoverSort.FOR_YOU, "user_a"))
        assertEquals(listOf("a1"), own.novels.map { it.id })
        assertEquals("Vì bạn đọc X", own.entries.single().reason)
        assertTrue(own.personalized)

        assertNull(store.load(DiscoverSort.FOR_YOU, "user_b"), "tài khoản khác bắt đầu từ trống")
        assertNull(store.load(DiscoverSort.FOR_YOU, "guest"))
        assertNull(store.load(DiscoverSort.RECENT, "user_a"), "bảng khác của cùng tài khoản là bản lưu riêng")
    }

    @Test
    fun savingAnEmptyListDeletesTheSnapshotSoStaleItemsNeverReturn() {
        val prefs = FakeHomePrefs()
        val store = DiscoverCacheStore(prefs)
        store.save(DiscoverSort.RECENT, "guest", result("a" to ""), savedAt = 1L)
        assertNotNull(store.load(DiscoverSort.RECENT, "guest"))
        store.save(DiscoverSort.RECENT, "guest", DiscoverResult(emptyList()), savedAt = 2L)
        assertNull(store.load(DiscoverSort.RECENT, "guest"))
        assertTrue(prefs.data.isEmpty(), "xoá khoá thay vì ghi chuỗi rỗng")
    }

    @Test
    fun clearAccountRemovesEverySortOfThatAccountOnly() {
        val prefs = FakeHomePrefs()
        val store = DiscoverCacheStore(prefs)
        for (account in listOf("user_a", "user_b")) {
            for (sort in DiscoverSort.entries) store.save(sort, account, result("x-${account}-${sort.apiValue}" to ""), 5L)
        }
        store.clearAccount("user_a")
        DiscoverSort.entries.forEach { assertNull(store.load(it, "user_a"), "user_a / ${it.apiValue}") }
        DiscoverSort.entries.forEach { assertNotNull(store.load(it, "user_b"), "user_b / ${it.apiValue}") }
    }

    @Test
    fun clearOnlyDropsTheOneSort() {
        val store = DiscoverCacheStore(FakeHomePrefs())
        store.save(DiscoverSort.RECENT, "user_a", result("r" to ""), 1L)
        store.save(DiscoverSort.POPULAR, "user_a", result("p" to ""), 1L)
        store.clear(DiscoverSort.RECENT, "user_a")
        assertNull(store.load(DiscoverSort.RECENT, "user_a"))
        assertNotNull(store.load(DiscoverSort.POPULAR, "user_a"))
    }

    @Test
    fun theOldAccountAgnosticSnapshotsAreNeverReadAndAreCleanedUp() {
        val legacy = DiscoverCache.encode(listOf(Novel(id = "old", title = "Cũ")), 9L)
        val prefs = FakeHomePrefs(HomePrefKeys.LEGACY_DISCOVER_CACHE_KEYS.associateWith { legacy })
        val store = DiscoverCacheStore(prefs)
        assertTrue(prefs.data.isEmpty(), "dựng kho là dọn bản lưu kiểu cũ")
        for (account in listOf("guest", "user_a")) {
            DiscoverSort.entries.forEach { assertNull(store.load(it, account)) }
        }
    }

    @Test
    fun aBrokenStoreNeverCrashesTheScreen() {
        val prefs = FakeHomePrefs()
        val store = DiscoverCacheStore(prefs)
        prefs.failure = IllegalStateException("kho hỏng")
        assertNull(store.load(DiscoverSort.RECENT, "guest"))
        store.save(DiscoverSort.RECENT, "guest", result("a" to ""), 1L)
        store.save(DiscoverSort.RECENT, "guest", DiscoverResult(emptyList()), 1L)
        store.clearAccount("guest")
        store.clearLegacy()
    }

    @Test
    fun withoutAnyStoreNothingIsRememberedAndNothingBreaks() {
        val store = DiscoverCacheStore(null)
        store.save(DiscoverSort.FOR_YOU, "user_a", result("a" to "r"), 1L)
        assertNull(store.load(DiscoverSort.FOR_YOU, "user_a"))
        store.clearAccount("user_a")
    }

    @Test
    fun corruptSnapshotsDecodeToNothing() {
        val prefs = FakeHomePrefs(mapOf(HomePrefKeys.discoverCacheKey(DiscoverSort.RECENT, "guest") to "{không phải json"))
        assertNull(DiscoverCacheStore(prefs).load(DiscoverSort.RECENT, "guest"))
    }

    // ---- Trạng thái bảng lấy từ bản lưu / kết quả ----

    @Test
    fun aFeedBuiltFromASnapshotKeepsReasonsAndTheFlagAndIsMarkedCached() {
        val store = DiscoverCacheStore(FakeHomePrefs())
        store.save(DiscoverSort.FOR_YOU, "user_a", result("a" to "Vì bạn đọc X", "b" to ""), 42L)
        val feed = DiscoverFeeds.fromSnapshot(store.load(DiscoverSort.FOR_YOU, "user_a"))
        assertTrue(feed.fromCache)
        assertTrue(feed.personalized)
        assertEquals("Vì bạn đọc X", feed.reasonOf(feed.novels.first()))
        assertEquals("", feed.reasonOf(feed.novels.last()))
        assertEquals(42L, feed.savedAt)
    }

    @Test
    fun aServerResultBecomesALiveFeedWithItsReasons() {
        val feed = DiscoverFeeds.succeeded(result("a" to "r", "b" to ""), now = 1_000L)
        assertEquals(listOf("a", "b"), feed.novels.map { it.id })
        assertEquals(mapOf("a" to "r"), feed.reasons)
        assertTrue(feed.personalized)
        assertTrue(feed.loaded)
        assertFalse(feed.fromCache)
        assertEquals(DiscoverMode.LIVE, DiscoverFeeds.mode(feed))
        assertEquals(listOf("r", ""), feed.entries.map { it.reason })
        assertEquals(feed.entries, feed.personalEntries, "danh sách đã xếp riêng thì dùng được làm gợi ý cho bạn")
        // Hai bước tải lại / lỗi không làm mất lý do của danh sách đang hiện
        val failed = DiscoverFeeds.failed(DiscoverFeeds.begin(feed), "Mất mạng")
        assertEquals(feed.reasons, failed.reasons)
        assertTrue(failed.personalized)
    }

    @Test
    fun theOldNovelsOnlyConstructorStillMeansAGeneralList() {
        val feed = DiscoverFeeds.succeeded(listOf(novel("a")), now = 1L)
        assertTrue(feed.reasons.isEmpty())
        assertFalse(feed.personalized)
        assertEquals(listOf("a"), feed.entries.map { it.novel.id })
        assertTrue(feed.personalEntries.isEmpty(), "bảng chung không được gọi là gợi ý cho bạn")
    }

    @Test
    fun aGeneralListFetchedForForYouIsNotTreatedAsPersonalPicks() {
        val feed = DiscoverFeeds.succeeded(result("a" to "", "b" to "", personalized = false), now = 1L)
        assertEquals(2, feed.novels.size)
        assertTrue(feed.personalEntries.isEmpty())
    }
}
