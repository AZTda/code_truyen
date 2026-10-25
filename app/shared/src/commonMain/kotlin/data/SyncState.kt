package com.truyencloud.shared.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * Sổ ghi nhớ nhỏ dạng khoá-giá trị cho đồng bộ, nằm CHUNG cơ sở dữ liệu với dữ liệu được
 * đồng bộ. Để chung là có chủ ý: con trỏ đồng bộ nằm ở SharedPreferences còn dữ liệu nằm ở
 * Room thì xoá dữ liệu ứng dụng / di trú lại làm hai bên lệch nhau (con trỏ nói "đã kéo tới
 * mốc X" trong khi bookmark đã mất) và những gì đã mất không bao giờ được kéo lại.
 *
 * Các khoá đang dùng xem [SyncKeys].
 */
@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey
    val stateKey: String,
    val longValue: Long = 0L,
    val textValue: String = ""
)

@Dao
interface SyncStateDao {
    @Query("SELECT longValue FROM sync_state WHERE stateKey = :key LIMIT 1")
    suspend fun getLong(key: String): Long?

    /** Mọi hàng có khoá bắt đầu bằng [prefix] (so sánh đúng từng ký tự, không dùng LIKE vì `_` là ký tự thay thế). */
    @Query("SELECT * FROM sync_state WHERE substr(stateKey, 1, length(:prefix)) = :prefix")
    suspend fun listByPrefix(prefix: String): List<SyncState>

    @Upsert
    suspend fun put(state: SyncState)

    @Query("DELETE FROM sync_state WHERE stateKey = :key")
    suspend fun delete(key: String)
}

/** Tên khoá của [SyncState]. */
object SyncKeys {
    /** Con trỏ kéo bookmark (giờ máy chủ, ms) theo từng tài khoản. */
    fun bookmarkCursor(accountKey: String) = "bm_cursor:$accountKey"

    /** Con trỏ kéo từ vựng + nhật ký ôn (giờ máy chủ, ms) theo từng tài khoản. */
    fun vocabCursor(accountKey: String) = "vocab_cursor:$accountKey"

    /** Truyện đã bị xoá khỏi tủ nhưng chưa báo được cho máy chủ; giá trị = giờ xoá (ms). */
    const val LIBRARY_REMOVED_PREFIX = "lib_removed:"

    fun libraryRemoved(novelId: String) = LIBRARY_REMOVED_PREFIX + novelId
}
