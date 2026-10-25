package com.truyencloud.shared.repo

import com.truyencloud.shared.api.BookmarkApi
import com.truyencloud.shared.data.Bookmark
import com.truyencloud.shared.data.BookmarkDao
import com.truyencloud.shared.data.SyncStateDao
import com.truyencloud.shared.data.getCurrentEpochMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlin.random.Random

/**
 * Dấu trang, highlight và ghi chú.
 *
 * Xoá là XOÁ MỀM (bia mộ): dòng bị ẩn khỏi mọi truy vấn hiển thị nhưng còn trong Room để lần
 * đồng bộ sau báo cho máy khác; xoá cứng thì máy kia đẩy bản cũ lên và bookmark hiện lại.
 */
class BookmarkRepository(
    private val bookmarkDao: BookmarkDao,
    bookmarkApi: BookmarkApi = BookmarkApi(),
    /** Lưu con trỏ kéo theo từng tài khoản. Không truyền thì mỗi lần mở app kéo lại từ đầu (vẫn đúng, chỉ tốn hơn). */
    syncStateDao: SyncStateDao? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val sync = BookmarkSyncCoordinator(bookmarkDao, bookmarkApi, syncStateDao, scope)

    fun getBookmarksForChapter(novelId: String, chapterNumber: Int): Flow<List<Bookmark>> =
        bookmarkDao.getBookmarksForChapter(novelId, chapterNumber)

    /** Toàn bộ dấu trang của một truyện, xếp theo chương rồi đoạn — cho danh sách dấu trang cả cuốn. */
    fun getBookmarksForNovel(novelId: String): Flow<List<Bookmark>> =
        bookmarkDao.getBookmarksForNovel(novelId)

    fun getAllBookmarks(): Flow<List<Bookmark>> =
        bookmarkDao.getAllBookmarks()

    suspend fun saveBookmark(
        novelId: String,
        chapterNumber: Int,
        chapterTitle: String,
        paragraphIndex: Int,
        selectedText: String,
        note: String = "",
        colorHex: String = "#FFD54F"
    ): Bookmark {
        val now = getCurrentEpochMillis()
        // Hậu tố ngẫu nhiên: hai dấu trang cùng đoạn trong cùng một mili-giây (hoặc từ hai máy) không trùng id.
        val id = "bm_${novelId.take(60)}_${chapterNumber}_${paragraphIndex}_${now}_${Random.nextInt(0, 0xFFFF).toString(16)}"
        val bookmark = Bookmark(
            id = id,
            novelId = novelId,
            chapterNumber = chapterNumber,
            chapterTitle = chapterTitle,
            paragraphIndex = paragraphIndex,
            selectedText = selectedText,
            note = note,
            colorHex = colorHex,
            createdAt = now,
            updatedAt = now,
            syncDirty = true
        )
        bookmarkDao.upsertBookmark(bookmark)
        sync.requestSync()
        return bookmark
    }

    suspend fun updateBookmark(bookmark: Bookmark) {
        bookmarkDao.upsertBookmark(bookmark.copy(updatedAt = getCurrentEpochMillis(), syncDirty = true))
        sync.requestSync()
    }

    suspend fun deleteBookmark(bookmark: Bookmark) = deleteById(bookmark.id)

    suspend fun deleteById(id: String) {
        bookmarkDao.softDelete(id, getCurrentEpochMillis())
        sync.requestSync()
    }

    /** Xoá mềm mọi dấu trang của một truyện (khi người dùng xoá truyện kèm ghi chú). */
    suspend fun deleteAllForNovel(novelId: String) {
        bookmarkDao.softDeleteForNovel(novelId, getCurrentEpochMillis())
        sync.requestSync()
    }

    /**
     * Đồng bộ hai chiều với máy chủ (xem [BookmarkSyncCoordinator]). Đã giãn cách: gọi dày đặc
     * (mở chương, lưu dấu trang) cũng chỉ chạy tối đa một lượt mỗi ~30 giây.
     *
     * @param force bỏ qua giãn cách — dùng khi ứng dụng ra nền.
     */
    suspend fun syncWithServer(force: Boolean = false): Result<Int> = sync.sync(force)

    /** Hẹn một lượt đồng bộ gộp sau khi sửa dấu trang (không chặn, không ném lỗi). */
    fun requestSync() = sync.requestSync()

    /** Số dấu trang / ghi chú chưa lên máy chủ (kể cả bia mộ xoá) — để cảnh báo trước khi xoá dữ liệu. */
    suspend fun pendingCount(): Int = bookmarkDao.getDirtyBookmarks().size
}
