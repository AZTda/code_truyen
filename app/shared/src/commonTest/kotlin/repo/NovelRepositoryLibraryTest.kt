package com.truyencloud.shared.repo

import com.truyencloud.shared.api.NovelApi
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.FakeChapterDao
import com.truyencloud.shared.data.FakeNovelDao
import com.truyencloud.shared.data.FakeSyncStateDao
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.SyncKeys
import com.truyencloud.shared.data.runNow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NovelRepositoryLibraryTest {
    private val novels = FakeNovelDao()
    private val chapters = FakeChapterDao()
    private val state = FakeSyncStateDao()
    private val repo = NovelRepository(NovelApi(), novels, chapterDao = chapters, bookmarkDao = null, syncStateDao = state)

    private val novel = Novel(id = "n1", title = "Truyện", titleVi = "Truyện Việt", lastReadChapter = 99, isFavorite = true)

    private inline fun <T> loggedIn(block: () -> T): T {
        val old = TruyenCloudApi.authToken
        TruyenCloudApi.authToken = "payload.sig"
        try {
            return block()
        } finally {
            TruyenCloudApi.authToken = old
        }
    }

    @Test
    fun refreshingMetadataNeverInsertsANovelThatIsNotInTheLibrary() {
        assertNull(runNow { repo.refreshMetadataIfInLibrary(novel) })
        assertNull(novels.rows["n1"], "mở trang chi tiết không được làm bẩn tủ sách")
    }

    @Test
    fun refreshingMetadataKeepsProgressShelfAndFavorite() {
        novels.rows["n1"] = Novel(id = "n1", title = "Cũ", lastReadChapter = 12, shelfName = "completed", isFavorite = true, lastReadAt = 77)
        val row = assertNotNull(runNow { repo.refreshMetadataIfInLibrary(novel.copy(title = "Mới", totalChapters = 300)) })
        assertEquals("Mới", row.title)
        assertEquals(300, row.totalChapters)
        assertEquals(12, row.lastReadChapter, "không ghi đè tiến độ bằng số liệu cũ trong danh sách")
        assertEquals("completed", row.shelfName)
        assertTrue(row.isFavorite)
    }

    @Test
    fun addToLibraryCreatesAReadingShelfRowWithoutProgress() {
        val row = runNow { repo.addToLibrary(novel) }
        assertEquals("reading", row.shelfName)
        assertEquals(0, row.lastReadChapter, "thêm vào tủ không mang theo tiến độ của danh sách")
        assertFalse(row.isFavorite)
        assertTrue(row.addedAt > 0)
        assertTrue(row.syncDirty)
    }

    @Test
    fun addingTwiceKeepsExistingProgress() {
        novels.rows["n1"] = Novel(id = "n1", title = "T", lastReadChapter = 12)
        val row = runNow { repo.addToLibrary(novel) }
        assertEquals(12, row.lastReadChapter)
    }

    @Test
    fun favoriteHeartOnAnUnlistedNovelAddsItAsFavorite() {
        assertTrue(runNow { repo.toggleFavorite(novel.copy(isFavorite = false)) })
        assertTrue(novels.rows["n1"]!!.isFavorite)
        // Bấm lần nữa thì bỏ yêu thích nhưng vẫn ở trong tủ.
        assertFalse(runNow { repo.toggleFavorite(novel) })
        assertFalse(novels.rows["n1"]!!.isFavorite)
        assertNotNull(novels.rows["n1"])
    }

    @Test
    fun shelfAndFavoriteChangesAreStampedForSync() {
        novels.rows["n1"] = Novel(id = "n1", title = "T", syncDirty = false)
        runNow { repo.updateShelf("n1", "completed") }
        val row = novels.rows["n1"]!!
        assertEquals("completed", row.shelfName)
        assertTrue(row.syncDirty)
        assertTrue(row.syncUpdatedAt > 0)
    }

    @Test
    fun removingDeletesTheRowAndOptionallyTheDownloadedChapters() {
        novels.rows["n1"] = Novel(id = "n1", title = "T")
        runNow { chapters.insertChaptersIfAbsent(listOf(Chapter("n1_1", "n1", 1, "c"))) }
        runNow { repo.removeFromLibrary("n1", deleteOfflineData = false) }
        assertNull(novels.rows["n1"])
        assertEquals(1, chapters.rows.size, "giữ chương đã tải khi không yêu cầu xoá")
        novels.rows["n1"] = Novel(id = "n1", title = "T")
        runNow { repo.removeFromLibrary("n1", deleteOfflineData = true) }
        assertEquals(0, chapters.rows.size)
    }

    @Test
    fun removalIsRememberedForTheServerOnlyWhenLoggedIn() {
        novels.rows["n1"] = Novel(id = "n1", title = "T", lastReadChapter = 4)
        runNow { repo.removeFromLibrary("n1", deleteOfflineData = false) }
        assertNull(state.rows[SyncKeys.libraryRemoved("n1")], "khách không đồng bộ nên không cần bia mộ")

        novels.rows["n1"] = Novel(id = "n1", title = "T", lastReadChapter = 4)
        loggedIn { runNow { repo.removeFromLibrary("n1", deleteOfflineData = false) } }
        val tomb = assertNotNull(state.rows[SyncKeys.libraryRemoved("n1")])
        assertTrue(tomb.longValue > 0)
        assertEquals("4", tomb.textValue)
    }

    @Test
    fun addingBackClearsTheRemovalTombstone() {
        novels.rows["n1"] = Novel(id = "n1", title = "T")
        loggedIn { runNow { repo.removeFromLibrary("n1", deleteOfflineData = false) } }
        runNow { repo.addToLibrary(novel) }
        assertNull(state.rows[SyncKeys.libraryRemoved("n1")])
    }

    @Test
    fun migratingToTheCanonicalIdKeepsProgressAndRetiresTheOldId() {
        novels.rows["2157106"] = Novel(id = "2157106", title = "T", lastReadChapter = 8, shelfName = "completed", isFavorite = true, lastReadAt = 500, syncUpdatedAt = 500)
        loggedIn {
            assertTrue(runNow { repo.migrateNovelId("2157106", "jjwxc_2157106") })
        }
        assertNull(novels.rows["2157106"])
        val moved = assertNotNull(novels.rows["jjwxc_2157106"])
        assertEquals(8, moved.lastReadChapter)
        assertEquals("completed", moved.shelfName)
        assertTrue(moved.isFavorite)
        assertTrue(moved.syncDirty)
        assertNotNull(state.rows[SyncKeys.libraryRemoved("2157106")], "id cũ phải được báo là đã gỡ để máy khác không dựng hai hàng")
    }

    @Test
    fun migrationDoesNothingWhenNotInLibraryOrTargetExists() {
        assertFalse(runNow { repo.migrateNovelId("a", "b") })
        novels.rows["a"] = Novel(id = "a", title = "A")
        novels.rows["b"] = Novel(id = "b", title = "B")
        assertFalse(runNow { repo.migrateNovelId("a", "b") })
        assertFalse(runNow { repo.migrateNovelId("a", "a") })
    }
}
