package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.repo.ChapterRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Mở / đóng mục lục nhanh. Danh sách chương của trình đọc là bản NHẸ (không có nội dung), nên huy hiệu "đã dịch /
 * bản tạm" của từng chương lấy từ cờ nội dung — chỉ theo dõi khi mục lục đang mở để không kéo cờ của hàng nghìn
 * chương mỗi lần bảng chương đổi (mỗi lần lưu tiến độ cuộn).
 */
internal class ReaderTocCoordinator(
    private val chapterRepository: ChapterRepository,
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope
) {
    private var flagsJob: Job? = null
    private var refreshJob: Job? = null
    private var serverFlagsJob: Job? = null

    fun toggle() { if (uiStateFlow.value.showQuickToc) close() else open() }

    fun open() {
        uiStateFlow.value = uiStateFlow.value.copy(showQuickToc = true)
        val novelId = uiStateFlow.value.novel?.id ?: return
        flagsJob?.cancel()
        flagsJob = scope.launch {
            chapterRepository.observeChapterFlags(novelId).collect { flags ->
                uiStateFlow.value = uiStateFlow.value.copy(tocFlags = flags.associateBy { it.chapterNumber })
            }
        }
        serverFlagsJob?.cancel()
        serverFlagsJob = scope.launch {
            val serverSet = chapterRepository.getServerTranslatedChapters(novelId)
            if (uiStateFlow.value.novel?.id == novelId && serverSet.isNotEmpty()) {
                uiStateFlow.value = uiStateFlow.value.copy(tocServerTranslated = serverSet)
            }
        }
        refreshList(novelId)
    }

    /**
     * Làm mới danh sách chương lúc mở mục lục: dấu "đã đọc" của chương vừa đọc xong, và danh sách đầy đủ khi lần nạp lúc
     * mở màn đọc hỏng / mới chỉ có chương đầu tiên. Danh sách nhẹ nên rẻ; chỉ thay khi thật sự khác để khỏi dựng lại
     * chỉ mục tìm kiếm của hàng nghìn chương vô ích.
     */
    private fun refreshList(novelId: String) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val list = try {
                chapterRepository.getChapters(novelId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            val s = uiStateFlow.value
            if (s.novel?.id != novelId || list.isEmpty() || list.size < s.chapters.size || list == s.chapters) return@launch
            uiStateFlow.value = s.copy(
                chapters = list,
                lastChapterNumber = maxOf(s.lastChapterNumber, list.maxOf { it.chapterNumber })
            )
        }
    }

    fun close() {
        flagsJob?.cancel()
        flagsJob = null
        refreshJob?.cancel()
        refreshJob = null
        serverFlagsJob?.cancel()
        serverFlagsJob = null
        uiStateFlow.value = uiStateFlow.value.copy(showQuickToc = false, tocFlags = emptyMap())
    }
}
