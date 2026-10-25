package com.truyencloud.shared.reader

/**
 * Quy đổi giữa vị trí đọc đã lưu (tỉ lệ 0..1) và chỉ số mục trong danh sách cuộn.
 *
 * Bản cũ lưu `firstVisibleIndex / (totalItems - 1)` nhưng khôi phục bằng `ratio * paragraphs` (lệch một mục),
 * nên mỗi lần mở lại chương lùi dần một đoạn; và chỉ khôi phục khi vị trí > 0 nên chương chưa đọc (0) mở
 * ở đúng độ sâu của chương trước. Ở đây lưu và khôi phục dùng CHUNG một công thức.
 */
object ReaderScrollRestore {

    sealed interface Decision {
        /** Không làm gì (chưa có nội dung, hoặc đã khôi phục rồi). */
        data object None : Decision

        /** Cuộn tới mục [index] (0 = đầu chương). */
        data class ScrollTo(val index: Int) : Decision
    }

    /**
     * @param savedPosition vị trí cần khôi phục (0 = không có vị trí đã lưu).
     * @param itemCount tổng số mục của danh sách (tiêu đề + đoạn + thẻ hết chương); <= 0 nghĩa là chưa có nội dung.
     * @param alreadyRestored chương này đã được đặt vị trí ban đầu rồi (chỉ mở đầu chương MỘT lần).
     */
    fun decide(savedPosition: Float, itemCount: Int, alreadyRestored: Boolean): Decision {
        if (itemCount <= 0) return Decision.None
        if (savedPosition > 0f) return Decision.ScrollTo(indexFor(savedPosition, itemCount))
        // Chương chưa đọc (vị trí 0) phải mở ở đầu chương, không được giữ độ sâu cuộn của chương trước.
        return if (alreadyRestored) Decision.None else Decision.ScrollTo(0)
    }

    /** Số mục của danh sách cuộn: 1 tiêu đề + [paragraphCount] đoạn + 1 thẻ hết chương. */
    fun listItemCount(paragraphCount: Int): Int = paragraphCount.coerceAtLeast(0) + 2

    fun indexFor(position: Float, itemCount: Int): Int {
        if (itemCount <= 1) return 0
        val p = position.coerceIn(0f, 1f)
        return (p * (itemCount - 1) + 0.5f).toInt().coerceIn(0, itemCount - 1)
    }

    fun positionFor(index: Int, itemCount: Int): Float {
        if (itemCount <= 1) return 0f
        return (index.toFloat() / (itemCount - 1)).coerceIn(0f, 1f)
    }

    /** Vị trí nhỏ nhất vẫn > 0: đủ để hiệu ứng khôi phục chạy (0 nghĩa là "không có vị trí cần đặt") mà vẫn quy về mục / trang đầu. */
    const val MIN_PLACE = 0.001f

    /** Vị trí (luôn > 0) để nhảy tới đoạn [paragraphIndex] của danh sách có [paragraphCount] đoạn. */
    fun positionForParagraph(paragraphIndex: Int, paragraphCount: Int): Float {
        if (paragraphCount <= 0) return 0f
        val index = paragraphIndex.coerceIn(0, paragraphCount - 1) + 1 // mục 0 là tiêu đề
        return positionFor(index, listItemCount(paragraphCount)).coerceAtLeast(MIN_PLACE)
    }

    /**
     * Vị trí cần đặt lại khi chữ của chương bị thay TẠI CHỖ (dịch lại, nâng cấp bản tạm, đổi gốc/dịch). Số đoạn giữ nguyên
     * thì danh sách tự giữ chỗ (khoá mục theo chỉ số) nên không cần đặt gì (0f); số đoạn đổi thì chỉ số cũ không còn trỏ
     * đúng chỗ, phải quy theo tỉ lệ đã cuộn — luôn > 0 kể cả khi đang ở đầu chương.
     */
    fun placeAfterReplace(oldCount: Int, newCount: Int, scrollFraction: Float): Float =
        if (oldCount == newCount) 0f else scrollFraction.coerceIn(0f, 1f).coerceAtLeast(MIN_PLACE)

    /** Trang cần mở trong chế độ lật trang cho vị trí đã lưu. */
    fun pageFor(position: Float, pageCount: Int): Int = indexFor(position, pageCount)

    /**
     * Có được báo tiến độ cuộn về cho ViewModel không: chỉ khi danh sách đang hiển thị ĐÚNG là chương hiện tại
     * và vị trí ban đầu của chương đó đã được đặt. Nếu không, lần phát đầu tiên sau khi đổi chương mang độ sâu
     * của chương trước và bị lưu nhầm làm tiến độ của chương mới.
     */
    fun shouldReport(settledKey: String?, currentKey: String?): Boolean =
        settledKey != null && settledKey == currentKey
}
