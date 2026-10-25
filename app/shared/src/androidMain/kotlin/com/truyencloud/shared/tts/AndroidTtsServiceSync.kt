package com.truyencloud.shared.tts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Đồng bộ trạng thái phiên đọc sang thông báo + MediaSession (qua [TtsServiceBridge]):
 * tiêu đề, "Đoạn x/y" theo đoạn hiển thị, nhãn hẹn giờ và ảnh bìa.
 */
internal class AndroidTtsServiceSync(
    private val context: Context,
    private val scope: CoroutineScope,
    private val isBackgroundEnabled: () -> Boolean
) {
    private var segmentToParagraph: List<Int> = emptyList()
    private var totalParagraphs: Int = 0
    private var artworkUrl: String? = null
    private var artworkJob: Job? = null
    private var labelTicks = 0

    fun setSegmentMap(map: List<Int>, total: Int) {
        segmentToParagraph = map
        totalParagraphs = total
    }

    fun setMetadata(title: String, subtitle: String, isPlaying: Boolean) {
        TtsServiceBridge.novelTitle = title
        TtsServiceBridge.chapterTitle = subtitle
        if (isPlaying) refresh()
    }

    fun setSpeed(speed: Float) {
        TtsServiceBridge.playbackSpeed = speed
    }

    /**
     * Cập nhật trạng thái phát + số đoạn; [segmentIndex] là chỉ số mảnh TTS. [deviceVoice] = người dùng chọn giọng AI
     * nhưng đang nghe giọng MÁY (giọng AI vừa lỗi): ghi rõ ở thông báo / màn hình khoá để không tưởng là chọn sai giọng.
     */
    fun sync(playing: Boolean, segmentIndex: Int, segmentCount: Int, deviceVoice: Boolean = false) {
        val map = segmentToParagraph.takeIf { it.size == segmentCount }.orEmpty()
        val base = TtsProgressMath.paragraphLabel(segmentIndex, map, totalParagraphs, segmentCount)
        val label = if (deviceVoice && base.isNotBlank()) "$base · giọng máy" else base
        val number = if (map.isNotEmpty()) (map.getOrNull(segmentIndex) ?: map.last()) + 1 else segmentIndex + 1
        val total = if (map.isNotEmpty()) totalParagraphs else segmentCount
        TtsServiceBridge.sync(playing, label, number, total)
        refresh()
    }

    fun showTransition() {
        TtsServiceBridge.paragraphInfo = "Đang chuyển chương tiếp theo..."
        refresh()
    }

    /** Nhãn đổi mỗi giây; chỉ làm mới thông báo mỗi ~5 giây hoặc khi bật/tắt/đổi loại hẹn giờ. */
    fun setSleepLabel(label: String) {
        val old = TtsServiceBridge.sleepTimerInfo
        TtsServiceBridge.sleepTimerInfo = label
        labelTicks++
        val kindChanged = label.isBlank() != old.isBlank() || label.take(1) != old.take(1)
        if (kindChanged || labelTicks % 5 == 0) refresh()
    }

    fun start() {
        if (isBackgroundEnabled()) TtsServiceBridge.startService(context)
    }

    fun stop() = TtsServiceBridge.stopService(context)

    fun refresh() {
        if (isBackgroundEnabled()) TtsServiceBridge.updateService(context)
    }

    fun setArtworkUrl(url: String?) {
        if (url == artworkUrl) return
        artworkUrl = url
        artworkJob?.cancel()
        if (url.isNullOrBlank()) {
            TtsServiceBridge.artwork = null
            refresh()
            return
        }
        artworkJob = scope.launch {
            val bitmap = withContext(Dispatchers.IO) { downloadScaled(url) }
            if (artworkUrl == url) {
                TtsServiceBridge.artwork = bitmap
                refresh()
            }
        }
    }

    private fun downloadScaled(url: String): Bitmap? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 8000
        }
        val raw = conn.inputStream.use { BitmapFactory.decodeStream(it) }
        conn.disconnect()
        raw?.let { bmp ->
            // Ảnh quá lớn vượt giới hạn binder của MediaSession
            val scale = (MAX_ART_PX.toFloat() / maxOf(bmp.width, bmp.height)).coerceAtMost(1f)
            if (scale >= 1f) bmp
            else Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MAX_ART_PX = 512
    }
}
