package com.truyencloud.shared.repo

import com.truyencloud.shared.data.Bookmark
import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.FakeBookmarkDao
import com.truyencloud.shared.data.FakeChapterDao
import com.truyencloud.shared.data.FakeNovelDao
import com.truyencloud.shared.data.FakeSyncStateDao
import com.truyencloud.shared.data.InMemoryReviewLogDao
import com.truyencloud.shared.data.InMemorySavedWordDao
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.ReviewLog
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.data.SyncKeys
import com.truyencloud.shared.data.SyncState
import com.truyencloud.shared.data.runNow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemovedContentCleanupTest {
    private val novels = FakeNovelDao()
    private val chapters = FakeChapterDao()
    private val bookmarks = FakeBookmarkDao()
    private val words = InMemorySavedWordDao()
    private val logs = InMemoryReviewLogDao()
    private val state = FakeSyncStateDao()
    private val cleanup = RemovedContentCleanup(novels, chapters, bookmarks, words, logs, state)

    private fun novel(id: String, sourceId: String) { novels.rows[id] = Novel(id = id, title = id, sourceId = sourceId) }
    private fun chapter(novelId: String) { chapters.rows["${novelId}_1"] = Chapter(id = "${novelId}_1", novelId = novelId, chapterNumber = 1, title = "1") }
    private fun word(id: String, novelId: String = "", bookTitle: String = "", deletedAt: Long = 0L, synced: Boolean = true) {
        words.rows.add(SavedWord(id = id, word = id, novelId = novelId, bookTitle = bookTitle, deletedAt = deletedAt, isSynced = synced, updatedAt = 1L))
        logs.rows.add(ReviewLog(id = (logs.rows.size + 1).toLong(), wordId = id, reviewedAt = 1L, rating = 3))
    }
    private fun bookmark(id: String, novelId: String, deletedAt: Long = 0L, dirty: Boolean = false) {
        bookmarks.rows[id] = Bookmark(id = id, novelId = novelId, chapterNumber = 1, selectedText = "x", deletedAt = deletedAt, syncDirty = dirty)
    }

    @Test
    fun removesJjwxcAndBostonNovelsWithTheirChapters() {
        novel("3535079", "jjwxc")          // id trần, chỉ nhận ra nhờ nguồn
        novel("jjwxc_10632928", "")        // id chuẩn có tiền tố
        novel("2157106", "local")          // truyện mẫu Boston (bản chưa phát hành đổi nguồn thành local)
        novel("41682", "piaotia")
        listOf("3535079", "jjwxc_10632928", "2157106", "41682").forEach(::chapter)
        chapter("boston-love-story")       // đã gỡ khỏi tủ nhưng còn chương đã tải

        val report = runNow { cleanup.run() }

        assertEquals(setOf("41682"), novels.rows.keys)
        assertEquals(setOf("41682"), chapters.rows.values.map { it.novelId }.toSet())
        assertEquals(3, report.novels)
    }

    @Test
    fun removesWordsAndReviewLogsIncludingUnsentTombstones() {
        novel("3535079", "jjwxc")
        word("w-jjwxc", novelId = "3535079")
        word("w-boston", novelId = "boston-love-story")
        word("serendipity", bookTitle = "Boston Love Story")              // từ mẫu: chỉ có tên sách
        word("w-tomb", novelId = "jjwxc_2157106", deletedAt = 5L, synced = false)
        word("ephemeral", bookTitle = "Literary Studies")
        word("w-other", novelId = "41682", bookTitle = "Kiếm Lai")

        val report = runNow { cleanup.run() }

        assertEquals(listOf("ephemeral", "w-other"), words.rows.map { it.id })
        assertEquals(setOf("ephemeral", "w-other"), logs.rows.map { it.wordId }.toSet())
        assertEquals(4, report.words)
    }

    @Test
    fun removesBookmarksAndLibraryTombstonesWithoutWritingNewOnes() {
        bookmark("b1", "2157106")
        bookmark("b2", "jjwxc_5167880", deletedAt = 9L, dirty = true)   // bia mộ chưa đẩy
        bookmark("b3", "41682")
        listOf(SyncKeys.libraryRemoved("2157106"), SyncKeys.libraryRemoved("jjwxc_30678"), SyncKeys.libraryRemoved("41682"),
            SyncKeys.vocabCursor("acc")).forEach { state.rows[it] = SyncState(it, longValue = 1L) }

        val report = runNow { cleanup.run() }

        assertEquals(setOf("b3"), bookmarks.rows.keys)
        assertEquals(setOf(SyncKeys.libraryRemoved("41682"), SyncKeys.vocabCursor("acc")), state.rows.keys)
        assertEquals(2, report.bookmarks)
        assertEquals(2, report.tombstones)
    }

    @Test
    fun secondRunChangesNothing() {
        novel("2157106", "jjwxc")
        word("serendipity", bookTitle = "Boston Love Story")
        bookmark("b1", "2157106")
        runNow { cleanup.run() }

        assertEquals(0, runNow { cleanup.run() }.total)
    }

    @Test
    fun policyKeepsLookalikeIds() {
        assertTrue(RemovedContent.isRemovedNovel("x", "jjwxc"))
        assertFalse(RemovedContent.isRemovedNovel("21571060", "piaotia"))
        assertFalse(RemovedContent.isRemovedNovel("my-jjwxc_notes", ""))
        assertFalse(RemovedContent.isRemovedWord(SavedWord(id = "a", word = "a", bookTitle = "Boston Legal"), emptySet()))
    }
}
