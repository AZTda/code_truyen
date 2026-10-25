package com.truyencloud.shared.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Kết quả tải một dải chương về máy, phân theo độ sẵn sàng đọc offline. */
data class OfflineDownloadResult(
    val total: Int,
    /** Có bản dịch dùng được (và là bản AI nếu đang đòi hỏi). */
    val ready: Int,
    /** Chỉ có bản dịch TẠM của Google (người dùng đã đăng nhập nên lẽ ra phải có bản AI). */
    val provisional: Int,
    /** Chỉ có văn bản gốc: mất mạng sẽ hiện tiếng Trung. */
    val originalOnly: Int,
    /** Không tải được gì. */
    val failed: Int
) {
    /** Số chương đọc offline được bằng tiếng Việt (sẵn sàng + bản tạm). */
    val usable: Int get() = ready + provisional
}

/**
 * Tải hàng loạt chương về máy. Tách khỏi [ChapterRepository] cho gọn; chỉ dùng API công khai của nó.
 */
internal class ChapterOfflineDownloader(private val repo: ChapterRepository) {
    suspend fun download(novelId: String, from: Int, to: Int, requireAi: Boolean): OfflineDownloadResult =
        downloadList(novelId, (from..to).toList(), requireAi)

    suspend fun downloadList(novelId: String, numbers: List<Int>, requireAi: Boolean): OfflineDownloadResult =
        coroutineScope {
            if (numbers.isEmpty()) return@coroutineScope OfflineDownloadResult(0, 0, 0, 0, 0)
            // Tải song song có giới hạn (5 chương cùng lúc) thay vì tuần tự từng chương
            val sem = Semaphore(MAX_PARALLEL)
            val states = numbers.map { num ->
                async { sem.withPermit { stateAfterDownload(novelId, num, requireAi) } }
            }.awaitAll()
            OfflineDownloadResult(
                total = states.size,
                ready = states.count { it == ChapterDownloadState.READY },
                provisional = states.count { it == ChapterDownloadState.PROVISIONAL },
                originalOnly = states.count { it == ChapterDownloadState.ORIGINAL_ONLY },
                failed = states.count { it == ChapterDownloadState.MISSING }
            )
        }

    private suspend fun stateAfterDownload(novelId: String, num: Int, requireAi: Boolean): ChapterDownloadState {
        val cached = repo.getChapterFromCache(novelId, num)
        val before = ChapterOfflinePolicy.classify(cached, requireAi)
        if (!ChapterOfflinePolicy.needsFetch(before)) return before
        return try {
            // forceRefresh: chương chỉ có bản gốc / bản tạm PHẢI hỏi lại máy chủ — đường
            // không ép buộc trả nguyên bản đã lưu nếu nó có nội dung.
            val fetched = repo.getChapter(novelId, num, forceRefresh = true)
            ChapterOfflinePolicy.classify(fetched, requireAi)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Không nâng cấp được: giữ trạng thái hiện có (chương chỉ-bản-gốc vẫn đọc được bản gốc)
            before
        }
    }

    private companion object {
        const val MAX_PARALLEL = 5
    }
}
