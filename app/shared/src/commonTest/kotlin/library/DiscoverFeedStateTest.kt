package com.truyencloud.shared.library

import com.truyencloud.shared.data.Novel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoverFeedStateTest {

    private fun novels(vararg ids: String) = ids.map { Novel(id = it, title = "Truyện $it") }

    @Test
    fun firstOpenWithoutAnySavedListShowsTheSkeletonNotTheBuiltInCatalog() {
        val untouched = DiscoverFeed()
        assertEquals(DiscoverMode.LOADING, DiscoverFeeds.mode(untouched))
        assertNull(DiscoverFeeds.notice(untouched))

        val loading = DiscoverFeeds.begin(untouched)
        assertTrue(loading.isLoading)
        assertEquals(DiscoverMode.LOADING, DiscoverFeeds.mode(loading))
        assertNull(DiscoverFeeds.notice(loading))
    }

    @Test
    fun theServerAnswerReplacesTheListAndClearsAnyOldError() {
        val old = DiscoverFeed(novels = novels("old"), error = "Mất mạng", savedAt = 5L, fromCache = true)
        val now = DiscoverFeeds.succeeded(novels("a", "b"), now = 1_000L)
        assertEquals(listOf("a", "b"), now.novels.map { it.id })
        assertEquals(1_000L, now.savedAt)
        assertTrue(now.loaded)
        assertFalse(now.fromCache, "dữ liệu mới từ máy chủ không còn là bản lưu")
        assertNull(now.error)
        assertFalse(now.isLoading)
        assertEquals(DiscoverMode.LIVE, DiscoverFeeds.mode(now))
        assertNull(DiscoverFeeds.notice(now))
        assertEquals("Mất mạng", old.error, "bản cũ không bị sửa")
    }

    @Test
    fun failureWithNothingToShowFallsBackToTheCatalogAndExplainsWhy() {
        val failed = DiscoverFeeds.failed(DiscoverFeeds.begin(DiscoverFeed()), "Không có kết nối mạng.")
        assertFalse(failed.isLoading)
        assertEquals(DiscoverMode.FALLBACK, DiscoverFeeds.mode(failed))
        assertEquals("Không có kết nối mạng. Đang hiển thị tuyển chọn có sẵn.", DiscoverFeeds.notice(failed))
    }

    @Test
    fun failureKeepsTheLastGoodListAndSaysItIsTheSavedOne() {
        val cached = DiscoverFeeds.fromSnapshot(DiscoverSnapshot(savedAt = 77L, novels = novels("x", "y")))
        val failed = DiscoverFeeds.failed(DiscoverFeeds.begin(cached), "Máy chủ phản hồi quá chậm.")
        assertEquals(listOf("x", "y"), failed.novels.map { it.id }, "không được mất danh sách cũ khi mất mạng")
        assertEquals(DiscoverMode.LIVE, DiscoverFeeds.mode(failed))
        assertEquals("Máy chủ phản hồi quá chậm. Đang hiển thị danh sách đã lưu.", DiscoverFeeds.notice(failed))
    }

    @Test
    fun anEmptyServerAnswerFallsBackAndSaysTheServerHasNoPublicNovels() {
        val empty = DiscoverFeeds.succeeded(emptyList(), now = 9L)
        assertTrue(empty.loaded)
        assertEquals(DiscoverMode.FALLBACK, DiscoverFeeds.mode(empty))
        assertEquals(
            "Máy chủ chưa có truyện công khai nào. Đang hiển thị tuyển chọn có sẵn.",
            DiscoverFeeds.notice(empty)
        )
    }

    @Test
    fun refreshingAnExistingListNeverFlashesTheSkeleton() {
        val live = DiscoverFeeds.succeeded(novels("a"), now = 1L).copy(error = "Lỗi cũ")
        val refreshing = DiscoverFeeds.begin(live)
        assertFalse(refreshing.isLoading)
        assertNull(refreshing.error, "bắt đầu tải lại thì xóa câu báo lỗi cũ")
        assertEquals(DiscoverMode.LIVE, DiscoverFeeds.mode(refreshing))
        assertEquals(live.novels, refreshing.novels)
    }

    @Test
    fun aSavedListFromThePreviousRunShowsAtOnceMarkedAsCached() {
        val feed = DiscoverFeeds.fromSnapshot(DiscoverSnapshot(savedAt = 42L, novels = novels("c")))
        assertEquals(DiscoverMode.LIVE, DiscoverFeeds.mode(feed))
        assertTrue(feed.fromCache)
        assertFalse(feed.loaded, "chưa được máy chủ xác nhận trong phiên này")
        assertEquals(42L, feed.savedAt)
        assertEquals(DiscoverFeed(), DiscoverFeeds.fromSnapshot(null))
    }

    @Test
    fun reopeningTheTabSkipsTheNetworkOnlyWhenEveryFeedIsFilledAndRecent() {
        val t = 10_000_000L
        val recent = DiscoverFeeds.succeeded(novels("a"), now = t - 60_000)
        val popular = DiscoverFeeds.succeeded(novels("b"), now = t - 120_000)
        assertTrue(DiscoverFeeds.isFresh(listOf(recent, popular), t))

        val stale = DiscoverFeeds.succeeded(novels("b"), now = t - DiscoverPolicy.FRESH_MS - 1)
        assertFalse(DiscoverFeeds.isFresh(listOf(recent, stale), t), "một bảng đã cũ thì tải lại")
        assertFalse(DiscoverFeeds.isFresh(listOf(recent, DiscoverFeed()), t), "chưa có dữ liệu thì tải")
        assertFalse(DiscoverFeeds.isFresh(listOf(recent, DiscoverFeeds.succeeded(emptyList(), now = t)), t))
    }
}
