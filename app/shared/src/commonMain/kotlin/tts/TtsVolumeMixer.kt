package com.truyencloud.shared.tts

/**
 * Hai hệ số âm lượng của phiên đọc: [fade] (hẹn giờ ngủ giảm dần) và [duck] (ứng dụng khác nói chen
 * ngang nên vặn nhỏ). Mọi thay đổi được đẩy xuống bộ phát qua [apply].
 *
 * Bộ phát (nhất là Android) tự NHỚ mức âm lượng cuối cùng được đặt cho phiên sau. Vì vậy hệ số về 1.0
 * mà không đẩy xuống bộ phát thì phiên kế tiếp vẫn phát ở mức 25% (lỗi cũ: dừng / mất quyền phát khi
 * đang bị nhường tiếng).
 */
internal class TtsVolumeMixer(private val apply: (fade: Float, duck: Float) -> Unit) {
    var fade = 1.0f
        private set
    var duck = 1.0f
        private set

    fun setFade(value: Float) {
        fade = value
        apply(fade, duck)
    }

    fun setDucked(ducked: Boolean) {
        val target = if (ducked) DUCK_FACTOR else 1.0f
        if (duck == target) return
        duck = target
        apply(fade, duck)
    }

    /** Dừng hẳn: cả hai hệ số về 1.0 và bộ phát nhận ngay mức đầy. */
    fun reset() {
        if (fade == 1.0f && duck == 1.0f) return
        fade = 1.0f
        duck = 1.0f
        apply(fade, duck)
    }

    companion object {
        const val DUCK_FACTOR = 0.25f
    }
}
