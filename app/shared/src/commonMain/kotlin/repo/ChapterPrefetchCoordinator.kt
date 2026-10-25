package com.truyencloud.shared.repo

import com.truyencloud.shared.api.TruyenCloudApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Điều phối tải trước thông minh (Smart Offline Prefetch) các chương tiếp theo.
 *
 * Khi độc giả đang đọc chương N:
 * 1. Tải ngầm văn bản gốc N+1..N+5 vào Room SQLite (chặn ở chương cuối đã biết).
 * 2. Nếu đã đăng nhập và có [translateRepository]: xin dịch trước N+1 (rồi N+2) ở làn NỀN
 *    của máy chủ, để chuyển chương không phải chờ dịch và offline không gặp chương tiếng Trung.
 *
 * Mọi việc chạy nền, tuần tự, nuốt lỗi và bị huỷ ngay khi người dùng sang chương khác —
 * không bao giờ chặn hay tranh với lượt tải chương đang đọc.
 */
class ChapterPrefetchCoordinator(
    private val chapterRepository: ChapterRepository,
    private val translateRepository: TranslateRepository? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val prefetchAheadCount: Int = 5,
    private val translateAheadCount: Int = 2
) {
    private var prefetchJob: Job? = null
    private var generation = 0
    private var lastPrefetchedNovelId: String = ""
    private var lastPrefetchedChapterNumber: Int = -1

    // 401 (phiên hết hạn) tắt dịch trước chỉ cho token bị từ chối; 429/503 nghỉ một lúc (xem TranslateAheadGate).
    private val translateGate = TranslateAheadGate()
    private val endCache = PrefetchEndCache()

    private val _isPrefetching = MutableStateFlow(false)
    val isPrefetching: StateFlow<Boolean> = _isPrefetching.asStateFlow()

    private val _prefetchedCount = MutableStateFlow(0)
    val prefetchedCount: StateFlow<Int> = _prefetchedCount.asStateFlow()

    /**
     * Kích hoạt tải trước các chương kế tiếp cho tác phẩm đang đọc.
     *
     * @param totalChapters tổng số chương của truyện nếu biết (`novel.totalChapters`); 0 = chưa
     * biết, khi đó dựa vào mục lục đã lưu. Truyền vào để không tải quá chương cuối.
     */
    fun triggerPrefetch(novelId: String, currentChapterNumber: Int, totalChapters: Int = 0) {
        if (novelId.isBlank() || currentChapterNumber <= 0) return

        // Tránh kích hoạt lặp lại trên cùng một chương
        if (novelId == lastPrefetchedNovelId && currentChapterNumber == lastPrefetchedChapterNumber) {
            return
        }

        lastPrefetchedNovelId = novelId
        lastPrefetchedChapterNumber = currentChapterNumber

        prefetchJob?.cancel()
        val myGeneration = ++generation
        prefetchJob = scope.launch {
            _isPrefetching.value = true
            try {
                runPrefetch(novelId, currentChapterNumber, totalChapters)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Tải nền hỏng (đĩa, mạng) không được làm sập ứng dụng hay ảnh hưởng luồng đọc chính.
            } finally {
                // Chỉ lượt mới nhất được hạ cờ: lượt cũ bị huỷ chạy nốt finally SAU khi lượt mới đã bật cờ.
                if (myGeneration == generation) _isPrefetching.value = false
            }
        }
    }

    private suspend fun runPrefetch(novelId: String, current: Int, totalChapters: Int) {
        val bound = ChapterBound.resolve(chapterRepository, novelId, current, totalChapters, endCache)
        val targets = ChapterPrefetchPlanner.targets(
            current = current,
            ahead = prefetchAheadCount,
            knownLast = bound.knownLast,
            totalKnown = bound.reliable,
            knownEnd = endCache.get(novelId)
        )
        var count = 0
        for (target in targets) {
            when (prefetchRaw(novelId, target, bound.knownLast)) {
                RawResult.FETCHED -> count++
                RawResult.END -> break
                RawResult.CACHED, RawResult.FAILED -> Unit
            }
        }
        _prefetchedCount.value += count
        translateAhead(novelId, targets)
    }

    fun cancelPrefetch() {
        prefetchJob?.cancel()
        _isPrefetching.value = false
        // Lượt bị huỷ chưa làm xong việc: cho phép kích hoạt lại cùng chương.
        lastPrefetchedNovelId = ""
        lastPrefetchedChapterNumber = -1
    }

    /** Truyện vừa có chương mới (mục lục dài thêm): quên điểm cuối đã dò được. */
    fun onTocExtended(novelId: String) = endCache.forget(novelId)

    private enum class RawResult { CACHED, FETCHED, END, FAILED }

    private suspend fun prefetchRaw(novelId: String, target: Int, knownLast: Int): RawResult {
        return try {
            val cached = chapterRepository.getChapterFromCache(novelId, target)
            val hasContent = cached != null &&
                (!cached.translatedContent.isNullOrBlank() || !cached.originalContent.isNullOrBlank())
            if (hasContent) return RawResult.CACHED
            // Tải từ máy chủ và tự động lưu vào Room SQLite
            chapterRepository.getChapter(novelId, target, forceRefresh = false)
            RawResult.FETCHED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 404 ở chương mà mục lục chưa hề nói là có = hết truyện; dừng và nhớ điểm cuối.
            if (ApiErrors.isNotFound(e) && target > knownLast) {
                endCache.recordMissing(novelId, target)
                RawResult.END
            } else {
                // Lỗi mạng nền không làm ảnh hưởng luồng đọc chính
                RawResult.FAILED
            }
        }
    }

    /** Dịch trước N+1 (và N+2 nếu còn thời gian) ở làn nền, lưu kết quả vào Room. */
    private suspend fun translateAhead(novelId: String, targets: List<Int>) {
        val repo = translateRepository ?: return
        if (!translateGate.canTranslate()) return
        val token = TruyenCloudApi.authToken
        // Nhường đường cho lượt tải chương đang đọc và các thao tác đầu tiên của người dùng.
        delay(TRANSLATE_AHEAD_DELAY_MS)
        for (target in targets.take(translateAheadCount)) {
            if (!translateGate.canTranslate()) return
            val flag = chapterRepository.getChapterFlag(novelId, target) ?: continue
            if (!flag.hasOriginal) continue
            if (!ChapterPrefetchPlanner.shouldTranslateAhead(flag.hasTranslation, flag.isProvisional)) continue
            val raw = chapterRepository.getChapterFromCache(novelId, target)?.originalContent ?: continue
            try {
                repo.translateChapterDetailed(
                    novelId = novelId,
                    chapterNumber = target,
                    content = raw,
                    prefetch = true
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ApiErrors.isAuthError(e)) {
                    translateGate.onAuthRejected(token)
                    return
                }
                if (ApiErrors.isBusyOrRateLimited(e)) {
                    translateGate.onBusy()
                    return
                }
                // Lỗi khác (mạng yếu...): bỏ qua chương này, lần mở chương sau thử lại.
            }
        }
    }

    private companion object {
        const val TRANSLATE_AHEAD_DELAY_MS = 1_500L
    }
}

/** Chương cuối đã biết của một truyện và mức độ đáng tin của nó. */
internal class ChapterBound(val knownLast: Int, val reliable: Boolean) {
    companion object {
        suspend fun resolve(
            repo: ChapterRepository,
            novelId: String,
            current: Int,
            totalChapters: Int,
            endCache: PrefetchEndCache
        ): ChapterBound {
            val localLast = try {
                repo.getLastChapterNumber(novelId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                0
            }
            val last = ChapterPrefetchPlanner.knownLast(localLast, totalChapters)
            // Mục lục cục bộ dài hơn chương hiện tại, hoặc biết tổng chương => ranh giới đáng tin.
            val reliable = totalChapters > 0 || localLast > current || endCache.get(novelId) != null
            return ChapterBound(last, reliable)
        }
    }
}
