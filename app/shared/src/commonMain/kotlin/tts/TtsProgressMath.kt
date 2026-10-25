package com.truyencloud.shared.tts

/**
 * Quy đổi giữa "đoạn đang nghe" và vị trí cuộn đã lưu của chương.
 *
 * Cùng công thức với ReaderContent khi LƯU vị trí: danh sách có 1 mục tiêu đề ở đầu và
 * 1 mục cuối chương (N đoạn -> N+2 mục), vị trí = mục đầu tiên nhìn thấy / (N+1); đoạn i
 * là mục i+1. Ở chế độ phân trang đây chỉ là xấp xỉ theo tỉ lệ chương.
 */
object TtsProgressMath {

    fun scrollForParagraph(paragraphIndex: Int, paragraphCount: Int): Float {
        if (paragraphCount <= 0) return 0f
        val item = paragraphIndex.coerceIn(0, paragraphCount - 1) + 1
        return (item.toFloat() / (paragraphCount + 1).toFloat()).coerceIn(0f, 1f)
    }

    fun paragraphForScroll(scroll: Float, paragraphCount: Int): Int {
        if (paragraphCount <= 0 || scroll <= 0f) return 0
        val item = kotlin.math.round(scroll.coerceIn(0f, 1f) * (paragraphCount + 1)).toInt()
        return (item - 1).coerceIn(0, paragraphCount - 1)
    }

    /**
     * Đoạn tương ứng ở bản có [newCount] đoạn khi đang nghe đoạn [index] của bản có [oldCount] đoạn (cùng vị trí theo
     * tỉ lệ chương). Dùng khi đổi bản Việt / Anh / song ngữ giữa chừng; hai bản cùng số đoạn thì giữ nguyên chỉ số.
     */
    fun equivalentParagraph(index: Int, oldCount: Int, newCount: Int): Int {
        if (newCount <= 0 || oldCount <= 0) return 0
        val old = index.coerceIn(0, oldCount - 1)
        if (oldCount == newCount) return old
        return (old.toLong() * newCount / oldCount).toInt().coerceIn(0, newCount - 1)
    }

    /** Nhãn "Đoạn x/y" theo ĐOẠN HIỂN THỊ (không phải mảnh TTS) để khớp thanh điều khiển. */
    fun paragraphLabel(segmentIndex: Int, segmentToParagraph: List<Int>, totalParagraphs: Int, segmentCount: Int): String {
        if (segmentToParagraph.isNotEmpty() && totalParagraphs > 0) {
            val paragraph = segmentToParagraph.getOrNull(segmentIndex) ?: segmentToParagraph.last()
            return "Đoạn ${paragraph + 1}/$totalParagraphs"
        }
        return if (segmentCount > 0) "Đoạn ${segmentIndex + 1}/$segmentCount" else ""
    }
}

/** Mốc tốc độ dùng chung cho Audiobook Studio và nút đổi tốc độ trên thanh TTS. */
object TtsSpeedPresets {
    val PRESETS: List<Float> = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    /** Mốc kế tiếp (vòng lại mốc đầu); tốc độ lẻ thì nhảy lên mốc lớn hơn gần nhất. */
    fun next(current: Float): Float {
        val idx = PRESETS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
        if (idx >= 0) return PRESETS[(idx + 1) % PRESETS.size]
        return PRESETS.firstOrNull { it > current } ?: PRESETS.first()
    }

    fun label(speed: Float): String {
        val hundredths = kotlin.math.round(speed * 100).toInt()
        val whole = hundredths / 100
        val frac = hundredths % 100
        return when {
            frac == 0 -> "$whole.0x"
            frac % 10 == 0 -> "$whole.${frac / 10}x"
            else -> "$whole.${frac.toString().padStart(2, '0')}x"
        }
    }
}
