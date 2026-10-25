package com.truyencloud.shared.reader

/** Chỗ cần nhảy tới sau khi chương đích tải xong (nhảy từ dấu trang sang chương khác). */
data class PendingJump(val novelId: String, val chapterNumber: Int, val paragraphIndex: Int)

/** Những gì cần biết về màn đọc để quyết định đã áp dụng được [PendingJump] chưa. */
data class JumpView(
    /** Truyện đang mở trong màn đọc. */
    val novelId: String?,
    /** Chương đang hiển thị (chưa chắc là chương đích: chương đích có thể đang tải). */
    val shownChapter: Int?,
    val paragraphCount: Int,
    val isLoadingChapter: Boolean,
    /** Chương đang được tải (khác [shownChapter] khi bấm sang chương khác). */
    val loadingTarget: Int?,
    /** Chương đang hiển thị đang được dịch (chưa có chữ). */
    val isTranslatingShown: Boolean,
    /** Vị trí khôi phục ban đầu của chương CHƯA được danh sách cuộn áp dụng. */
    val restorePending: Boolean
)

/**
 * Nhảy từ dấu trang sang chương khác: bản cũ gọi nhảy ngay sau khi yêu cầu tải chương — trước khi đoạn văn của chương mới tồn tại —
 * nên vị trí bị bỏ hoặc bị lần khôi phục vị trí đã lưu của chính chương đó ghi đè.
 *
 * Giờ chỗ cần nhảy được ghi trong [PendingJump] và chỉ áp dụng khi đoạn văn của chương đích ĐÃ được công bố:
 *  - Đường chính ([onChapterReady]): bộ tải chương báo "đã công bố" ngay sau khi công bố, vị trí nhảy ghi đè vị trí đã lưu trong
 *    cùng một lượt (không nháy qua vị trí cũ, TTS đang bật bắt đầu đúng đoạn đó).
 *  - Dự phòng ([step]): đợi trạng thái màn đọc cho tới khi đoạn văn đã có và vị trí khôi phục ban đầu đã được danh sách áp dụng
 *    xong (nếu còn một giá trị khôi phục chờ áp dụng thì đặt thêm một giá trị nữa có thể bị lần "đã khôi phục xong" xoá mất).
 * Chương đích tải hỏng / dịch hỏng / người đọc sang chương khác thì bỏ, tránh nhảy nhầm vào một chương về sau.
 */
object BookmarkJump {
    sealed interface Step {
        /** Chưa đủ điều kiện, đợi trạng thái màn đọc đổi tiếp. */
        data object Wait : Step

        /** Không còn áp dụng được nữa: xoá chỗ cần nhảy. */
        data object Drop : Step

        /** Đặt vị trí cuộn [position] (luôn > 0) rồi xoá chỗ cần nhảy. */
        data class Apply(val position: Float) : Step
    }

    /**
     * Đã ở đúng chương đích và có chữ để nhảy ngay (không cần tải lại)? Cờ "đang tải" ở đây chỉ có thể là việc tải một chương KHÁC
     * (tải lại chính chương đang hiện không bật cờ đó) — người gọi huỷ lượt tải ấy rồi nhảy luôn, không gọi tải lại chương này vì
     * lượt tải sau sẽ ghi đè vị trí vừa đặt bằng vị trí đã lưu.
     */
    fun canJumpNow(targetChapter: Int, shownChapter: Int?, paragraphCount: Int): Boolean =
        targetChapter == shownChapter && paragraphCount > 0

    /**
     * Chương [number] của truyện [novelId] vừa được công bố (hoặc báo lỗi dịch / không có chữ: [paragraphCount] = 0). Gọi ĐỒNG BỘ
     * ngay sau lúc công bố, trước khi TTS bắt đầu và trước khung hình kế tiếp: vị trí nhảy ghi đè vị trí đã lưu của chương trong cùng
     * một lần cập nhật nên không có khung hình nào hiện chương ở vị trí cũ rồi mới nhảy, và TTS (đang bật) bắt đầu đúng đoạn đó.
     *
     * @return null nếu sự kiện thuộc về chương khác (chưa phải chương đích — vẫn chờ).
     */
    fun onChapterReady(jump: PendingJump, novelId: String, number: Int, paragraphCount: Int): Step? {
        if (jump.novelId != novelId || jump.chapterNumber != number) return null
        return if (paragraphCount > 0) {
            Step.Apply(ReaderScrollRestore.positionForParagraph(jump.paragraphIndex, paragraphCount))
        } else {
            Step.Drop
        }
    }

    fun step(jump: PendingJump, view: JumpView): Step {
        if (view.novelId != jump.novelId) return Step.Drop
        if (view.shownChapter == jump.chapterNumber) {
            if (view.paragraphCount > 0) {
                if (view.isLoadingChapter || view.restorePending) return Step.Wait
                return Step.Apply(ReaderScrollRestore.positionForParagraph(jump.paragraphIndex, view.paragraphCount))
            }
            return if (view.isLoadingChapter || view.isTranslatingShown) Step.Wait else Step.Drop
        }
        return if (view.isLoadingChapter && view.loadingTarget == jump.chapterNumber) Step.Wait else Step.Drop
    }
}
