package com.truyencloud.shared.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Trí nhớ thuật ngữ / tên riêng của một bộ truyện.
 *
 * Trước đây mỗi chương được dịch độc lập, không có trí nhớ, nên cùng một cái tên
 * có thể ra khác nhau giữa chương 1 và chương 200 (岑星 -> "Sầm Tinh" rồi "Sâm Tinh").
 * Máy chủ từng vá tạm bằng một dict hardcode 6 từ cho đúng một bộ truyện.
 * Bảng này thay thế cách vá đó: mỗi truyện tự tích luỹ bảng thuật ngữ của riêng nó,
 * và bảng đó được gửi kèm mọi lần dịch sau.
 */
@Serializable
@Entity(
    tableName = "term_memories",
    indices = [
        // Một thuật ngữ chỉ có duy nhất một cách dịch trong phạm vi một truyện.
        Index(value = ["novelId", "source"], unique = true),
        Index(value = ["novelId"]),
        Index(value = ["updatedEpoch"])
    ]
)
data class TermMemory(
    /** Khoá ổn định "novelId|source" để lưu lại cùng một từ không sinh dòng mới. */
    @PrimaryKey
    val id: String,

    /** Rỗng = thuật ngữ toàn cục, áp dụng cho mọi truyện (Boston, Harvard, MIT...). */
    @ColumnInfo(defaultValue = "")
    val novelId: String = "",

    /** Nguyên văn trong bản gốc: 岑星 */
    val source: String,

    /** Cách dịch chốt: Sầm Tinh */
    val target: String,

    // ---- Dữ liệu tra cứu, lấy từ /lore/explain ----------------------------
    @ColumnInfo(defaultValue = "")
    val hanzi: String = "",
    @ColumnInfo(defaultValue = "")
    val pinyin: String = "",
    @ColumnInfo(defaultValue = "")
    val sinoVietnamese: String = "",
    @ColumnInfo(defaultValue = "")
    val definition: String = "",
    @ColumnInfo(defaultValue = "")
    val loreContext: String = "",

    /** person / place / sect / item / skill / other — chỉ để lọc và hiển thị. */
    @ColumnInfo(defaultValue = "other")
    val category: String = "other",

    /**
     * Ghim = luôn gửi kèm khi dịch dù bảng thuật ngữ đã dài.
     * Tên nhân vật chính nên ghim, danh từ phụ thì không cần.
     */
    @ColumnInfo(defaultValue = "0")
    val pinned: Boolean = false,

    /** Số lần người đọc tra lại từ này — dùng để xếp ưu tiên khi bảng quá dài. */
    @ColumnInfo(defaultValue = "0")
    val hitCount: Int = 0,

    @ColumnInfo(defaultValue = "0")
    val createdEpoch: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val updatedEpoch: Long = 0
) {
    /** Nhãn hiển thị gọn trong danh sách: "岑星 → Sầm Tinh". */
    @get:Ignore
    val displayLabel: String get() = "$source → $target"
}
