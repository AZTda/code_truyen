package com.truyencloud.compose.screens.detail

import com.truyencloud.shared.api.NovelApi
import com.truyencloud.shared.data.getCurrentEpochMillis
import com.truyencloud.shared.export.PlatformShareHandler
import com.truyencloud.shared.repo.ApiErrors
import com.truyencloud.shared.repo.BatchMessages
import com.truyencloud.shared.repo.BatchPollPolicy
import com.truyencloud.shared.repo.ChapterRepository
import com.truyencloud.shared.repo.SyncStatus
import com.truyencloud.shared.repo.TranslateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tải & dịch hàng loạt chương về máy.
 *
 * - Đã đăng nhập: xin máy chủ dịch cả dải (AI), theo dõi tác vụ, xong thì tải về máy.
 * - Khách (máy chủ chặn dịch hàng loạt bằng 401): bỏ qua bước dịch trên máy chủ, chỉ tải những
 *   gì máy chủ có sẵn, kèm lời nhắc đăng nhập.
 * - 401 lúc bắt đầu hoặc lúc theo dõi, mất mạng chập chờn: xử lý có thông báo rõ ràng, KHÔNG ném lỗi
 *   ra ngoài (bản cũ chạy vòng theo dõi ngoài try/catch nên một lần mất mạng là sập ứng dụng).
 */
internal class BatchDownloadCoordinator(
    private val chapterRepository: ChapterRepository,
    private val translateRepository: TranslateRepository?,
    private val scope: CoroutineScope,
    private val isLoggedIn: () -> Boolean,
    private val update: ((NovelDetailUiState) -> NovelDetailUiState) -> Unit,
    private val novelApi: NovelApi? = null
) {
    private var job: Job? = null
    private var overnightWatchJob: Job? = null
    private var autoPullJob: Job? = null

    fun start(novelId: String, fromChapter: Int, toChapter: Int) {
        job?.cancel()
        job = scope.launch {
            try {
                run(novelId, fromChapter, toChapter)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(isBatchQueuing = false, batchProgress = null, batchMessage = "Lỗi: ${ApiErrors.describe(e)}") }
            }
        }
    }

    fun startOvernight(novelId: String, fromChapter: Int, toChapter: Int) {
        job?.cancel()
        job = scope.launch {
            update { it.copy(isBatchQueuing = true, batchMessage = null, batchProgress = null) }
            val repo = translateRepository
            if (repo == null || !isLoggedIn()) {
                update {
                    it.copy(
                        isBatchQueuing = false,
                        batchRequiresLogin = true,
                        batchProgress = null,
                        batchMessage = "Vui lòng đăng nhập để bật Dịch Ngầm trên máy chủ."
                    )
                }
                return@launch
            }
            try {
                val res = repo.batchTranslate(novelId, fromChapter, toChapter, overnight = true)
                update {
                    it.copy(
                        isBatchQueuing = false,
                        isOvernightEnabled = true,
                        batchProgress = 1f,
                        batchMessage = res.message.ifBlank {
                            "Đã bật Dịch Ngầm (Gemini Flash Medium + Qwen3-4B) từ chương $fromChapter đến $toChapter."
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(isBatchQueuing = false, batchProgress = null, batchMessage = "Lỗi: ${ApiErrors.describe(e)}") }
            }
        }
    }

    fun stopOvernight(novelId: String) {
        overnightWatchJob?.cancel()
        job?.cancel()
        job = scope.launch {
            update { it.copy(isBatchQueuing = true, batchMessage = null, batchProgress = null) }
            val repo = translateRepository
            if (repo == null || !isLoggedIn()) {
                update { it.copy(isBatchQueuing = false, batchRequiresLogin = true, batchMessage = "Vui lòng đăng nhập để điều khiển Dịch Ngầm.") }
                return@launch
            }
            try {
                val res = repo.stopOvernightTranslation(novelId)
                update {
                    it.copy(
                        isBatchQueuing = false,
                        isOvernightEnabled = false,
                        batchProgress = null,
                        batchMessage = res.message.ifBlank { "Đã tạm dừng Dịch Ngầm cho bộ truyện này." }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(isBatchQueuing = false, batchProgress = null, batchMessage = "Lỗi: ${ApiErrors.describe(e)}") }
            }
        }
    }

    /** Theo dõi trực tiếp tiến độ Dịch Ngầm trên máy chủ mỗi 15 giây khi người dùng đang mở màn hình truyện. */
    fun watchOvernight(
        novelId: String,
        enabled: Boolean,
        anchorChapter: () -> Int,
        localReady: () -> Set<Int>
    ) {
        overnightWatchJob?.cancel()
        val api = novelApi ?: return
        if (!enabled) return
        overnightWatchJob = scope.launch {
            while (true) {
                delay(15_000L)
                try {
                    val remote = api.getNovelDetail(novelId)
                    update {
                        it.copy(
                            serverTranslatedCount = remote.translated_chapters,
                            serverTranslatedChapters = remote.translated_chapter_numbers,
                            isOvernightEnabled = remote.overnight_enabled
                        )
                    }
                    autoPullMissingTranslated(
                        novelId = novelId,
                        serverTranslatedChapters = remote.translated_chapter_numbers,
                        localReady = localReady(),
                        anchorChapter = anchorChapter()
                    )
                    if (!remote.overnight_enabled) break
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
        }
    }

    /** Kéo toàn bộ các chương máy chủ đã dịch sẵn về bộ nhớ điện thoại chỉ với 1 chạm. */
    fun syncAllTranslated(novelId: String, serverTranslatedChapters: List<Int>, localReady: Set<Int>) {
        val missing = serverTranslatedChapters.filter { it !in localReady }
        if (missing.isEmpty()) {
            update { it.copy(batchProgress = 1f, batchMessage = "Toàn bộ ${serverTranslatedChapters.size} chương máy chủ đã dịch đều đã có sẵn trong máy!") }
            return
        }
        job?.cancel()
        job = scope.launch {
            update { it.copy(isBatchQueuing = true, batchProgress = 0.05f, batchMessage = "Đang kéo ${missing.size} chương đã dịch từ máy chủ về máy…") }
            try {
                val res = chapterRepository.downloadSpecificChapters(novelId, missing, requireAi = false)
                update {
                    it.copy(
                        isBatchQueuing = false,
                        batchProgress = 1f,
                        batchMessage = "Đã đồng bộ xong ${res.usable}/${missing.size} chương đã dịch về điện thoại!"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(isBatchQueuing = false, batchProgress = null, batchMessage = "Lỗi đồng bộ: ${ApiErrors.describe(e)}") }
            }
        }
    }

    /** Tự động kéo ngầm các chương máy chủ mới dịch xong khi mở truyện, ưu tiên quanh chương đang đọc trước. */
    fun autoPullMissingTranslated(
        novelId: String,
        serverTranslatedChapters: List<Int>,
        localReady: Set<Int>,
        anchorChapter: Int = 1
    ) {
        val anchor = anchorChapter.coerceAtLeast(1)
        val allMissing = serverTranslatedChapters.filter { it !in localReady }
        val missing = (allMissing.filter { it >= anchor } + allMissing.filter { it < anchor }).take(40)
        if (missing.isEmpty() || autoPullJob?.isActive == true) return
        autoPullJob = scope.launch {
            try {
                chapterRepository.downloadSpecificChapters(novelId, missing, requireAi = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    /** Đóng gói & chia sẻ sách điện tử EPUB trực tiếp trên điện thoại. */
    fun exportEpub(novelId: String) {
        val api = novelApi ?: return
        job?.cancel()
        job = scope.launch {
            update { it.copy(isBatchQueuing = true, batchProgress = null, batchMessage = "Đang đóng gói sách điện tử EPUB từ máy chủ…") }
            try {
                val (fileName, bytes) = api.downloadNovelEpub(novelId)
                PlatformShareHandler.shareBinaryFile(fileName, bytes, "application/epub+zip")
                update { it.copy(isBatchQueuing = false, batchProgress = 1f, batchMessage = "Đã tạo xong $fileName (${bytes.size / 1024} KB)!") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(isBatchQueuing = false, batchProgress = null, batchMessage = "Lỗi xuất EPUB: ${ApiErrors.describe(e)}") }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        overnightWatchJob?.cancel()
        overnightWatchJob = null
        autoPullJob?.cancel()
        autoPullJob = null
    }

    private suspend fun run(novelId: String, from: Int, to: Int) {
        update { it.copy(isBatchQueuing = true, batchMessage = null, batchProgress = null) }
        val repo = translateRepository
        if (repo == null || !isLoggedIn()) {
            update { it.copy(isBatchQueuing = false, batchRequiresLogin = true, batchProgress = 0f) }
            downloadToDevice(novelId, from, to, loggedIn = false)
            return
        }

        val response = try {
            repo.batchTranslate(novelId, from, to)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (ApiErrors.isAuthError(e)) {
                // Token hết hạn: máy chủ không cho dịch, nhưng vẫn tải được những chương có sẵn.
                SyncStatus.reportSessionExpired()
                update { it.copy(isBatchQueuing = false, batchRequiresLogin = true, batchProgress = 0f) }
                downloadToDevice(novelId, from, to, loggedIn = false)
            } else {
                update { it.copy(isBatchQueuing = false, batchProgress = null, batchMessage = "Lỗi: ${ApiErrors.describe(e)}") }
            }
            return
        }

        update {
            it.copy(
                isBatchQueuing = false,
                batchProgress = 0f,
                batchMessage = response.message.ifBlank { "Đã gửi yêu cầu dịch ngầm lên máy chủ." }
            )
        }
        if (response.job_id.isBlank()) {
            downloadToDevice(novelId, from, to, loggedIn = true)
        } else {
            waitForJob(repo, response.job_id, novelId, from, to)
        }
    }

    private suspend fun waitForJob(repo: TranslateRepository, jobId: String, novelId: String, from: Int, to: Int) {
        val startedAt = getCurrentEpochMillis()
        var failures = 0
        while (getCurrentEpochMillis() - startedAt < BatchPollPolicy.MAX_TOTAL_MS) {
            delay(BatchPollPolicy.nextDelayMs(failures))
            val status = try {
                repo.getBatchJobStatus(jobId).also { failures = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                when {
                    ApiErrors.isAuthError(e) -> {
                        SyncStatus.reportSessionExpired()
                        stopWithMessage("Lỗi: phiên đăng nhập đã hết hạn — đăng nhập lại để theo dõi tiến độ dịch.")
                        return
                    }
                    ApiErrors.isNotFound(e) -> {
                        // Máy chủ khởi động lại làm mất tác vụ (hoặc không phải tác vụ của bạn): tải phần đã có.
                        update { it.copy(batchMessage = "Không còn theo dõi được tác vụ dịch — đang tải các chương đã có về máy.") }
                        downloadToDevice(novelId, from, to, loggedIn = true)
                        return
                    }
                    BatchPollPolicy.shouldGiveUp(failures) -> {
                        stopWithMessage("Lỗi: mất kết nối khi theo dõi tiến độ dịch. Máy chủ vẫn dịch ngầm — mở lại hộp thoại để kiểm tra.")
                        return
                    }
                    else -> {
                        update { it.copy(batchMessage = "Đang chờ kết nối lại để theo dõi tiến độ dịch…") }
                        continue
                    }
                }
            }
            update {
                it.copy(
                    batchProgress = status.progress,
                    batchMessage = status.message.ifBlank { "Đang dịch ngầm trên máy chủ..." }
                )
            }
            when (status.status) {
                "succeeded", "completed" -> {
                    downloadToDevice(novelId, from, to, loggedIn = true)
                    return
                }
                "failed" -> {
                    stopWithMessage("Lỗi dịch hàng loạt: ${status.error.ifBlank { status.message }}")
                    return
                }
            }
        }
        stopWithMessage("Tác vụ dịch mất quá nhiều thời gian. Bạn có thể mở lại sau để kiểm tra.")
    }

    private fun stopWithMessage(message: String) {
        update { it.copy(batchProgress = null, batchMessage = message) }
    }

    private suspend fun downloadToDevice(novelId: String, from: Int, to: Int, loggedIn: Boolean) {
        try {
            val result = chapterRepository.downloadChaptersDetailed(novelId, from, to, requireAi = loggedIn)
            update { it.copy(batchProgress = 1f, batchMessage = BatchMessages.downloadSummary(result, loggedIn)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update {
                it.copy(
                    batchProgress = null,
                    batchMessage = "Tải về máy thất bại: ${ApiErrors.describe(e)}"
                )
            }
        }
    }
}
