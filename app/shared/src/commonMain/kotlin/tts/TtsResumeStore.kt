package com.truyencloud.shared.tts

import com.truyencloud.shared.library.HomePrefsStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Chỗ đang nghe dở của MỘT chương, lưu bền qua dừng / rời màn đọc / tắt ứng dụng / khởi động lại máy để bấm Phát là đọc
 * tiếp đúng CHỮ đã dừng. [textHash] là băm chữ của mảnh lúc lưu: bản dịch đổi (dịch lại, đổi bản Anh / Việt) thì mảnh ở chỉ
 * số đó không còn là chữ cũ, vị trí không được dùng mù.
 */
@Serializable
data class TtsResumePoint(
    val novelId: String,
    val chapterNumber: Int,
    val segmentIndex: Int,
    val charOffset: Int,
    val audioMs: Long = -1L,
    val voiceKey: String? = null,
    val textHash: Int = 0,
    val savedAtMs: Long = 0L
) {
    fun isFor(novelId: String, chapterNumber: Int) = this.novelId == novelId && this.chapterNumber == chapterNumber

    /** Cùng chỗ (bỏ qua giờ lưu): khỏi ghi lại khi vị trí không đổi. */
    fun samePlace(other: TtsResumePoint?) = other != null && other.copy(savedAtMs = savedAtMs) == this
}

/**
 * Kho vị trí nghe dở trong kho khoá-giá trị của nền tảng (KHÔNG phải Room: không đổi lược đồ). Một khoá JSON giữ tối đa
 * [maxEntries] chương gần nhất. Xoá cùng dữ liệu tài khoản ([wipe], gọi từ đường xoá dữ liệu của tài khoản).
 */
class TtsResumeStore(private val prefs: HomePrefsStore?, private val maxEntries: Int = MAX_ENTRIES) {

    fun all(): List<TtsResumePoint> {
        val raw = runCatching { prefs?.getString(KEY) }.getOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    fun load(novelId: String, chapterNumber: Int): TtsResumePoint? = all().firstOrNull { it.isFor(novelId, chapterNumber) }

    /** Lưu (thay bản cũ của cùng chương), chương mới nhất lên đầu, chỉ giữ [maxEntries] chương. */
    fun save(point: TtsResumePoint) {
        val list = listOf(point) + all().filterNot { it.isFor(point.novelId, point.chapterNumber) }
        write(list.take(maxEntries))
    }

    fun clear(novelId: String, chapterNumber: Int) {
        val list = all()
        if (list.none { it.isFor(novelId, chapterNumber) }) return
        write(list.filterNot { it.isFor(novelId, chapterNumber) })
    }

    private fun write(list: List<TtsResumePoint>) {
        runCatching {
            if (list.isEmpty()) prefs?.remove(KEY) else prefs?.putString(KEY, json.encodeToString(serializer, list))
        }
    }

    companion object {
        const val KEY = "tts_resume_positions"
        const val MAX_ENTRIES = 5
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(TtsResumePoint.serializer())

        /** Xoá mọi vị trí nghe dở (đổi tài khoản / xoá dữ liệu trên máy). */
        fun wipe(prefs: HomePrefsStore?) {
            runCatching { prefs?.remove(KEY) }
        }
    }
}

/**
 * Ghi vị trí đang nghe có tiết chế: lúc đang phát tối đa mỗi [intervalMs] một lần (vị trí đổi ~4 lần/giây), bắt buộc ghi
 * ngay khi tạm dừng / dừng / vào nền ([flush]). Vị trí chưa ghi luôn là vị trí MỚI NHẤT.
 */
class TtsResumeTracker(
    private val store: TtsResumeStore,
    private val nowMs: () -> Long,
    private val intervalMs: Long = WRITE_INTERVAL_MS
) {
    private var pending: TtsResumePoint? = null
    private var lastWritten: TtsResumePoint? = null
    private var lastWriteAtMs: Long = Long.MIN_VALUE

    fun onPosition(point: TtsResumePoint) {
        if (point.samePlace(lastWritten)) {
            pending = null
            return
        }
        pending = point
        val now = nowMs()
        if (lastWriteAtMs == Long.MIN_VALUE || now - lastWriteAtMs >= intervalMs) writePending(now)
    }

    /** Ghi ngay vị trí còn chờ (tạm dừng, dừng, rời màn đọc, ứng dụng vào nền). */
    fun flush() = writePending(nowMs())

    /** Chương nghe xong / người dùng tự chọn chỗ khác: bỏ vị trí đã lưu (và vị trí chờ ghi) của chương đó. */
    fun clear(novelId: String, chapterNumber: Int) {
        if (pending?.isFor(novelId, chapterNumber) == true) pending = null
        if (lastWritten?.isFor(novelId, chapterNumber) == true) lastWritten = null
        store.clear(novelId, chapterNumber)
    }

    private fun writePending(now: Long) {
        val p = pending ?: return
        pending = null
        store.save(p.copy(savedAtMs = now))
        lastWritten = p
        lastWriteAtMs = now
    }

    companion object {
        const val WRITE_INTERVAL_MS = 2_000L
    }
}

/** Quy đổi giữa vị trí của bộ đọc và vị trí đã lưu. Thuần, để kiểm thử. */
object TtsResumePolicy {

    fun hashOf(segmentText: String): Int = segmentText.trim().hashCode()

    /** Vị trí cần lưu cho [position] của chương (novelId, chapterNumber) đang đọc [texts]; null nếu vị trí không hợp lệ. */
    fun pointFor(novelId: String, chapterNumber: Int, texts: List<String>, position: TtsPlaybackPosition): TtsResumePoint? {
        val text = texts.getOrNull(position.segmentIndex) ?: return null
        if (novelId.isBlank()) return null
        return TtsResumePoint(
            novelId = novelId,
            chapterNumber = chapterNumber,
            segmentIndex = position.segmentIndex,
            charOffset = position.charOffset.coerceIn(0, text.trim().length),
            audioMs = position.audioMs,
            voiceKey = position.audioVoiceKey,
            textHash = hashOf(text)
        )
    }

    /**
     * Vị trí bắt đầu từ [point] cho chương đang có [texts]. Chữ của mảnh đã đổi thì tìm mảnh có ĐÚNG chữ cũ gần nhất (bản
     * dịch thêm / bớt một đoạn phía trước); không tìm thấy thì null (người gọi dùng cách chọn đoạn thường lệ).
     */
    fun restore(point: TtsResumePoint?, texts: List<String>): TtsPlaybackPosition? {
        if (point == null || texts.isEmpty()) return null
        val index = when {
            texts.getOrNull(point.segmentIndex)?.let(::hashOf) == point.textHash -> point.segmentIndex
            else -> texts.indices.filter { hashOf(texts[it]) == point.textHash }
                .minByOrNull { kotlin.math.abs(it - point.segmentIndex) } ?: return null
        }
        val length = texts[index].trim().length
        return TtsPlaybackPosition(index, point.charOffset.coerceIn(0, length), point.audioMs, point.voiceKey)
    }
}
