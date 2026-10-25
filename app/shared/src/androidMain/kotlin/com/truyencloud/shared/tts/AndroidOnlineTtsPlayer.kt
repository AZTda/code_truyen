package com.truyencloud.shared.tts

import android.content.Context
import android.os.Handler
import kotlinx.coroutines.CoroutineScope

/**
 * Bộ phát giọng AI theo từng mảnh, luôn tải sẵn [TtsSegmentPlan.BUFFER_AHEAD] mảnh phía trước (bỏ qua mảnh tiếng Trung:
 * giọng máy đọc). Mỗi mảnh là một [AndroidSegmentAudio] (tệp đã tải + MediaPlayer).
 *
 * Người gọi quyết định mảnh nào phát tiếp (gọi [play]); lớp này chỉ dùng lại đúng mảnh đã tải sẵn cho chỉ số đó thay vì tải
 * lại. Khoá mảnh = giọng + chữ (KHÔNG có tốc độ: trình phát áp tốc độ). Mọi sự kiện kiểm tra mảnh tương ứng còn là mảnh
 * hiện tại nên sự kiện cũ bị bỏ qua. Trong lúc phát, vị trí được báo về ~4 lần/giây ([Listener.onSegmentProgress]).
 */
internal class AndroidOnlineTtsPlayer(
    private val context: Context,
    private val mainHandler: Handler,
    private val scope: CoroutineScope,
    private val streamClient: AndroidTtsStreamClient,
    private val isSessionPlaying: () -> Boolean,
    private val listener: Listener
) {
    interface Listener {
        fun onSegmentStarted(index: Int)
        fun onSegmentCompleted(index: Int)

        /** [result]: lỗi máy chủ (503 / mạng / hết giờ) hay chỉ mảnh này không đọc được bằng giọng AI (422, lỗi phát tệp). */
        fun onSegmentFailed(index: Int, result: TtsAudioFetch)
        fun onSegmentProgress(index: Int, charOffset: Int, audioMs: Long, voiceKey: String)
    }

    private var current: AndroidSegmentAudio? = null
    private val ahead = mutableMapOf<Int, AndroidSegmentAudio>()
    private var texts: List<String> = emptyList()
    private var profileId: String = ""
    private var speed: Float = 1.0f
    private var volume: Float = 1.0f

    private val events = object : AndroidSegmentAudio.Events {
        override fun onPrepared(audio: AndroidSegmentAudio) {
            // Kiểm tra trạng thái SỐNG: người dùng có thể đã bấm dừng trong lúc đang tải
            if (audio === current && isSessionPlaying()) startCurrent()
        }

        override fun onCompleted(audio: AndroidSegmentAudio) {
            if (audio !== current) return
            releaseCurrent()
            listener.onSegmentCompleted(audio.index)
        }

        override fun onFailed(audio: AndroidSegmentAudio, result: TtsAudioFetch) {
            if (audio === current) {
                releaseCurrent()
                // Đang dừng thì không đọc thay bằng giọng máy; lúc tiếp tục sẽ thử lại
                if (isSessionPlaying()) listener.onSegmentFailed(audio.index, result)
            } else {
                // Mảnh tải sẵn hỏng: bỏ đi, tới lượt nó sẽ được tải lại
                ahead.entries.firstOrNull { it.value === audio }?.key?.let { ahead.remove(it) }
                audio.release()
            }
        }
    }

    private val progressTick = object : Runnable {
        override fun run() {
            val e = current ?: return
            if (!e.started || e.paused) return
            val ms = e.positionMs()
            if (ms >= 0) listener.onSegmentProgress(e.index, TtsTextTiming.charOffsetAt(e.text, ms, e.durationMs), ms, e.voiceId)
            mainHandler.postDelayed(this, PROGRESS_MS)
        }
    }

    val hasActiveSegment: Boolean get() = current != null

    /** Phát mảnh [index] từ [from] (đầu mảnh, hoặc đúng chỗ đang nghe dở). */
    fun play(texts: List<String>, index: Int, profileId: String, speed: Float, from: TtsPlaybackPosition) {
        this.texts = texts
        this.profileId = profileId
        this.speed = speed
        val text = texts.getOrNull(index)?.trim().orEmpty()
        val key = keyFor(text)
        val start = TtsSegmentStart.from(from, text, profileId)
        // Đang tải đúng mảnh này (bấm dừng lúc chờ rồi phát lại): dùng tiếp, không tải lại
        val existing = current?.takeIf { it.index == index && it.key == key && !it.released && !it.started }
        if (existing != null) {
            existing.start = start
            if (existing.prepared && isSessionPlaying()) startCurrent()
            return
        }
        releaseCurrent()
        val candidate = ahead.remove(index)
        val reused = candidate?.takeIf { it.key == key && !it.started }
        // Mảnh tải sẵn nhưng sai giọng / nội dung: bỏ đi, không để rò MediaPlayer
        if (candidate != null && reused == null) candidate.release()
        val entry = reused ?: createEntry(index, text, key)
        entry.start = start
        current = entry
        if (entry.prepared && isSessionPlaying()) startCurrent()
    }

    /** Tạm dừng; mảnh còn đang tải thì cứ tải tiếp (lúc phát lại không phải chờ lại từ đầu). */
    fun pause() {
        mainHandler.removeCallbacks(progressTick)
        current?.pause()
    }

    /** Tiếp tục đúng chỗ đã dừng; false nếu không còn gì để tiếp tục. */
    fun resume(): Boolean {
        val e = current ?: return false
        val ok = e.resume(speed, volume)
        if (ok) startProgress() else if (e.started) releaseCurrent()
        return ok
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        current?.setVolume(volume)
    }

    /** Đổi tốc độ ngay trên âm thanh đang nghe; true nếu có mảnh giọng AI đang nạp (không cần đọc lại). */
    fun setSpeed(value: Float): Boolean {
        speed = value
        val e = current ?: return false
        e.setSpeed(value)
        return true
    }

    fun stopCurrent() = releaseCurrent()

    /** Bỏ các mảnh đã tải sẵn (đổi giọng làm chúng không còn đúng). */
    fun invalidateBuffer() {
        ahead.values.forEach { it.release() }
        ahead.clear()
    }

    fun stopAll() {
        releaseCurrent()
        invalidateBuffer()
    }

    private fun keyFor(text: String): String = "$profileId|$text"

    private fun createEntry(index: Int, text: String, key: String): AndroidSegmentAudio =
        AndroidSegmentAudio(index, key, text, profileId, context, mainHandler, events).also {
            it.load(scope, streamClient, TtsSegmentPlan.prefetchHint(texts, index))
        }

    private fun startCurrent() {
        val e = current ?: return
        if (e.started) return
        if (!e.begin(speed, volume)) {
            events.onFailed(e, TtsAudioFetch.Unsupported(AndroidSegmentAudio.PLAYBACK_ERROR))
            return
        }
        listener.onSegmentStarted(e.index)
        startProgress()
        refillBuffer(e.index)
    }

    private fun startProgress() {
        mainHandler.removeCallbacks(progressTick)
        mainHandler.post(progressTick)
    }

    private fun refillBuffer(currentIdx: Int) {
        val wanted = TtsSegmentPlan.serverAhead(texts, currentIdx)
        val iterator = ahead.entries.iterator()
        while (iterator.hasNext()) {
            val (idx, e) = iterator.next()
            if (idx !in wanted || e.key != keyFor(texts.getOrNull(idx)?.trim().orEmpty())) {
                e.release()
                iterator.remove()
            }
        }
        for (idx in wanted) {
            if (idx in ahead) continue
            val text = texts[idx].trim()
            ahead[idx] = createEntry(idx, text, keyFor(text))
        }
    }

    private fun releaseCurrent() {
        mainHandler.removeCallbacks(progressTick)
        current?.release()
        current = null
    }

    private companion object {
        const val PROGRESS_MS = 250L
    }
}
