package com.truyencloud.shared.library

/**
 * Các kệ của tủ sách. `id` KHÔNG được đổi: máy chủ và các máy khác lưu đúng chuỗi này trong
 * `shelf_name`. Kệ "planned" hiển thị là "Bộ sưu tập" (truyện dự định đọc sau) để khớp nhãn tab ở
 * Tủ sách và nhãn "Trong tủ • ..." ở trang chi tiết; thêm một id kệ thứ tư sẽ làm truyện biến mất
 * khỏi giao diện của các máy/phiên bản chưa biết id đó.
 */
object LibraryShelves {
    const val READING = "reading"
    const val COMPLETED = "completed"
    const val PLANNED = "planned"

    data class Shelf(val id: String, val label: String, val hint: String)

    val all: List<Shelf> = listOf(
        Shelf(READING, "Đang đọc", "Truyện bạn đang theo dõi"),
        Shelf(COMPLETED, "Hoàn thành", "Truyện đã đọc xong"),
        Shelf(PLANNED, "Bộ sưu tập", "Dự định đọc sau")
    )

    private val ids: Set<String> = all.map { it.id }.toSet()

    /**
     * Kệ lạ (dữ liệu từ phiên bản khác, hoặc "removed" sót lại) xếp vào "đang đọc" để không có truyện
     * nào vô hình trong tủ sách.
     */
    fun normalize(shelfName: String): String = if (shelfName in ids) shelfName else READING

    fun label(shelfName: String): String = all.first { it.id == normalize(shelfName) }.label
}
