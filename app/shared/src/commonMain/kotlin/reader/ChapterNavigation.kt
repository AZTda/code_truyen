package com.truyencloud.shared.reader

/** Giới hạn chương kế/trước của trình đọc và tình trạng "đã đọc hết truyện". */
object ChapterNavigation {

    /** Chương cuối đã biết: lớn nhất trong mọi nguồn (mục lục cục bộ, tổng chương của truyện, danh sách đang mở). */
    fun lastKnown(vararg candidates: Int): Int = (candidates.maxOrNull() ?: 0).coerceAtLeast(0)

    /** Chương [current] là chương cuối (chỉ khi biết chương cuối; 0 = chưa biết thì chưa kết luận được). */
    fun isLast(current: Int, lastKnown: Int): Boolean = lastKnown > 0 && current >= lastKnown

    /** Chương kế tiếp, hoặc null nếu đang ở chương cuối đã biết. Chưa biết chương cuối thì cho dò chương kế. */
    fun nextTarget(current: Int, lastKnown: Int): Int? =
        if (current < 1 || isLast(current, lastKnown)) null else current + 1

    fun prevTarget(current: Int): Int? = if (current > 1) current - 1 else null

    /** Số chương mới xuất hiện sau khi làm mới mục lục. */
    fun newChaptersAdded(before: Int, after: Int): Int = (after - before).coerceAtLeast(0)

    fun endOfBookMessage(lastKnown: Int): String = "Bạn đã đọc hết (hiện có $lastKnown chương)"

    /** Chỉ tính "hoàn thành thêm một chương" khi chương kế đã tải xong thật sự. */
    fun shouldCountCompletion(requested: Boolean, loadSucceeded: Boolean): Boolean = requested && loadSucceeded

    /**
     * Người đọc đã đọc xong chương vừa rời chưa: nghe hết ([finishedReading]) hoặc đã cuộn tới thẻ hết chương / trang cuối
     * ([endReachedKey] là khoá của chính chương đó). Chỉ khi đó mới đánh dấu "đã đọc" VÀ tính một chương hoàn thành vào thống kê;
     * bấm Sau mà chưa đọc gì thì cả hai đều không (bản cũ vẫn ghi một chương hoàn thành cho mỗi lần bấm).
     */
    fun hasFinishedReading(finishedReading: Boolean, endReachedKey: String?, chapterKey: String?): Boolean =
        finishedReading || (endReachedKey != null && endReachedKey == chapterKey)

    /**
     * Màn hình đang hiển thị đúng chương [chapterNumber] của truyện [novelId] không. Một việc nền (dịch lại, cào lại...) gắn với MỘT
     * chương của MỘT truyện: truyện nào cũng mở ở chương 1 nên chỉ so số chương là chưa đủ — kết quả chậm của chương 1 truyện A sẽ
     * đè lên chương 1 truyện B vừa mở.
     */
    fun isShown(shownNovelId: String?, shownChapter: Int?, novelId: String, chapterNumber: Int): Boolean =
        shownNovelId == novelId && shownChapter == chapterNumber

    /** Khoá duy nhất của một chương trong một truyện — dùng để gắn trạng thái danh sách/trang với đúng chương. */
    fun chapterKey(novelId: String, chapterNumber: Int): String = "${novelId}_$chapterNumber"
}
