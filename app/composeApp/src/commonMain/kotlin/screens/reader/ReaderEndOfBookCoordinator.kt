package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.api.FriendlyErrors
import com.truyencloud.shared.api.NovelApi
import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.reader.ChapterNavigation
import com.truyencloud.shared.repo.ChapterRepository
import com.truyencloud.shared.repo.ChapterUpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Thẻ "Bạn đã đọc hết": kiểm tra chương mới (làm mới mục lục rồi mở lại nút Chương sau nếu có chương mới)
 * và bật thông báo chương mới.
 */
internal class ReaderEndOfBookCoordinator(
    private val chapterRepository: ChapterRepository,
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope,
    private val updateChecker: ChapterUpdateChecker?,
    /** Mục lục dài thêm: bộ tải trước phải quên điểm cuối đã dò. */
    private val onTocExtended: (novelId: String) -> Unit
) {
    private val novelApi by lazy { NovelApi() }

    private var state: ReaderUiState
        get() = uiStateFlow.value
        set(value) { uiStateFlow.value = value }

    init {
        if (updateChecker != null) {
            state = state.copy(newChapterAlertsAvailable = true, newChapterAlertsEnabled = updateChecker.isEnabled)
            scope.launch { updateChecker.enabledFlow.collect { state = state.copy(newChapterAlertsEnabled = it) } }
        }
    }

    fun dismiss() {
        state = state.copy(showEndOfBook = false, newChaptersNotice = null)
    }

    fun checkForNewChapters() {
        val novel = state.novel ?: return
        if (state.isCheckingNewChapters) return
        state = state.copy(isCheckingNewChapters = true, newChaptersNotice = null)
        scope.launch {
            try {
                val before = state.lastChapterNumber
                // Nhờ máy chủ quét lại nguồn (có thể chậm hoặc lỗi — vẫn lấy mục lục hiện có sau đó)
                try { novelApi.checkUpdates(novel.id) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                val detail = novelApi.getNovelDetail(novel.id, novel.url)
                chapterRepository.saveChapters(
                    detail.chapters.map { ch ->
                        Chapter(
                            id = "${novel.id}_${ch.chapter_number}",
                            novelId = novel.id,
                            chapterNumber = ch.chapter_number,
                            title = ch.title,
                            titleVi = ch.title_vi.orEmpty(),
                            url = ch.url.orEmpty()
                        )
                    }
                )
                val list = chapterRepository.getChapters(novel.id)
                val dbLast = chapterRepository.getLastChapterNumber(novel.id)
                val last = ChapterNavigation.lastKnown(before, dbLast, detail.total_chapters, list.maxOfOrNull { it.chapterNumber } ?: 0)
                val added = ChapterNavigation.newChaptersAdded(before, last)
                if (added > 0) onTocExtended(novel.id)
                // Người đọc đã sang truyện khác trong lúc chờ: kết quả này không phải của truyện đang mở
                if (state.novel?.id != novel.id) {
                    state = state.copy(isCheckingNewChapters = false)
                    return@launch
                }
                state = state.copy(
                    isCheckingNewChapters = false,
                    chapters = list,
                    lastChapterNumber = last,
                    novel = state.novel?.copy(totalChapters = maxOf(state.novel?.totalChapters ?: 0, detail.total_chapters)),
                    newChaptersNotice = if (added > 0) {
                        "Có $added chương mới! Bấm \"Chương tiếp theo\" để đọc tiếp."
                    } else {
                        "Chưa có chương mới. Bật thông báo để được báo ngay khi truyện cập nhật."
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(
                    isCheckingNewChapters = false,
                    newChaptersNotice = if (state.novel?.id == novel.id) FriendlyErrors.of(e) else state.newChaptersNotice
                )
            }
        }
    }

    fun enableAlerts() {
        val checker = updateChecker ?: return
        scope.launch {
            val enabled = try { checker.enableWithPermission() } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            state = state.copy(
                newChaptersNotice = if (enabled) {
                    "Đã bật thông báo chương mới."
                } else {
                    "Chưa được cấp quyền thông báo. Hãy cho phép thông báo trong Cài đặt hệ thống rồi thử lại."
                }
            )
        }
    }
}
