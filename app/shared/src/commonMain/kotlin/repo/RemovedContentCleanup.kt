package com.truyencloud.shared.repo

import com.truyencloud.shared.data.BookmarkDao
import com.truyencloud.shared.data.ChapterDao
import com.truyencloud.shared.data.LocalCache
import com.truyencloud.shared.data.NovelDao
import com.truyencloud.shared.data.ReviewLogDao
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.data.SavedWordDao
import com.truyencloud.shared.data.SyncKeys
import com.truyencloud.shared.data.SyncStateDao
import com.truyencloud.shared.data.TermMemoryDao
import com.truyencloud.shared.prefs.PrefKeys
import com.truyencloud.shared.prefs.SettingsStore
import kotlinx.coroutines.flow.first

/**
 * Nội dung máy chủ đã gỡ hẳn ngày 16/10/2026: nguồn trả phí jjwxc (Tấn Giang) và truyện mẫu "Chuyện Tình Boston"
 * (kể cả từ vựng lưu từ truyện đó). Máy chủ đã xoá truyện, chương, tiến độ và từ vựng; máy chỉ còn bản sao cục bộ.
 */
object RemovedContent {
    val SOURCE_IDS = setOf("jjwxc")

    /** Id chuẩn máy chủ cấp cho truyện jjwxc. */
    const val ID_PREFIX = "jjwxc_"

    /** Truyện Boston: id jjwxc cũ (gieo sẵn ở lần chạy đầu tới bản 1.4.0) và bản tải lên trên máy chủ. */
    val NOVEL_IDS = setOf("2157106", "boston-love-story")

    /** Tên sách của từ đã lưu (từ mẫu "serendipity" chỉ ghi tên sách, không có novelId). */
    private val BOOK_TITLES = setOf("boston love story", "波士顿爱情故事", "chuyện tình boston")

    fun isRemovedNovel(id: String, sourceId: String = ""): Boolean =
        sourceId in SOURCE_IDS || id.startsWith(ID_PREFIX) || id in NOVEL_IDS

    fun isRemovedWord(word: SavedWord, removedNovelIds: Set<String>): Boolean =
        word.novelId in removedNovelIds || isRemovedNovel(word.novelId) ||
            word.bookTitle.trim().lowercase() in BOOK_TITLES
}

/**
 * Dọn bản sao trên máy của [RemovedContent]: hàng trong tủ sách, chương đã tải, thuật ngữ riêng, dấu trang, từ vựng
 * (kèm log ôn tập) và bia mộ "đã gỡ khỏi tủ" chưa gửi.
 *
 * Xoá CỨNG, không ghi bia mộ: máy chủ đã xoá các hàng này, bia mộ đẩy lên chỉ dựng lại dấu vết ở đó.
 * Chạy mỗi lần mở ứng dụng (idempotent, đã sạch thì không đổi gì) vì máy khác còn bản cũ vẫn có thể đẩy lại
 * dữ liệu lên máy chủ cho tới khi được cập nhật, rồi lần kéo kế tiếp đem nó về đây.
 */
class RemovedContentCleanup(
    private val novelDao: NovelDao,
    private val chapterDao: ChapterDao,
    private val bookmarkDao: BookmarkDao,
    private val savedWordDao: SavedWordDao,
    private val reviewLogDao: ReviewLogDao,
    private val syncStateDao: SyncStateDao,
    private val termMemoryDao: TermMemoryDao? = null,
    private val settings: SettingsStore? = null
) {
    data class Report(val novels: Int, val words: Int, val bookmarks: Int, val tombstones: Int) {
        val total: Int get() = novels + words + bookmarks + tombstones
    }

    suspend fun run(): Report {
        val inLibrary = novelDao.getAllNovels().first()
            .filter { RemovedContent.isRemovedNovel(it.id, it.sourceId) }
            .map { it.id }
            .toSet()
        // Id cố định: truyện đã gỡ khỏi tủ từ trước nhưng còn giữ chương đã tải.
        val novelIds = inLibrary + RemovedContent.NOVEL_IDS + RemovedContent.NOVEL_IDS.map { RemovedContent.ID_PREFIX + it }
        for (id in novelIds) {
            if (id in inLibrary) novelDao.deleteNovelById(id)
            chapterDao.deleteChaptersForNovel(id)
            termMemoryDao?.deleteAllForNovel(id)
            settings?.remove(PrefKeys.NOTIFIED_CHAPTERS_PREFIX + id)
        }

        // Gồm cả bia mộ chưa đẩy: đẩy lên thì máy chủ lại có dòng mang mã truyện đã gỡ.
        val bookmarkIds = (bookmarkDao.getAllBookmarksList() + bookmarkDao.getDirtyBookmarks())
            .filter { it.novelId in novelIds || RemovedContent.isRemovedNovel(it.novelId) }
            .map { it.id }
            .distinct()
        if (bookmarkIds.isNotEmpty()) bookmarkDao.hardDeleteByIds(bookmarkIds)

        // getChangedForSync(0) trả mọi dòng có dấu thời gian, kể cả bia mộ; getAllWordsOnce bù các dòng cũ chưa có.
        val words = (savedWordDao.getAllWordsOnce() + savedWordDao.getChangedForSync(0L))
            .distinctBy { it.id }
            .filter { RemovedContent.isRemovedWord(it, novelIds) }
        for (word in words) {
            reviewLogDao.deleteForWord(word.id)
            savedWordDao.purge(word.id)
        }

        val tombstones = syncStateDao.listByPrefix(SyncKeys.LIBRARY_REMOVED_PREFIX).filter {
            val id = it.stateKey.removePrefix(SyncKeys.LIBRARY_REMOVED_PREFIX)
            id in novelIds || RemovedContent.isRemovedNovel(id)
        }
        tombstones.forEach { syncStateDao.delete(it.stateKey) }

        return Report(inLibrary.size, words.size, bookmarkIds.size, tombstones.size)
    }

    companion object {
        fun forDatabase(db: LocalCache, settings: SettingsStore?) = RemovedContentCleanup(
            novelDao = db.novelDao(),
            chapterDao = db.chapterDao(),
            bookmarkDao = db.bookmarkDao(),
            savedWordDao = db.savedWordDao(),
            reviewLogDao = db.reviewLogDao(),
            syncStateDao = db.syncStateDao(),
            termMemoryDao = db.termMemoryDao(),
            settings = settings
        )
    }
}
