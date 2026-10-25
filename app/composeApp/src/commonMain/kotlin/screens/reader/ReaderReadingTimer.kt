package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.reader.ReadingTimeAccumulator
import com.truyencloud.shared.reader.ReadingTimerPolicy
import com.truyencloud.shared.repo.LifecycleSyncManager
import com.truyencloud.shared.repo.ReaderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Đếm phút đọc. Chạy theo nhịp ngắn ([ReadingTimerPolicy.SLICE_SECONDS] giây) và chỉ cộng những nhịp đang thật sự đọc:
 * màn đọc đang mở VÀ ứng dụng ở nền trước (tắt màn hình / ra màn hình chính thì không tính), hoặc đang nghe TTS phát thật sự.
 * Bản cũ chỉ kiểm tra "màn đọc còn mở" mỗi 60 giây nên để máy khoá màn hình vẫn cộng phút.
 */
internal class ReaderReadingTimer(
    private val readerRepository: ReaderRepository,
    private val uiState: StateFlow<ReaderUiState>,
    private val scope: CoroutineScope
) {
    fun start() {
        val accumulator = ReadingTimeAccumulator()
        scope.launch {
            while (isActive) {
                delay(ReadingTimerPolicy.SLICE_SECONDS * 1_000L)
                val s = uiState.value
                val hasContent = s.currentChapter != null && (s.paragraphs.isNotEmpty() || s.englishParagraphs.isNotEmpty())
                val counting = ReadingTimerPolicy.shouldCount(
                    hasContent = hasContent,
                    readerVisible = s.isReaderVisible,
                    appForeground = LifecycleSyncManager.isForeground.value,
                    ttsPlaying = s.ttsIsPlaying
                )
                if (!counting) continue
                val minutes = accumulator.add(ReadingTimerPolicy.SLICE_SECONDS)
                if (minutes > 0) readerRepository.recordReadingSession(minutes = minutes, chaptersCompleted = 0)
            }
        }
    }
}
