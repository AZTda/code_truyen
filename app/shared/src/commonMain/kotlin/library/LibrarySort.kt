package com.truyencloud.shared.library

import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.displayTitle
import com.truyencloud.shared.text.VietnameseText

/**
 * Cách sắp xếp tủ sách. [key] được lưu vào cài đặt nên KHÔNG đổi giá trị đã phát hành.
 */
enum class LibrarySort(val key: String, val label: String) {
    RECENT("recent", "Đọc gần đây"),
    ADDED("added", "Mới thêm"),
    TITLE("title", "Tên A-Z"),
    UNREAD("unread", "Chưa đọc nhiều nhất");

    /**
     * Sắp xếp ổn định. Hòa thì rơi về tên rồi id để thứ tự không nhảy lung tung giữa các lần nạp lại
     * (Room phát lại danh sách sau mỗi lần đọc/đổi kệ).
     */
    fun sort(novels: List<Novel>): List<Novel> {
        if (novels.size < 2) return novels
        // Tên hiển thị qua bảng phiên âm Hán-Việt: tính một lần cho mỗi truyện thay vì mỗi lần so sánh.
        val titles = HashMap<String, String>(novels.size * 2)
        for (n in novels) titles[n.id] = VietnameseText.fold(n.displayTitle)
        val byTitle = Comparator<Novel> { a, b -> titles.getValue(a.id).compareTo(titles.getValue(b.id)) }
        val byId = compareBy<Novel> { it.id }

        val primary: Comparator<Novel> = when (this) {
            RECENT -> compareByDescending<Novel> { it.lastReadAt }.thenByDescending { it.addedAt }
            ADDED -> compareByDescending<Novel> { it.addedAt }.thenByDescending { it.lastReadAt }
            TITLE -> byTitle
            UNREAD -> compareByDescending<Novel> { LibraryListLogic.unreadChapters(it) }
                .thenByDescending { it.lastReadAt }
        }
        return novels.sortedWith(primary.then(byTitle).then(byId))
    }

    companion object {
        val DEFAULT: LibrarySort = RECENT

        fun fromKey(key: String?): LibrarySort = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
