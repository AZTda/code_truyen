package com.truyencloud.shared.repo

import com.truyencloud.shared.data.Chapter
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

class ReaderRepositoryProgressTest {
    private val novels = FakeNovelDao()
    private val chapters = FakeChapterDao()
    private val state = FakeSyncStateDao()

    // progressApi = null: khách/chưa cấu hình, không có gì được đẩy, chỉ kiểm tra phần ghi cục bộ.
    private val repo = ReaderRepository(novels, chapters, readingSessionDao = null, progressApi = null, syncStateDao = state)

    private val novel = Novel(id = "n1", title = "Truyện", titleVi = "Truyện Việt", totalChapters = 100)

    private fun chapter(n: Int) = Chapter(id = "n1_$n", novelId = "n1", chapterNumber = n, title = "C$n")

    @Test
    fun firstProgressSaveInsertsTheNovelIntoTheLibrary() {
        runNow { repo.saveReadingProgress(novel, "n1", 3, 0.2f) }
        val row = assertNotNull(novels.rows["n1"])
        assertEquals(3, row.lastReadChapter)
        assertEquals("reading", row.shelfName)
        assertFalse(row.isFavorite)
        assertTrue(row.addedAt > 0)
        assertTrue(row.syncDirty, "tiến độ mới phải chờ được đẩy lên")
        assertTrue(row.syncUpdatedAt > 0)
    }

    @Test
    fun progressWithoutANovelObjectDoesNotInventALibraryRow() {
        runNow { repo.saveReadingProgress(null, "n1", 3, 0.2f) }
        assertNull(novels.rows["n1"])
    }

    @Test
    fun savingProgressAgainKeepsShelfFavoriteAndAddedAt() {
        novels.rows["n1"] = novel.copy(shelfName = "completed", isFavorite = true, addedAt = 42L, lastReadChapter = 1)
        runNow { repo.saveReadingProgress(novel, "n1", 7, 0.5f) }
        val row = assertNotNull(novels.rows["n1"])
        assertEquals(7, row.lastReadChapter)
        assertEquals("completed", row.shelfName)
        assertTrue(row.isFavorite)
        assertEquals(42L, row.addedAt)
    }

    @Test
    fun chapterIsMarkedReadOnlyAfterScrollingNearTheEnd() {
        runNow { chapters.insertChaptersIfAbsent(listOf(chapter(1), chapter(2))) }
        runNow { repo.saveReadingProgress(novel, "n1", 1, 0f) }
        assertFalse(chapters.rows["n1_1"]!!.isRead, "vừa mở chương (scroll = 0) chưa phải đã đọc")
        runNow { repo.saveReadingProgress(novel, "n1", 1, 0.84f) }
        assertFalse(chapters.rows["n1_1"]!!.isRead)
        assertEquals(0.84f, chapters.rows["n1_1"]!!.scrollPosition)
        runNow { repo.saveReadingProgress(novel, "n1", 1, ReaderRepository.READ_SCROLL_THRESHOLD) }
        assertTrue(chapters.rows["n1_1"]!!.isRead)
        // Đọc xong rồi cuộn ngược lên đầu: vẫn là đã đọc.
        runNow { repo.saveReadingProgress(novel, "n1", 1, 0.1f) }
        assertTrue(chapters.rows["n1_1"]!!.isRead)
    }

    @Test
    fun missingChapterRowIsCreatedSoScrollIsNotLost() {
        runNow { repo.saveReadingProgress(novel, "n1", 4, 0.6f) }
        val row = assertNotNull(chapters.rows["n1_4"])
        assertEquals(0.6f, row.scrollPosition)
        assertFalse(row.isRead)
    }

    @Test
    fun readingAgainClearsThePendingRemovalTombstone() {
        state.rows[SyncKeys.libraryRemoved("n1")] = SyncState(SyncKeys.libraryRemoved("n1"), longValue = 5L)
        runNow { repo.saveReadingProgress(novel, "n1", 1, 0f) }
        assertNull(state.rows[SyncKeys.libraryRemoved("n1")])
    }

    @Test
    fun scrollIsClampedToTheZeroOneRange() {
        runNow { chapters.insertChaptersIfAbsent(listOf(chapter(1))) }
        runNow { repo.saveReadingProgress(novel, "n1", 1, 7f) }
        assertEquals(1f, chapters.rows["n1_1"]!!.scrollPosition)
        assertTrue(chapters.rows["n1_1"]!!.isRead)
    }
}
