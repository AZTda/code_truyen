package com.truyencloud.shared.library

/** Nội dung gợi ý cho từng trường hợp trống của Tủ sách (thuần, để kiểm thử đủ mọi kệ). */
data class LibraryEmptyCopy(val title: String, val hint: String) {
    companion object {
        /**
         * @param libraryIsEmpty cả tủ sách không có truyện nào (chưa thêm gì / đã xóa hết).
         * @param favoritesOnly đang bật lọc yêu thích nhưng kệ không có truyện yêu thích nào.
         */
        fun of(shelf: String, libraryIsEmpty: Boolean, favoritesOnly: Boolean): LibraryEmptyCopy = when {
            libraryIsEmpty -> LibraryEmptyCopy(
                "Tủ sách còn trống",
                "Thêm truyện từ mục Khám phá, tìm theo tên hoặc dán link truyện, hoặc nạp tệp TXT/EPUB của bạn."
            )
            favoritesOnly -> LibraryEmptyCopy(
                "Chưa có truyện yêu thích ở kệ này",
                "Nhấn giữ bìa truyện rồi chọn “Thêm vào yêu thích”, hoặc bỏ lọc để xem toàn bộ kệ."
            )
            LibraryShelves.normalize(shelf) == LibraryShelves.COMPLETED -> LibraryEmptyCopy(
                "Chưa có truyện hoàn thành",
                "Đọc xong một truyện? Nhấn giữ bìa truyện rồi chọn “Hoàn thành” để chuyển sang kệ này."
            )
            LibraryShelves.normalize(shelf) == LibraryShelves.PLANNED -> LibraryEmptyCopy(
                "Bộ sưu tập còn trống",
                "Nhấn giữ bìa truyện rồi chọn “Bộ sưu tập” để cất truyện định đọc sau, hoặc thêm từ mục Khám phá."
            )
            else -> LibraryEmptyCopy(
                "Chưa có truyện nào đang đọc",
                "Bắt đầu đọc một truyện từ mục Khám phá hoặc chuyển truyện từ kệ khác sang đây."
            )
        }
    }
}
