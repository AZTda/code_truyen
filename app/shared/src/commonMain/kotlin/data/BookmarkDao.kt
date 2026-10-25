package com.truyencloud.shared.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Mọi truy vấn hiển thị lọc `deletedAt = 0`: bookmark đã xoá chỉ còn là "bia mộ" chờ đồng bộ.
 */
@Dao
interface BookmarkDao {
    @Upsert
    suspend fun upsertBookmark(bookmark: Bookmark)

    @Upsert
    suspend fun upsertAll(bookmarks: List<Bookmark>)

    @Query("SELECT * FROM bookmarks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): Bookmark?

    @Query("SELECT * FROM bookmarks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<Bookmark>

    @Query("SELECT * FROM bookmarks WHERE novelId = :novelId AND chapterNumber = :chapterNumber AND deletedAt = 0 ORDER BY paragraphIndex ASC, createdAt ASC")
    fun getBookmarksForChapter(novelId: String, chapterNumber: Int): Flow<List<Bookmark>>

    /** Toàn bộ dấu trang của một truyện, xếp theo chương rồi đoạn (danh sách dấu trang cả cuốn). */
    @Query("SELECT * FROM bookmarks WHERE novelId = :novelId AND deletedAt = 0 ORDER BY chapterNumber ASC, paragraphIndex ASC, createdAt ASC")
    fun getBookmarksForNovel(novelId: String): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE deletedAt = 0 ORDER BY createdAt DESC")
    fun getAllBookmarks(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE deletedAt = 0 ORDER BY createdAt DESC")
    suspend fun getAllBookmarksList(): List<Bookmark>

    // ── Xoá mềm & đồng bộ ────────────────────────────────────────────────

    /** Xoá mềm: giữ nội dung làm bia mộ, updatedAt = deletedAt = [now] để thắng mọi bản sửa cũ hơn. */
    @Query("UPDATE bookmarks SET deletedAt = :now, updatedAt = :now, syncDirty = 1 WHERE id = :id AND deletedAt = 0")
    suspend fun softDelete(id: String, now: Long): Int

    /** Xoá mềm mọi dấu trang còn sống của một truyện (khi người dùng chọn xoá cả ghi chú). */
    @Query("UPDATE bookmarks SET deletedAt = :now, updatedAt = :now, syncDirty = 1 WHERE novelId = :novelId AND deletedAt = 0")
    suspend fun softDeleteForNovel(novelId: String, now: Long): Int

    @Query("SELECT * FROM bookmarks WHERE syncDirty = 1")
    suspend fun getDirtyBookmarks(): List<Bookmark>

    /**
     * Gỡ cờ "chưa đẩy" cho các dòng vừa đẩy xong. Chỉ gỡ dòng có updatedAt <= [beforeMs] (mốc
     * lúc bắt đầu đẩy): dòng bị sửa tiếp trong lúc gọi mạng có updatedAt > mốc nên vẫn dirty.
     */
    @Query("UPDATE bookmarks SET syncDirty = 0 WHERE id IN (:ids) AND updatedAt <= :beforeMs")
    suspend fun markPushed(ids: List<String>, beforeMs: Long): Int

    @Query("DELETE FROM bookmarks WHERE id IN (:ids)")
    suspend fun hardDeleteByIds(ids: List<String>): Int

    /** Dọn bia mộ đã được máy chủ xác nhận và quá hạn (máy chủ cũng chỉ trả tombstone trong 90 ngày). */
    @Query("DELETE FROM bookmarks WHERE deletedAt > 0 AND deletedAt < :cutoffMs AND syncDirty = 0")
    suspend fun purgeTombstonesBefore(cutoffMs: Long): Int

    /** Dời dấu trang sang id truyện chuẩn (máy chủ trả id khác id ứng dụng đã dùng). Đánh dấu dirty để đẩy lên. */
    @Query("UPDATE bookmarks SET novelId = :newId, updatedAt = :now, syncDirty = 1 WHERE novelId = :oldId")
    suspend fun migrateNovelId(oldId: String, newId: String, now: Long): Int

    /** Hàng có từ trước chưa có updatedAt: lấy giờ tạo làm giờ sửa để so sánh được với máy chủ. */
    @Query("UPDATE bookmarks SET updatedAt = createdAt WHERE updatedAt = 0")
    suspend fun backfillUpdatedAt(): Int
}
