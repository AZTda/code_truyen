package com.truyencloud.shared.data

import androidx.room.Transactor
import androidx.room.useWriterConnection
import com.truyencloud.shared.repo.LocalDataWiper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Bảo trì cơ sở dữ liệu cục bộ: xoá sạch dữ liệu người dùng và thu nhỏ tệp (VACUUM).
 *
 * Xoá bằng SQL thô trên MỌI bảng người dùng (đọc danh sách từ sqlite_master) thay vì liệt kê từng DAO:
 * bảng mới thêm sau này tự được xoá theo, không ai phải nhớ cập nhật một danh sách thứ hai.
 * Bảng nội bộ của SQLite/Room (`sqlite_*`, `android_*`, `room_*`) được giữ nguyên, nếu không
 * Room sẽ báo sai danh tính cơ sở dữ liệu ở lần mở sau.
 */
class LocalDataMaintenance(
    private val db: LocalCache,
    /** Đường dẫn tệp .db, để đo dung lượng thật trước/sau khi dọn. Null = không đo được. */
    private val databasePath: String? = null
) : LocalDataWiper {

    private val _wipeCount = MutableStateFlow(0)

    /**
     * Tăng sau mỗi lần xoá dữ liệu người dùng. Các con số không đi qua Flow của Room (XP, chuỗi ngày đọc)
     * lắng nghe đây để tính lại ngay, thay vì hiện số của người trước cho tới nhịp cập nhật kế tiếp.
     */
    val wipeCount: StateFlow<Int> = _wipeCount.asStateFlow()

    override suspend fun wipeUserData() {
        db.useWriterConnection { connection ->
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                val tables = mutableListOf<String>()
                usePrepared("SELECT name FROM sqlite_master WHERE type = 'table'") { statement ->
                    while (statement.step()) tables += statement.getText(0)
                }
                for (table in tables.filter(::isUserTable)) {
                    usePrepared("DELETE FROM `$table`") { it.step() }
                }
            }
        }
        // Các Flow đang quan sát phải thấy bảng rỗng ngay, không đợi lần ghi kế tiếp
        try {
            db.invalidationTracker.refreshAsync()
        } catch (_: Exception) {
        }
        _wipeCount.update { it + 1 }
        vacuum()
    }

    /**
     * Thu nhỏ tệp sau khi xoá nhiều dữ liệu: `UPDATE ... = NULL` chỉ trả trang về danh sách trống
     * của SQLite, tệp .db không nhỏ đi nếu không VACUUM. Cố gắng hết sức, không bao giờ ném lỗi.
     */
    suspend fun vacuum(): Boolean {
        val viaConnection = try {
            // Ngoài giao dịch: VACUUM không chạy được bên trong transaction
            db.useWriterConnection { connection ->
                // Chế độ WAL: VACUUM ghi bản mới vào tệp -wal trước, nên phải "checkpoint + cắt" cả trước lẫn sau thì
                // tệp .db và -wal mới thật sự nhỏ lại (không cắt thì tổng dung lượng trên đĩa gần như không đổi).
                runCatching { connection.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { it.step() } }
                connection.usePrepared("VACUUM") { it.step() }
                runCatching { connection.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { it.step() } }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        return viaConnection || PlatformDatabaseOps.vacuumFallback(db)
    }

    /** Tổng dung lượng .db + -wal + -shm trên đĩa (byte); 0 nếu không đo được. */
    fun databaseBytes(): Long {
        val path = databasePath ?: return 0L
        return PlatformDatabaseOps.fileBytes(path) +
            PlatformDatabaseOps.fileBytes("$path-wal") +
            PlatformDatabaseOps.fileBytes("$path-shm")
    }

    companion object {
        fun isUserTable(name: String): Boolean =
            !name.startsWith("sqlite_") && !name.startsWith("android_") && !name.startsWith("room_")
    }
}

/** Thao tác cần API riêng của từng nền tảng. */
internal expect object PlatformDatabaseOps {
    fun fileBytes(path: String): Long

    /** Đường lùi khi VACUUM qua kết nối Room thất bại (Android: openHelper). iOS không có. */
    suspend fun vacuumFallback(db: LocalCache): Boolean
}
