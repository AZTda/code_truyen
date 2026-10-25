package com.truyencloud.shared.reader

/**
 * Khi nào một khoảng thời gian được tính là "đang đọc".
 *
 * Bản cũ chỉ kiểm tra màn đọc còn mở, nên để máy tắt màn hình / ra màn hình chính vẫn cộng phút đọc.
 * Nay phải vừa ở màn đọc vừa đang ở nền trước; riêng đang nghe TTS (phát thật sự) thì tính là đọc kể cả khi tắt màn hình.
 */
object ReadingTimerPolicy {
    /** Nhịp kiểm tra (giây): ngắn để vài giây ở nền trước không bị làm tròn thành cả phút. */
    const val SLICE_SECONDS = 5

    fun shouldCount(hasContent: Boolean, readerVisible: Boolean, appForeground: Boolean, ttsPlaying: Boolean): Boolean =
        hasContent && ((readerVisible && appForeground) || ttsPlaying)
}

/** Gom các nhịp [ReadingTimerPolicy.SLICE_SECONDS] đủ tính thành từng phút nguyên để ghi sổ. */
class ReadingTimeAccumulator {
    private var seconds = 0

    /** Cộng [sliceSeconds] giây; trả số phút nguyên cần ghi sổ ngay lúc này (thường 0 hoặc 1). */
    fun add(sliceSeconds: Int): Int {
        if (sliceSeconds <= 0) return 0
        seconds += sliceSeconds
        val minutes = seconds / 60
        seconds %= 60
        return minutes
    }

    val pendingSeconds: Int get() = seconds
}
