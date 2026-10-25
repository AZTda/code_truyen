package com.truyencloud.shared.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "bookmarks",
    indices = [
        Index(value = ["novelId", "chapterNumber"]),
        Index(value = ["createdAt"])
    ]
)
data class Bookmark(
    @PrimaryKey
    val id: String = "",
    val novelId: String,
    val chapterNumber: Int,
    val chapterTitle: String = "",
    val paragraphIndex: Int = 0,
    val selectedText: String,
    val note: String = "",
    val colorHex: String = "#FFD54F", // Gold default, Cyan (#4DD0E1), Rose (#F06292), Emerald (#81C784)
    val createdAt: Long = 0L,

    /**
     * Giờ sửa gần nhất (epoch ms) — máy chủ hợp nhất theo giờ này, bản mới hơn thắng.
     * Hàng có từ trước có updatedAt = 0 và được nâng lên bằng createdAt khi đồng bộ.
     */
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0L,

    /**
     * Mốc xoá mềm (epoch ms, > 0 = đã xoá). Dòng đã xoá được GIỮ LẠI làm "bia mộ" cho đến
     * khi máy chủ xác nhận — xoá cứng thì máy kia đẩy bản cũ lên và bookmark hiện lại.
     * Mọi truy vấn hiển thị phải lọc `deletedAt = 0`.
     */
    @ColumnInfo(defaultValue = "0")
    val deletedAt: Long = 0L,

    /** Có thay đổi chưa được máy chủ xác nhận. Hàng có từ trước mặc định "chưa đẩy" để đẩy một lần. */
    @ColumnInfo(defaultValue = "1")
    val syncDirty: Boolean = true
) {
    @get:Ignore
    val isDeleted: Boolean get() = deletedAt > 0L

    /** Mốc dùng để so sánh với bản trên máy chủ: lần sửa cuối, nếu chưa có thì lúc tạo. */
    @get:Ignore
    val effectiveUpdatedAt: Long get() = maxOf(updatedAt, deletedAt, createdAt)
}
