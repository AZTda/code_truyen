package com.truyencloud.shared.tts

/**
 * Quyết định đọc từ đoạn nào và khi nào dừng, tách khỏi giao diện để kiểm thử được.
 *
 * Lỗi cũ: tự chuyển chương lấy lại đoạn đang nghe (chỉ số mảnh) của chương trước nên chương kế
 * bắt đầu đọc từ giữa chừng; còn bấm nghe thì luôn đọc từ đầu chương thay vì chỗ đang xem.
 */
object TtsStartPolicy {

    /**
     * Người dùng chủ động bấm nghe.
     * - Phiên này đang mở ở đúng chương: tiếp tục đoạn đang dở.
     * - Còn vị trí cần khôi phục (chương vừa mở lại): dùng vị trí đó.
     * - Đã biết vị trí cuộn gần nhất (< 0 = chưa biết): đọc từ đoạn đang nhìn thấy.
     * - Không có gì: đoạn đang dở (mặc định 0).
     */
    fun manualStart(
        sameSession: Boolean,
        sessionParagraph: Int,
        restoreScroll: Float,
        visibleScroll: Float,
        paragraphCount: Int
    ): Int = when {
        sameSession -> sessionParagraph
        restoreScroll > 0f -> TtsProgressMath.paragraphForScroll(restoreScroll, paragraphCount)
        visibleScroll >= 0f -> TtsProgressMath.paragraphForScroll(visibleScroll, paragraphCount)
        else -> sessionParagraph
    }.coerceIn(0, (paragraphCount - 1).coerceAtLeast(0))

    /**
     * Chương vừa tải xong khi TTS đang bật. Tự chuyển chương ([advancing]) LUÔN đọc từ đầu; chọn từ
     * mục lục / mở từ chi tiết truyện thì đọc từ vị trí đã lưu nếu có.
     */
    fun afterChapterLoad(advancing: Boolean, restoreScroll: Float, paragraphCount: Int): Int = when {
        advancing -> 0
        restoreScroll > 0f -> TtsProgressMath.paragraphForScroll(restoreScroll, paragraphCount)
        else -> 0
    }

    /**
     * Chương kế vừa tải xong: có tự phát không? Đang tự chuyển chương ([advancing]) mà người dùng đã bấm tạm dừng (hoặc
     * hẹn giờ ngủ đã dừng) trong lúc chờ — [speakerPlaying] = false — thì KHÔNG được tự phát lại, trừ khi họ đã bấm Phát
     * trong lúc chờ ([playRequested]). Chọn từ mục lục / mở từ chi tiết truyện thì luôn phát.
     */
    fun autoPlayAfterLoad(advancing: Boolean, speakerPlaying: Boolean, playRequested: Boolean = false): Boolean =
        !advancing || speakerPlaying || playRequested

    /** Chương [current] có phải chương cuối đã biết ([knownLast] <= 0 nghĩa là chưa biết). */
    fun isLastChapter(current: Int, knownLast: Int): Boolean = knownLast > 0 && current >= knownLast
}
