package com.truyencloud.shared.notify

/** Một thông báo chương mới đã sẵn sàng để hiển thị. */
data class NewChapterNotice(
    val novelId: String,
    val novelTitle: String,
    val newChapters: Int,
    val totalChapters: Int
)

/**
 * Gửi thông báo của hệ điều hành.
 *
 * Toàn bộ ứng dụng trước đây không có một dòng mã thông báo nào, nên người đọc chỉ
 * biết truyện có chương mới khi tự mở từng truyện ra bấm kiểm tra.
 */
interface Notifier {
    /** Hệ điều hành đã cho phép hiện thông báo chưa. */
    val hasPermission: Boolean

    /** Xin quyền nếu chưa có. Trả về trạng thái sau khi xin. */
    suspend fun requestPermission(): Boolean

    /**
     * Hiện một thông báo gộp cho các truyện có chương mới.
     *
     * Gộp chứ không mỗi truyện một thông báo: tủ sách mười truyện cùng ra chương
     * sẽ đổ mười thông báo liên tiếp và người đọc tắt hết cho nhanh.
     */
    fun notifyNewChapters(notices: List<NewChapterNotice>)
}

/** Bản không làm gì — dùng cho kiểm thử và cho nền tảng chưa hỗ trợ. */
class NoOpNotifier : Notifier {
    override val hasPermission: Boolean = false
    override suspend fun requestPermission(): Boolean = false
    override fun notifyNewChapters(notices: List<NewChapterNotice>) {}
}

expect fun createNotifier(): Notifier

/**
 * Bật/tắt việc kiểm tra chương mới khi ứng dụng KHÔNG mở.
 *
 * Android dùng WorkManager chạy theo chu kỳ. iOS không cho chạy nền theo chu kỳ
 * tuỳ ý nên bản iOS không làm gì — việc kiểm tra diễn ra lúc mở lại ứng dụng.
 */
expect fun applyBackgroundUpdateChecks(enabled: Boolean)

/** Mô tả cách nền tảng này kiểm tra, để nói thật với người dùng trong Cài đặt. */
expect fun backgroundUpdateCheckDescription(): String
