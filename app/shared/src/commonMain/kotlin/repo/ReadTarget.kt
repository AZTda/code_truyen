package com.truyencloud.shared.repo

import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.Novel

/** Chọn chương mở khi bấm "Bắt đầu đọc" / "Đọc tiếp" / "Nghe audio" ở trang chi tiết truyện. */
object ReadTarget {
    /**
     * Đã đọc tới chương X (X > 0) thì mở X — kể cả khi mục lục chưa có hàng X (máy mới vừa kéo tiến
     * độ về): dựng chương tối thiểu, trình đọc sẽ tải nội dung. Bản cũ rơi về chương ĐẦU TIÊN trong
     * trường hợp đó nên người đọc bị đưa về đầu truyện.
     */
    fun resolve(novel: Novel, chapters: List<Chapter>): Chapter? {
        val last = novel.lastReadChapter
        if (last > 0) {
            chapters.firstOrNull { it.chapterNumber == last }?.let { return it }
            return Chapter(
                id = "${novel.id}_$last",
                novelId = novel.id,
                chapterNumber = last,
                title = "Chương $last",
                titleVi = "Chương $last"
            )
        }
        return chapters.firstOrNull()
    }
}
