package com.truyencloud.shared.reader

import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.text.VietnameseText

/** Một dòng mục lục kèm chuỗi tìm kiếm đã bỏ dấu — dựng MỘT lần cho cả danh sách 5000 chương. */
class TocEntry(val chapter: Chapter, internal val searchText: String)

sealed interface TocJump {
    /** Ô nhập trống hoặc không phải số. */
    data object Invalid : TocJump

    /** Số chương vượt quá chương cuối đã biết. */
    data class OutOfRange(val lastChapter: Int) : TocJump

    data class Go(val chapterNumber: Int) : TocJump
}

/** Lọc / sắp xếp / nhảy số cho mục lục nhanh của trình đọc (danh sách KHÔNG chứa nội dung chương). */
object ReaderToc {

    /** Mỗi số chương chỉ một dòng (danh sách lỗi có số chương trùng sẽ làm sập LazyColumn vì trùng khoá mục). */
    fun index(chapters: List<Chapter>): List<TocEntry> = chapters.distinctBy { it.chapterNumber }.map { ch ->
        TocEntry(ch, VietnameseText.fold("${ch.chapterNumber} ${ch.titleVi} ${ch.title}"))
    }

    /**
     * @param query từ khoá tìm trong tên chương (không phân biệt hoa thường / dấu); toàn chữ số thì khớp cả số chương.
     * @param descending true = chương mới nhất lên đầu.
     */
    fun filter(entries: List<TocEntry>, query: String, descending: Boolean): List<Chapter> {
        val needle = VietnameseText.fold(query.trim())
        val matched = if (needle.isEmpty()) {
            entries.map { it.chapter }
        } else if (needle.all { it.isDigit() }) {
            entries.filter { e -> e.chapter.chapterNumber.toString().startsWith(needle) || needle in e.searchText }
                .map { it.chapter }
        } else {
            entries.filter { needle in it.searchText }.map { it.chapter }
        }
        return if (descending) matched.asReversed() else matched
    }

    /** Đọc ô "nhảy tới chương số": [lastChapter] <= 0 nghĩa là chưa biết chương cuối (không chặn trên). */
    fun parseJump(input: String, lastChapter: Int): TocJump {
        val n = input.trim().toIntOrNull() ?: return TocJump.Invalid
        if (n < 1) return TocJump.Invalid
        if (lastChapter > 0 && n > lastChapter) return TocJump.OutOfRange(lastChapter)
        return TocJump.Go(n)
    }

    /** Vị trí của chương trong danh sách đang hiển thị (-1 nếu không có mặt). */
    fun positionOf(list: List<Chapter>, chapterNumber: Int): Int = list.indexOfFirst { it.chapterNumber == chapterNumber }
}
