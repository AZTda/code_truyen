package com.truyencloud.shared.reader

/** Hành động màn đọc thực hiện khi nhận phím âm lượng. */
enum class ReaderKeyAction { NEXT_PAGE, PREVIOUS_PAGE, SCROLL_DOWN, SCROLL_UP }

object ReaderVolumeKeys {
    /** Chế độ cuộn: mỗi lần bấm cuộn ~90% chiều cao vùng nhìn để còn giữ lại vài dòng ngữ cảnh. */
    const val SCROLL_FRACTION = 0.9f

    /** Phím giảm âm lượng = tiến (trang sau / cuộn xuống), tăng âm lượng = lùi — cùng quy ước hầu hết ứng dụng đọc sách. */
    fun actionFor(forward: Boolean, pagedMode: Boolean): ReaderKeyAction = when {
        pagedMode && forward -> ReaderKeyAction.NEXT_PAGE
        pagedMode -> ReaderKeyAction.PREVIOUS_PAGE
        forward -> ReaderKeyAction.SCROLL_DOWN
        else -> ReaderKeyAction.SCROLL_UP
    }

    /**
     * Phím âm lượng có được dùng để lật trang lúc này không. Khi true, Activity NUỐT phím (không đổi âm lượng hệ thống) nên chỉ bật
     * khi: người dùng đã bật tuỳ chọn, không có bảng / hộp thoại nào che phía trước (cuộn cuốn sách phía sau là vô nghĩa, lại chặn
     * mất thanh âm lượng của bảng Audiobook) và đang có chữ để lật (thẻ chờ / thẻ lỗi thì để phím chỉnh âm lượng như thường).
     */
    fun pagingActive(optionOn: Boolean, overlayOpen: Boolean, hasText: Boolean): Boolean = optionOn && !overlayOpen && hasText

    /** Quãng cuộn (px, có dấu) cho một lần bấm ở chế độ cuộn. */
    fun scrollDistancePx(viewportHeightPx: Int, forward: Boolean): Float {
        val distance = viewportHeightPx.coerceAtLeast(0) * SCROLL_FRACTION
        return if (forward) distance else -distance
    }
}
