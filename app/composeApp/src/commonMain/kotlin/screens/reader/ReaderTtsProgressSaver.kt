package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.repo.ReaderRepository
import com.truyencloud.shared.tts.TtsProgressMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Lưu vị trí đang NGHE (chương + đoạn) qua cùng đường lưu tiến độ mà trình đọc dùng
 * (`ReaderRepository.saveReadingProgress`).
 *
 * Trước đây tiến độ chỉ được lưu từ thao tác cuộn trên màn hình: tắt màn hình nghe cả giờ
 * thì mở lại vẫn ở chỗ cũ. Mỗi lần lưu chụp sẵn truyện/chương/số đoạn lúc LÊN LỊCH, để
 * khi chương đã chuyển sang chương sau thì vị trí cũ không bị ghi nhầm vào chương mới.
 */
internal class ReaderTtsProgressSaver(
    private val readerRepository: ReaderRepository?,
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope
) {
    private class Snapshot(val novel: Novel, val chapterNumber: Int, val scroll: Float)

    private var latest: Snapshot? = null
    private var job: Job? = null

    fun onParagraphChanged(paragraphIndex: Int) {
        if (readerRepository == null) return
        val st = uiStateFlow.value
        val novel = st.novel ?: return
        val chapter = st.currentChapter ?: return
        // Đếm theo bản đang đọc (Anh / song ngữ / xen kẽ có số đoạn khác bản Việt): tỉ lệ này mới đúng vị trí trong chương
        val count = st.ttsSource().size
        if (count == 0) return
        latest = Snapshot(novel, chapter.chapterNumber, TtsProgressMath.scrollForParagraph(paragraphIndex, count))
        // Đã có một lần lưu đang chờ: nó sẽ lấy giá trị mới nhất, nên mỗi ~6 giây chỉ ghi một lần
        if (job?.isActive == true) return
        job = scope.launch {
            delay(SAVE_DELAY_MS)
            job = null
            saveLatest()
        }
    }

    /** Lưu ngay (khi tạm dừng / đóng / dừng vì lỗi). */
    fun flush() {
        job?.cancel()
        job = null
        scope.launch { saveLatest() }
    }

    private suspend fun saveLatest() {
        val repo = readerRepository ?: return
        val snap = latest ?: return
        latest = null
        try {
            repo.saveReadingProgress(snap.novel, snap.novel.id, snap.chapterNumber, snap.scroll)
        } catch (_: Exception) {
            // Lưu tiến độ thất bại không được làm sập phiên đọc
        }
    }

    private companion object {
        const val SAVE_DELAY_MS = 6_000L
    }
}
