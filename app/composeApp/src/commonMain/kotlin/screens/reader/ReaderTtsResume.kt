package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.library.defaultHomePrefs
import com.truyencloud.shared.tts.TtsPlaybackPosition
import com.truyencloud.shared.tts.TtsResumePolicy
import com.truyencloud.shared.tts.TtsResumeStore
import com.truyencloud.shared.tts.TtsResumeTracker
import com.truyencloud.shared.tts.TtsSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * Nghe tiếp ĐÚNG CHỮ đã dừng qua dừng hẳn / rời màn đọc / vào nền / tắt ứng dụng: theo dõi vị trí của bộ đọc
 * ([TtsSpeaker.playbackPosition]: mảnh + ký tự + ms âm thanh của giọng AI) và lưu theo (truyện, chương) vào kho cài đặt —
 * tiết chế ~2 giây một lần lúc đang phát, ghi ngay khi tạm dừng / dừng / vào nền ([flush]).
 *
 * Bỏ vị trí đã lưu khi chương nghe xong hoặc người dùng tự chọn chỗ khác (chạm đoạn, lùi / tới đoạn, bản chữ đổi giữa
 * chừng). Vị trí của phiên đang mở trong cùng lần chạy do chính bộ đọc giữ (tạm dừng rồi tiếp tục tại chỗ).
 */
internal class ReaderTtsResume(
    private val ttsSpeaker: TtsSpeaker,
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope,
    private val store: TtsResumeStore = TtsResumeStore(defaultHomePrefs())
) {
    private val tracker = TtsResumeTracker(store, nowMs = { clockOrigin.elapsedNow().inWholeMilliseconds })

    private var novelId: String? = null
    private var chapterNumber = 0
    private var texts: List<String> = emptyList()

    fun start() {
        scope.launch {
            // Vị trí chữ (cuộn / tô sáng) đọc thẳng từ TtsSpeaker.playbackPosition: chép vào ReaderUiState ~4 lần/giây
            // sẽ bắt cả màn đọc vẽ lại liên tục
            ttsSpeaker.playbackPosition.collect(::record)
        }
    }

    /** Phiên đọc mới bắt đầu cho chương này với [texts] (đúng danh sách mảnh đưa cho bộ đọc). */
    fun bind(novelId: String?, chapterNumber: Int, texts: List<String>) {
        if (this.novelId != novelId || this.chapterNumber != chapterNumber) tracker.flush()
        this.novelId = novelId
        this.chapterNumber = chapterNumber
        this.texts = texts
    }

    /** Vị trí đã lưu của chương này, còn khớp chữ hiện tại (null nếu không có / chữ đã đổi hẳn). */
    fun savedStart(novelId: String?, chapterNumber: Int, texts: List<String>): TtsPlaybackPosition? {
        val id = novelId ?: return null
        return TtsResumePolicy.restore(store.load(id, chapterNumber), texts)?.takeUnless { it.atSegmentStart && it.segmentIndex == 0 }
    }

    fun flush() = tracker.flush()

    /** Bỏ vị trí đã lưu của chương đang nghe (nghe xong / người dùng tự chọn chỗ khác). */
    fun clearCurrent() {
        val id = novelId ?: return
        tracker.clear(id, chapterNumber)
    }

    private fun record(position: TtsPlaybackPosition) {
        val id = novelId ?: return
        if (!uiStateFlow.value.isTtsActive || texts.isEmpty()) return
        // Đầu chương thì không có gì để "nghe tiếp": khỏi chiếm chỗ của chương khác trong kho (giữ vài chương gần nhất)
        if (position.segmentIndex == 0 && position.atSegmentStart) return
        TtsResumePolicy.pointFor(id, chapterNumber, texts, position)?.let(tracker::onPosition)
    }

    private companion object {
        val clockOrigin = TimeSource.Monotonic.markNow()
    }
}
