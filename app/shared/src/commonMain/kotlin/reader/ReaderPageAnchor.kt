package com.truyencloud.shared.reader

/** Đoạn văn đầu và cuối (chỉ số trong danh sách đoạn) nằm trên một trang của chế độ lật trang; trang trống thì cả hai là -1. */
data class PageSpan(val first: Int, val last: Int)

/**
 * Giữ đúng chỗ đang đọc của chế độ lật trang khi chương được CHIA TRANG LẠI (đổi cỡ chữ, lề, giãn dòng, xoay màn hình): chỉ số
 * trang cũ không còn trỏ đúng chỗ nên phải neo theo CHỈ SỐ ĐOẠN VĂN.
 *
 * Bản cũ có hai hiệu ứng cùng khoá theo danh sách trang: hiệu ứng thứ nhất ghi "đoạn đang đọc" từ (trang hiện tại, danh sách trang),
 * hiệu ứng thứ hai dùng nó để tìm trang mới. Khi chia lại, hiệu ứng thứ nhất chạy TRƯỚC với danh sách trang MỚI nhưng chỉ số trang
 * vẫn là của danh sách cũ, nên ghi đè neo bằng đoạn đầu của trang cùng số trong cách chia mới (đang ở đoạn 30, tăng cỡ chữ từ 40 lên
 * 60 trang thì neo thành ~20); hiệu ứng thứ hai rồi "neo lại" vào đúng trang đang đứng = không cuộn gì, người đọc bị kéo lùi.
 *
 * Ở đây neo CHỈ đổi khi người đọc tự đổi trang trên một cách chia trang; chia lại, kể cả việc pager tự kẹp trang hay lần cuộn neo lại
 * của chính nó, không bao giờ đổi neo — nên nhiều lần đổi cỡ chữ liên tiếp cũng không làm chỗ đọc trôi dần.
 *
 * Cách dùng (xem ReaderPaginatedView): gọi [observe] MỖI LẦN dựng giao diện — ngay lúc dựng, trước khi pager đo lại, `currentPage`
 * còn là chỉ số của cách chia CŨ — rồi cuộn tới trang nó trả về (nếu có) và báo [landed].
 */
class ReaderPageAnchor {
    /** Đoạn văn người đọc đang đọc (đoạn đầu của trang người đọc lật tới gần nhất). */
    var paragraph: Int = 0
        private set

    private var started = false

    /** Cách chia trang hiện tại. Hai cách chia có cùng đoạn đầu / cuối ở mọi trang là MỘT (khỏi cuộn lại khi chia lại mà không đổi gì). */
    private var spans: List<PageSpan> = emptyList()
    private var seenPage = -1
    private var plan: Int? = null
    private var settling = false

    /**
     * Thuần và lặp lại được: cùng đầu vào cho cùng kết quả (dựng lại giao diện nhiều lần với cùng một cách chia trang không đổi gì).
     *
     * @param spans đoạn văn đầu / cuối của từng trang theo cách chia hiện tại
     * @param currentPage trang pager đang hiển thị
     * @param canReanchor chương đã đặt xong vị trí ban đầu và không còn lần khôi phục vị trí nào đang chờ (chưa thì chưa neo lại)
     * @return trang cần cuộn tới để người đọc vẫn ở đúng đoạn văn sau khi chia lại; null nếu không cần cuộn gì
     */
    fun observe(spans: List<PageSpan>, currentPage: Int, canReanchor: Boolean): Int? {
        if (!started || spans != this.spans) {
            // Người đọc có thể vừa lật trang cùng lượt với việc chia lại: ghi nốt theo cách chia CŨ (chỉ số trang còn là của nó)
            capture(this.spans, currentPage)
            val firstTime = !started
            started = true
            this.spans = spans
            plan = null
            settling = false
            if (!firstTime && canReanchor && paragraph > 0 && spans.isNotEmpty()) {
                val target = pageOf(spans, paragraph)
                if (target != currentPage) {
                    plan = target
                    settling = true
                }
            }
        }
        capture(spans, currentPage)
        return plan
    }

    /**
     * Pager đã cuộn xong theo kế hoạch của [observe] cho cách chia [spans]; [page] là trang nó đang đứng. Từ đây lật trang của người
     * đọc lại được ghi nhận; lần dừng ở trang neo không được coi là lựa chọn của người đọc nên neo giữ nguyên (không trôi về đầu trang).
     */
    fun landed(spans: List<PageSpan>, page: Int) {
        if (spans != this.spans) return // đã chia lại thêm một lần nữa trong lúc cuộn: kế hoạch này đã cũ
        seenPage = page
        settling = false
        plan = null
    }

    private fun capture(spans: List<PageSpan>, page: Int) {
        if (settling || page == seenPage) return
        val span = spans.getOrNull(page) ?: return
        seenPage = page
        if (span.first >= 0) paragraph = span.first
    }

    /** Trang đầu tiên còn chữ từ [paragraph] trở đi (đoạn dài tràn qua nhiều trang thì là trang mở đầu nó); hết chữ thì trang cuối. */
    private fun pageOf(spans: List<PageSpan>, paragraph: Int): Int =
        spans.indexOfFirst { it.last >= paragraph }.takeIf { it >= 0 } ?: spans.lastIndex
}
