package com.truyencloud.shared.reader

import com.truyencloud.shared.data.ENGINE_GOOGLE_FALLBACK

/**
 * Bản dịch TẠM: văn bản do Google dịch dự phòng khi AI lỗi. Máy chủ lưu tạm và tự thử AI lại ở lần yêu cầu
 * sau (cách nhau ~10 phút mỗi chương), nên trình đọc vừa báo cho người đọc vừa thử nâng cấp trong nền.
 */
object ProvisionalTranslation {
    /** Bản đang xem là tạm khi phản hồi báo dự phòng, hoặc engine của chương đã lưu là dự phòng Google. */
    fun isProvisional(isFallback: Boolean, engine: String?): Boolean =
        isFallback || engine.equals(ENGINE_GOOGLE_FALLBACK, ignoreCase = true)

    const val BANNER_TEXT = "Bản dịch máy tạm"
    const val UPGRADE_ACTION = "Dịch lại bằng AI"

    /**
     * Lời nhắn của banner bản dịch tạm. Khách chưa đăng nhập không dùng được AI (máy chủ từ chối mọi yêu cầu dịch với khách) nên
     * không được nói "AI đang bận" và cũng không có nút [UPGRADE_ACTION] — chỉ nhắc đăng nhập để có bản AI.
     */
    fun bannerMessage(isLoggedIn: Boolean): String =
        if (isLoggedIn) "$BANNER_TEXT — AI đang bận nên tạm dùng bản dịch của Google."
        else "$BANNER_TEXT của Google — đăng nhập để dùng bản dịch AI."

    /** Kết quả của lượt yêu cầu nâng cấp nền nên xử lý thế nào. */
    enum class UpgradeOutcome {
        /** Có bản AI thật và vẫn đang ở đúng chương: thay nội dung tại chỗ, giữ vị trí cuộn. */
        SWAP_IN_PLACE,

        /** Vẫn là bản tạm: giữ nguyên, để banner tiếp tục hiện. */
        STILL_PROVISIONAL,

        /** Đã sang chương khác / đang nghe TTS / đang xem bản gốc: bản AI đã lưu trong máy, lần mở sau sẽ dùng. */
        KEEP_CURRENT_VIEW
    }

    fun outcomeOf(
        resultIsFallback: Boolean,
        stillOnSameChapter: Boolean,
        ttsActive: Boolean,
        showingOriginal: Boolean
    ): UpgradeOutcome = when {
        resultIsFallback -> UpgradeOutcome.STILL_PROVISIONAL
        // Đổi nội dung giữa lúc đang đọc thành tiếng sẽ làm bộ đọc lệch đoạn — để lần mở sau.
        !stillOnSameChapter || ttsActive || showingOriginal -> UpgradeOutcome.KEEP_CURRENT_VIEW
        else -> UpgradeOutcome.SWAP_IN_PLACE
    }
}

/** Mỗi chương chỉ tự xin nâng cấp bản tạm MỘT lần trong một phiên (máy chủ đã có hẹn giờ 10 phút riêng). */
class ProvisionalRetryPolicy {
    private val attempted = HashSet<String>()

    /**
     * @return true nếu nên gửi lượt xin AI nền cho chương này NGAY BÂY GIỜ (và ghi nhớ là đã thử).
     * Khách chưa đăng nhập không dịch được nên không bao giờ thử.
     */
    fun tryBegin(novelId: String, chapterNumber: Int, isProvisional: Boolean, isLoggedIn: Boolean): Boolean {
        if (!isProvisional || !isLoggedIn) return false
        return attempted.add(ChapterNavigation.chapterKey(novelId, chapterNumber))
    }

    fun reset() = attempted.clear()
}
