package com.truyencloud.shared.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Một truyện CÓ HÀNG trong bảng này nghĩa là truyện đang ở trong tủ sách của người đọc.
 * Mở trang chi tiết hay tìm kiếm thì KHÔNG chèn hàng — chỉ thêm vào tủ, yêu thích,
 * hoặc lưu tiến độ đọc lần đầu mới tạo hàng (xem NovelRepository / ReaderRepository).
 */
@Serializable
@Entity(tableName = "novels")
data class Novel(
    @PrimaryKey
    val id: String,
    val title: String,
    val titleVi: String = "",
    val author: String = "",
    val url: String = "",
    val coverUrl: String = "",
    val sourceId: String = "",
    val totalChapters: Int = 0,
    val description: String = "",
    val status: String = "ongoing",
    val lastReadChapter: Int = 0,
    val shelfName: String = "reading", // "reading", "completed", "planned"
    val isFavorite: Boolean = false,
    val lastReadAt: Long = 0L,
    val addedAt: Long = 0L,

    /**
     * Giờ máy (epoch ms) của lần sửa gần nhất có ý nghĩa với đồng bộ: đọc tiếp, đổi kệ,
     * bật/tắt yêu thích. Không dùng lastReadAt vì đổi kệ không được làm truyện nhảy lên
     * đầu danh sách "đọc gần đây". Máy chủ hợp nhất theo giờ này (last-write-wins).
     */
    @ColumnInfo(defaultValue = "0")
    val syncUpdatedAt: Long = 0L,

    /**
     * Có thay đổi cục bộ chưa được máy chủ xác nhận. Mặc định SQL là 1: hàng có sẵn từ
     * trước khi có cột này chưa từng được đẩy lên nên phải đẩy một lần (máy chủ vẫn từ
     * chối nếu bản của nó mới hơn). Giá trị mặc định của hàm dựng là false vì truyện mới
     * dựng từ kết quả tìm kiếm chưa thuộc tủ sách nên không có gì để đẩy.
     */
    @ColumnInfo(defaultValue = "1")
    val syncDirty: Boolean = false
) {
    /** Mốc thời gian cục bộ dùng để so với bản trên máy chủ. */
    @get:Ignore
    val localStamp: Long get() = maxOf(syncUpdatedAt, lastReadAt)
}

val Novel.displayTitle: String
    get() {
        val t = if (titleVi.isNotBlank()) titleVi else title
        return SinoVietnameseTransliteration.translateTitle(t)
    }

val Novel.displayAuthor: String
    get() {
        return SinoVietnameseTransliteration.translateAuthor(author)
    }
