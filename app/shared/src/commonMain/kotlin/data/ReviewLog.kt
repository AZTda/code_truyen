package com.truyencloud.shared.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Nhật ký từng lần ôn tập.
 *
 * Vì sao bắt buộc phải có: không có bảng này thì không tính được tỉ lệ nhớ thật,
 * không vẽ được biểu đồ tiến bộ, không tính được chuỗi ngày học, và sau này
 * KHÔNG THỂ hiệu chỉnh trọng số FSRS cho riêng người học vì không có dữ liệu lịch sử.
 * Ghi từ bây giờ để 6 tháng nữa đã có đủ dữ liệu.
 *
 * Khoá tự nhiên của một lượt ôn là (wordId, reviewedAt): `id` tự tăng chỉ có nghĩa trên
 * MỘT thiết bị (hai máy cùng sinh id = 1, 2, 3...) nên máy chủ và bước gộp dữ liệu đều
 * bỏ qua `id` và khử trùng theo cặp này.
 */
@Serializable
@Entity(
    tableName = "review_logs",
    indices = [
        Index(value = ["wordId"]),
        Index(value = ["reviewedAt"]),
        // KHÔNG unique: dữ liệu cũ có thể đã chứa bản trùng (đồng bộ cũ nhân đôi log mỗi lượt)
        // và AutoMigration sẽ vỡ nếu tạo unique index lên chúng. Khử trùng bằng mã (insertIfAbsent).
        Index(value = ["wordId", "reviewedAt"])
    ]
)
data class ReviewLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val wordId: String,
    val word: String = "",
    val reviewedAt: Long,
    /** 1 = Quên, 2 = Khó, 3 = Tốt, 4 = Dễ. */
    val rating: Int,
    /** Chế độ ôn: "recognition" | "cloze" | "typing" | "listening" | "collocation" | "pronunciation" | "quiz" | "cram". */
    val reviewMode: String = "recognition",
    /** Thời gian phản hồi — dự báo độ nhớ tốt hơn cả điểm tự chấm. */
    val responseMs: Long = 0L,
    val elapsedDays: Double = 0.0,
    val scheduledDays: Double = 0.0,
    val stabilityBefore: Double = 0.0,
    val stabilityAfter: Double = 0.0,
    val difficultyBefore: Double = 0.0,
    val difficultyAfter: Double = 0.0,
    val retrievabilityBefore: Double = 0.0,
    /** Ngày YYYY-MM-DD theo giờ địa phương — để nhóm thống kê và tính chuỗi ngày. */
    val dateKey: String = "",
    /**
     * true = lượt luyện tập KHÔNG đổi lịch FSRS (thẻ chưa đến hạn, đã ôn hôm nay, ôn nhồi, luyện phát âm).
     * Loại khỏi tỉ lệ nhớ, hạn mức ngày và đếm thẻ mới; vẫn tính là hoạt động cho chuỗi ngày.
     */
    @ColumnInfo(defaultValue = "0")
    val isPractice: Boolean = false,
    /** Trạng thái FSRS của thẻ TRƯỚC lượt ôn (0 mới, 1 đang học, 2 ôn tập, 3 học lại); -1 = log cũ, không rõ. */
    @ColumnInfo(defaultValue = "-1")
    val stateBefore: Int = -1,
    /** Đã đẩy lên máy chủ chưa. Log kéo về từ máy chủ được ghi với true. */
    @ColumnInfo(defaultValue = "0")
    val isSynced: Boolean = false
)
