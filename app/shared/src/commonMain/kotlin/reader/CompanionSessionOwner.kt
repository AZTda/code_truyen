package com.truyencloud.shared.reader

/**
 * Hội thoại với trợ lý AI thuộc về MỘT truyện của MỘT tài khoản.
 *
 * Màn đọc dùng chung một ViewModel cho mọi truyện và mọi tài khoản nên bản cũ giữ nguyên hội thoại khi mở truyện khác (câu hỏi
 * kèm tên + nội dung truyện mới nhưng lịch sử là của truyện trước: AI thấy cả tiết lộ của cuốn kia) hoặc sau khi đăng xuất / đăng
 * nhập tài khoản khác. Mỗi lần mở màn đọc gọi [claim]; true nghĩa là hội thoại đang giữ đã không còn thuộc về người đọc này.
 */
class CompanionSessionOwner {
    private var novelId: String? = null
    private var accountId: String? = null

    /**
     * Ghi nhận màn đọc vừa mở cho [novelId] bởi tài khoản [accountId].
     * @return true nếu hội thoại đang giữ thuộc về truyện hoặc tài khoản KHÁC (phải bỏ trước khi dùng); lần đầu tiên luôn false.
     */
    fun claim(novelId: String, accountId: String): Boolean {
        val foreign = (this.novelId != null && this.novelId != novelId) || (this.accountId != null && this.accountId != accountId)
        this.novelId = novelId
        this.accountId = accountId
        return foreign
    }
}
