package com.truyencloud.shared.repo

import com.truyencloud.shared.api.ProgressNovelSummary
import com.truyencloud.shared.api.ProgressRemoteResponse
import com.truyencloud.shared.data.FakeChapterDao
import com.truyencloud.shared.data.FakeNovelDao
import com.truyencloud.shared.data.FakeSyncStateDao
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.SyncKeys
import com.truyencloud.shared.data.SyncState
import com.truyencloud.shared.data.runNow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressRemoteApplierTest {
    private val novels = FakeNovelDao()
    private val chapters = FakeChapterDao()
    private val state = FakeSyncStateDao()
    private val applier = ProgressRemoteApplier(novels, chapters, state)

    private fun remote(
        id: String = "n1",
        chapter: Int = 5,
        scroll: Float = 0.4f,
        shelf: String = "reading",
        favorite: Boolean = false,
        stamp: Long = 1_000L,
        payload: Boolean = true
    ) = ProgressRemoteResponse(
        novel_id = id, current_chapter = chapter, scroll_position = scroll, shelf_name = shelf,
        is_favorite = favorite, updated_at_ms = stamp,
        novel = if (payload) ProgressNovelSummary(id = id, title = "Tiêu đề", title_vi = "Tên Việt", total_chapters = 120, source_url = "https://x/n1", cover_url = "c.jpg") else null
    )

    @Test
    fun newDeviceRebuildsTheBookshelfFromServerPayload() {
        assertTrue(runNow { applier.apply(remote(shelf = "completed", favorite = true), fallback = null) })
        val n = assertNotNull(novels.rows["n1"])
        assertEquals("Tên Việt", n.titleVi)
        assertEquals(5, n.lastReadChapter)
        assertEquals("completed", n.shelfName)
        assertTrue(n.isFavorite)
        assertEquals(120, n.totalChapters)
        assertEquals(1_000L, n.syncUpdatedAt)
        assertFalse(n.syncDirty, "bản vừa kéo về chính là trạng thái máy chủ, không phải thay đổi chờ đẩy")
    }

    @Test
    fun remoteScrollIsCarriedOverThroughAMinimalChapterRow() {
        runNow { applier.apply(remote(chapter = 5, scroll = 0.4f), fallback = null) }
        val row = assertNotNull(chapters.rows["n1_5"])
        assertEquals(0.4f, row.scrollPosition)
        assertFalse(row.isRead, "áp vị trí cuộn từ máy chủ không được tự đánh dấu đã đọc")
    }

    @Test
    fun serverRowWithoutPayloadCannotCreateANovel() {
        assertFalse(runNow { applier.apply(remote(payload = false), fallback = null) })
        assertNull(novels.rows["n1"])
        // Có truyện dự phòng (mở trang chi tiết) thì dựng được.
        assertTrue(runNow { applier.apply(remote(payload = false), fallback = Novel(id = "n1", title = "Dự phòng")) })
        assertEquals("Dự phòng", novels.rows["n1"]?.title)
    }

    @Test
    fun newerServerRowUpdatesProgressShelfAndFavorite() {
        novels.rows["n1"] = Novel(id = "n1", title = "T", lastReadChapter = 2, lastReadAt = 500, syncUpdatedAt = 500, syncDirty = false)
        assertTrue(runNow { applier.apply(remote(chapter = 9, shelf = "planned", favorite = true, stamp = 2_000), fallback = null) })
        val n = assertNotNull(novels.rows["n1"])
        assertEquals(9, n.lastReadChapter)
        assertEquals("planned", n.shelfName)
        assertTrue(n.isFavorite)
        assertEquals(2_000L, n.localStamp)
    }

    @Test
    fun sameChapterStillAdoptsNewerServerScroll() {
        novels.rows["n1"] = Novel(id = "n1", title = "T", lastReadChapter = 5, lastReadAt = 500, syncUpdatedAt = 500, syncDirty = false)
        runNow { chapters.insertChaptersIfAbsent(listOf(com.truyencloud.shared.data.Chapter("n1_5", "n1", 5, "c", scrollPosition = 0.1f))) }
        assertTrue(runNow { applier.apply(remote(chapter = 5, scroll = 0.8f, stamp = 900), fallback = null) })
        assertEquals(0.8f, chapters.rows["n1_5"]?.scrollPosition)
    }

    @Test
    fun olderServerRowNeverOverwritesNewerLocalProgress() {
        novels.rows["n1"] = Novel(id = "n1", title = "T", lastReadChapter = 30, lastReadAt = 5_000, syncUpdatedAt = 5_000, syncDirty = true)
        assertFalse(runNow { applier.apply(remote(chapter = 3, stamp = 1_000), fallback = null) })
        assertEquals(30, novels.rows["n1"]?.lastReadChapter)
        assertTrue(novels.rows["n1"]?.syncDirty == true, "bản cục bộ mới hơn vẫn chờ được đẩy lên")
    }

    @Test
    fun serverTombstoneRemovesTheNovelOnThisDevice() {
        novels.rows["n1"] = Novel(id = "n1", title = "T", lastReadChapter = 2, lastReadAt = 500, syncUpdatedAt = 500, syncDirty = false)
        assertTrue(runNow { applier.apply(remote(shelf = SHELF_REMOVED, stamp = 900, payload = false), fallback = null) })
        assertNull(novels.rows["n1"])
    }

    @Test
    fun locallyRemovedNovelIsNotResurrectedByAnOlderServerRow() {
        state.rows[SyncKeys.libraryRemoved("n1")] = SyncState(SyncKeys.libraryRemoved("n1"), longValue = 5_000L)
        assertFalse(runNow { applier.apply(remote(stamp = 1_000), fallback = null) })
        assertNull(novels.rows["n1"])
        // Đọc tiếp ở máy khác SAU khi xoá: truyện quay lại và bia mộ cục bộ được dọn.
        assertTrue(runNow { applier.apply(remote(stamp = 9_000), fallback = null) })
        assertNotNull(novels.rows["n1"])
        assertNull(state.rows[SyncKeys.libraryRemoved("n1")])
    }

    @Test
    fun serverNovelMetadataIsRecordedInServerNovelMetaStore() {
        com.truyencloud.shared.library.ServerNovelMetaStore.clear()
        val row = ProgressRemoteResponse(
            novel_id = "n_meta", current_chapter = 3, updated_at_ms = 1_000L,
            novel = ProgressNovelSummary(
                id = "n_meta", title = "T", translated_chapters = 42, overnight_enabled = true, category = "Tiên Hiệp"
            )
        )
        assertTrue(runNow { applier.apply(row, fallback = null) })
        val meta = assertNotNull(com.truyencloud.shared.library.ServerNovelMetaStore.get("n_meta"))
        assertEquals(42, meta.translatedChapters)
        assertTrue(meta.overnightEnabled)
        assertEquals("Tiên Hiệp", meta.category)
    }
}
