package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.reader.AiErrors
import com.truyencloud.shared.reader.RecapOutcome
import com.truyencloud.shared.reader.RecapPolicy
import com.truyencloud.shared.repo.ChapterRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * "Hồi tưởng trước khi đọc". Bản cũ khi gọi máy chủ lỗi thì tự bịa hai gạch đầu dòng "Tiếp nối mạch truyện hấp dẫn..." và
 * hiện như bản tóm tắt thật; máy chủ nay gắn cờ kết quả dự phòng nên ở đây chỉ có ba kết cục: bản tóm tắt thật, "không có
 * gì để nhắc lại" (chương đầu / chưa có chương trước) hoặc lỗi kèm nút thử lại.
 */
internal class ReaderRecapCoordinator(
    private val chapterRepository: ChapterRepository,
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope
) {
    private var job: Job? = null

    /** Mở bảng và nạp bản tóm tắt của chương đang đọc (cũng là việc của nút "Thử lại"). */
    fun open() {
        val s = uiStateFlow.value
        val novel = s.novel ?: return
        val chapter = s.currentChapter ?: return
        job?.cancel()
        val opened = s.copy(
            showChapterRecap = true,
            chapterRecapData = null,
            isLoadingRecap = false,
            recapError = null,
            recapNothingToRecap = false
        )
        when {
            // Chương đầu không có chương trước: khỏi hỏi máy chủ (nó chỉ trả nội dung tĩnh gắn cờ dự phòng)
            !RecapPolicy.needsRequest(chapter.chapterNumber) -> uiStateFlow.value = opened.copy(recapNothingToRecap = true)
            // Máy chủ chỉ tóm tắt cho tài khoản đã đăng nhập: khỏi gọi mạng chỉ để nhận 401
            TruyenCloudApi.authToken.isBlank() -> uiStateFlow.value = opened.copy(recapError = AiErrors.MSG_LOGIN_REQUIRED)
            else -> {
                uiStateFlow.value = opened.copy(isLoadingRecap = true)
                load(novel.id, chapter.chapterNumber, s.chapterKey)
            }
        }
    }

    fun close() {
        job?.cancel()
        uiStateFlow.value = uiStateFlow.value.copy(showChapterRecap = false, isLoadingRecap = false)
    }

    private fun load(novelId: String, number: Int, key: String?) {
        job = scope.launch {
            try {
                val recap = chapterRepository.getChapterRecap(novelId, number)
                if (stale(key)) return@launch
                val s = uiStateFlow.value
                uiStateFlow.value = when (val outcome = RecapPolicy.outcome(number, recap)) {
                    is RecapOutcome.Ready -> s.copy(isLoadingRecap = false, chapterRecapData = outcome.recap)
                    RecapOutcome.NothingToRecap -> s.copy(isLoadingRecap = false, recapNothingToRecap = true)
                    is RecapOutcome.Failed -> s.copy(isLoadingRecap = false, recapError = outcome.reason)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (stale(key)) return@launch
                uiStateFlow.value = uiStateFlow.value.copy(isLoadingRecap = false, recapError = AiErrors.message(e))
            }
        }
    }

    /** Người đọc đã sang chương khác trong lúc chờ: kết quả của chương cũ không được ghi vào màn mới. */
    private fun stale(key: String?): Boolean = uiStateFlow.value.chapterKey != key
}
