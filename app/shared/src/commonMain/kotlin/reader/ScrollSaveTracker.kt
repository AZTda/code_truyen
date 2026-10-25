package com.truyencloud.shared.reader

import com.truyencloud.shared.data.Novel

/** Vị trí cuộn (0..1) người đọc đã tới trong MỘT chương của một truyện. */
data class ScrollReport(val novel: Novel, val chapterNumber: Int, val position: Float) {
    fun isSameChapterAs(other: ScrollReport): Boolean = novel.id == other.novel.id && chapterNumber == other.chapterNumber
}

/**
 * Sổ vị trí cuộn chờ lưu của trình đọc. Tiến độ được lưu giãn nhịp (vài giây sau lần cuộn cuối) nên luôn có một vị trí "chưa lưu".
 *
 * Bản cũ chỉ có MỘT lịch lưu cho cả trình đọc: sang chương khác trong lúc còn một lần lưu đang chờ thì lần báo đầu tiên của chương
 * mới huỷ lịch đó, và vị trí cuối cùng của chương vừa rời (vd. 0.95 ở cuối chương) mất hẳn — mở lại chương bằng Trước / Mục lục thì
 * rơi về vị trí cũ hơn. Giờ mỗi lần chuyển chương, vị trí chưa lưu của chương cũ được trả về để lưu NGAY.
 */
class ScrollSaveTracker {
    private var latest: ScrollReport? = null
    private var unsaved = false

    /**
     * Ghi nhận [report] là vị trí mới nhất.
     * @return vị trí CHƯA LƯU của một chương KHÁC vừa bị nó thay thế — nơi gọi phải lưu ngay kẻo mất; null nếu không có.
     */
    fun report(report: ScrollReport): ScrollReport? {
        val previous = latest
        val displaced = previous?.takeIf { unsaved && !it.isSameChapterAs(report) }
        latest = report
        unsaved = true
        return displaced
    }

    /** Lấy vị trí chưa lưu (hết giãn nhịp, hoặc thoát màn đọc) và coi như đã lưu; null nếu không có gì đang chờ. */
    fun takeUnsaved(): ScrollReport? {
        val report = latest
        if (!unsaved || report == null) return null
        unsaved = false
        return report
    }

    /** Vị trí gần nhất đã báo cho chương này (đã lưu hay chưa); null nếu người đọc chưa cuộn trong chương này. */
    fun positionFor(novelId: String, chapterNumber: Int): Float? =
        latest?.takeIf { it.novel.id == novelId && it.chapterNumber == chapterNumber }?.position
}

object ReadingProgressPolicy {
    /**
     * Vị trí cần lưu cho chương đang mở khi người đọc rời màn đọc / ra nền; null = không lưu gì cho chương này.
     *
     * @param contentShown chữ của chương đã hiện lên màn hình. Chưa thì chương đang giữ có thể chỉ là bản giữ chỗ (Home dựng khi bấm
     *   "Đọc tiếp": scrollPosition = 0, chương chưa tải về máy này): lưu nó sẽ ghi 0 đè lên vị trí đã lưu — và vị trí 0 đó còn được đẩy
     *   lên đám mây, xoá luôn chỗ đọc trên các máy khác.
     * @param reported vị trí người đọc đã cuộn tới trong chương này; null nếu chưa cuộn lần nào
     * @param restored vị trí đã khôi phục của chương (chưa cuộn lần nào thì lưu lại đúng vị trí đó)
     */
    fun positionOnLeave(contentShown: Boolean, reported: Float?, restored: Float): Float? =
        if (!contentShown) null else reported ?: restored
}
