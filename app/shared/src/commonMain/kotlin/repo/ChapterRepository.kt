package com.truyencloud.shared.repo

import com.truyencloud.shared.api.ChapterApi
import com.truyencloud.shared.api.ChapterRemoteResponse
import com.truyencloud.shared.api.NovelApi
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.CachedNovelSummary
import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.ChapterDao
import com.truyencloud.shared.data.ChapterFlags
import com.truyencloud.shared.data.ENGINE_GOOGLE_FALLBACK
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

class ChapterRepository(
    private val chapterApi: ChapterApi,
    private val chapterDao: ChapterDao
) {
    // ── Danh sách chương ─────────────────────────────────────────────────

    /**
     * Danh sách chương KHÔNG kèm nội dung (originalContent/translatedContent = null, bản
     * Anh/song ngữ = ""). Chỉ dùng cho danh sách/mục lục: muốn biết chương nào đã có bản dịch
     * thì dùng [observeChapterFlags]; muốn nội dung thì [getChapter].
     */
    suspend fun getChapters(novelId: String): List<Chapter> = chapterDao.getChapterListLight(novelId)

    /** Cờ nội dung từng chương (có gốc / có bản dịch / bản tạm...) — theo dõi được, không kéo nguyên văn. */
    fun observeChapterFlags(novelId: String): Flow<List<ChapterFlags>> = chapterDao.observeChapterFlags(novelId)

    suspend fun getChapterFlags(novelId: String): List<ChapterFlags> = chapterDao.getChapterFlags(novelId)

    suspend fun getChapterFlag(novelId: String, chapterNumber: Int): ChapterFlags? =
        chapterDao.getChapterFlag(novelId, chapterNumber)

    /** Lấy danh sách số chương máy chủ đã dịch sẵn (để hiện huy hiệu "AI đã dịch" trong Mục lục nhanh của màn đọc). */
    suspend fun getServerTranslatedChapters(novelId: String): Set<Int> {
        return try {
            NovelApi().getNovelDetail(novelId).translated_chapter_numbers.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptySet()
        }
    }

    /**
     * Số chương lớn nhất trong mục lục đã lưu (0 nếu chưa có) — thẻ "hết truyện" của trình đọc
     * dùng để biết chương hiện tại là chương cuối. Kết hợp với `novel.totalChapters` nếu có.
     */
    suspend fun getLastChapterNumber(novelId: String): Int = chapterDao.getMaxChapterNumber(novelId) ?: 0

    /**
     * Lưu mục lục từ máy chủ CHỈ ở mức metadata: chương mới thì chèn, chương đã có thì chỉ sửa
     * tên/đường dẫn khi thật sự khác. Nội dung, bản dịch, vị trí cuộn và cờ đã đọc không bao
     * giờ bị chép đè bởi một lần làm mới mục lục.
     */
    suspend fun saveChapters(chapters: List<Chapter>) {
        for ((novelId, group) in chapters.groupBy { it.novelId }) {
            val existing = chapterDao.getChapterListLight(novelId).associateBy { it.chapterNumber }
            val diff = ChapterToc.diff(existing, group)
            if (diff.toInsert.isNotEmpty()) chapterDao.insertChaptersIfAbsent(diff.toInsert)
            for ((local, remote) in diff.toUpdate) {
                val (title, titleVi, url) = ChapterToc.mergedMetadata(local, remote)
                chapterDao.updateChapterMetadata(novelId, local.chapterNumber, title, titleVi, url)
            }
        }
    }

    /** Cache-only read: no network call, returns null if chapter not in local DB */
    suspend fun getChapterFromCache(novelId: String, chapterNumber: Int): Chapter? =
        chapterDao.getChapter(novelId, chapterNumber)

    suspend fun getChapter(novelId: String, chapterNumber: Int, forceRefresh: Boolean = false): Chapter {
        val cached = chapterDao.getChapter(novelId, chapterNumber)
        val badCached = cached != null && ChapterOfflinePolicy.isRefusalText(cached.translatedContent)
        // If we have valid translated content cached locally and not forceRefresh, return immediately
        if (!forceRefresh && !badCached && cached != null && !cached.translatedContent.isNullOrBlank()) {
            return cached
        }

        return try {
            // Fetch from Fat Backend server (which has the complete pre-translated chapter)
            val remote = chapterApi.getChapter(novelId, chapterNumber)
            persistFetched(novelId, chapterNumber, cached, remote, keepLocalTranslation = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cached != null && ChapterOfflinePolicy.hasAnyContent(cached)) cached else throw e
        }
    }

    /**
     * Ghi nội dung vừa tải về mà KHÔNG REPLACE cả hàng: vị trí cuộn, cờ đã đọc và bản Anh/song
     * ngữ được giữ nguyên theo giá trị MỚI NHẤT trong Room (không phải bản chụp đọc trước khi
     * gọi mạng — người dùng có thể đã cuộn trong lúc chờ).
     *
     * @param keepLocalTranslation máy chủ không có bản dịch thì giữ bản dịch đang có trong máy
     * (tải lại/ nâng cấp một chương không được làm mất bản dịch đã có). Dịch lại thì đặt false.
     */
    private suspend fun persistFetched(
        novelId: String,
        chapterNumber: Int,
        cached: Chapter?,
        remote: ChapterRemoteResponse,
        keepLocalTranslation: Boolean
    ): Chapter {
        val remoteTranslated = remote.translated_content
            ?.takeIf { it.isNotBlank() && !ChapterOfflinePolicy.isRefusalText(it) }
        val remoteIsProvisional = remote.translation_engine == ENGINE_GOOGLE_FALLBACK
        val kept: Chapter? = cached?.takeIf {
            keepLocalTranslation && ChapterOfflinePolicy.hasUsableTranslation(it) && (
                remoteTranslated == null ||
                    // Máy chủ chỉ có bản tạm của Google mà máy đã có bản AI: không hạ cấp.
                    (remoteIsProvisional && it.translationEngine != ENGINE_GOOGLE_FALLBACK)
                )
        }
        val translated = kept?.translatedContent ?: remoteTranslated
        val engine = (if (kept != null) kept.translationEngine else remote.translation_engine ?: cached?.translationEngine)
            ?: DEFAULT_ENGINE

        val fresh = Chapter(
            id = "${novelId}_${chapterNumber}",
            novelId = novelId,
            chapterNumber = chapterNumber,
            title = remote.title,
            titleVi = remote.title_vi ?: cached?.titleVi ?: "",
            url = cached?.url ?: "",
            originalContent = remote.original_content,
            translatedContent = translated,
            translationEngine = engine
        )
        chapterDao.insertChaptersIfAbsent(listOf(fresh))
        chapterDao.updateFetchedContent(
            novelId = novelId,
            chapterNumber = chapterNumber,
            title = remote.title,
            titleVi = remote.title_vi.orEmpty(),
            original = remote.original_content,
            translated = translated,
            engine = engine
        )
        return chapterDao.getChapter(novelId, chapterNumber) ?: fresh
    }

    /**
     * Hợp nhất mục lục từ máy chủ với mục lục đã lưu để hiển thị. Trả danh sách KHÔNG kèm nội
     * dung (trừ khi phải chép từ một id cũ của cùng truyện, xem bên dưới) — việc ghi xuống
     * Room là của [saveChapters] và chỉ ghi phần metadata.
     */
    suspend fun mergeRemoteChapterMetadata(
        remoteChapters: List<Chapter>,
        fallbackNovelId: String? = null
    ): List<Chapter> {
        if (remoteChapters.isEmpty()) return emptyList()
        val remoteNovelId = remoteChapters.first().novelId
        val local = chapterDao.getChapterListLight(remoteNovelId).associateBy { it.chapterNumber }.toMutableMap()
        // Máy chủ đôi khi trả id chuẩn khác id ứng dụng đã dùng để mở truyện. Khi mục lục mới
        // còn trống mà id cũ có chương thì chép sang (kèm nội dung) — đường hiếm nên mới đọc bản đầy đủ.
        if (local.isEmpty() && !fallbackNovelId.isNullOrBlank() && fallbackNovelId != remoteNovelId) {
            chapterDao.getChaptersForNovelOnce(fallbackNovelId).forEach { local[it.chapterNumber] = it }
        }
        return remoteChapters.map { remote ->
            val cached = local[remote.chapterNumber] ?: return@map remote
            remote.copy(
                id = remote.id.ifBlank { cached.id },
                title = remote.title.ifBlank { cached.title },
                titleVi = remote.titleVi.ifBlank { cached.titleVi },
                url = remote.url.ifBlank { cached.url },
                originalContent = cached.originalContent ?: remote.originalContent,
                translatedContent = cached.translatedContent ?: remote.translatedContent,
                translationEngine = cached.translationEngine ?: remote.translationEngine,
                translatedContentEn = cached.translatedContentEn,
                englishLevel = cached.englishLevel,
                translatedContentBilingual = cached.translatedContentBilingual,
                bilingualLevel = cached.bilingualLevel,
                isRead = cached.isRead,
                scrollPosition = cached.scrollPosition,
                updatedAt = maxOf(cached.updatedAt, remote.updatedAt)
            )
        }
    }

    suspend fun recrawlChapter(
        novelId: String,
        chapterNumber: Int,
        autoTranslate: Boolean = true,
        customPrompt: String? = null
    ): Chapter {
        val remote = chapterApi.recrawlChapter(novelId, chapterNumber, autoTranslate, customPrompt)
        val cached = chapterDao.getChapter(novelId, chapterNumber)
        return persistFetched(novelId, chapterNumber, cached, remote, keepLocalTranslation = false)
    }

    suspend fun getChapterRecap(novelId: String, chapterNumber: Int) =
        chapterApi.getChapterRecap(novelId, chapterNumber)

    suspend fun explainLore(term: String, context: String? = null) =
        chapterApi.explainLore(term, context)

    suspend fun preloadNextChapters(novelId: String, currentChapter: Int, count: Int = 2) {
        for (i in 1..count) {
            val targetNum = currentChapter + i
            try {
                val cached = chapterDao.getChapter(novelId, targetNum)
                if (cached == null || cached.translatedContent.isNullOrBlank()) {
                    getChapter(novelId, targetNum, forceRefresh = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Background pre-caching error ignored
            }
        }
    }

    // ── Tiến độ đọc ──────────────────────────────────────────────────────

    /**
     * Đánh dấu chương đã đọc xong. Trình đọc gọi khi người dùng sang chương kế tiếp; việc lưu
     * vị trí cuộn chỉ tự bật cờ khi đã cuộn gần hết chương (xem [ReaderRepository]).
     */
    suspend fun markChapterRead(novelId: String, chapterNumber: Int) {
        chapterDao.markRead(novelId, chapterNumber)
    }

    suspend fun getScrollPosition(novelId: String, chapterNumber: Int): Float =
        chapterDao.getScrollPosition(novelId, chapterNumber) ?: 0f

    // ── Chương đã tải offline ────────────────────────────────────────────

    /** Số chương đã tải và SẴN SÀNG đọc offline (có bản dịch; không tính chương chỉ có bản gốc). */
    fun getDownloadedChapterNumbers(novelId: String): Flow<List<Int>> =
        chapterDao.getDownloadedChapterNumbers(novelId)

    suspend fun getDownloadedChapterCount(novelId: String): Int =
        chapterDao.getDownloadedChapterCount(novelId)

    /** @return số chương đọc offline được bằng tiếng Việt (xem [downloadChaptersDetailed] để biết chi tiết). */
    suspend fun downloadChaptersForOffline(novelId: String, fromChapter: Int, toChapter: Int): Int =
        downloadChaptersDetailed(novelId, fromChapter, toChapter).usable

    /**
     * Tải một dải chương về máy. Chương đã có bản dịch dùng được thì bỏ qua; chương chỉ có văn
     * bản gốc (vd. bản tải trước thô) hoặc chỉ có bản dịch tạm (khi đã đăng nhập) được tải lại
     * để nâng cấp. Đếm riêng chương chỉ có bản gốc để giao diện báo đúng sự thật.
     */
    suspend fun downloadChaptersDetailed(
        novelId: String,
        fromChapter: Int,
        toChapter: Int,
        requireAi: Boolean = TruyenCloudApi.authToken.isNotBlank()
    ): OfflineDownloadResult = ChapterOfflineDownloader(this).download(novelId, fromChapter, toChapter, requireAi)

    suspend fun downloadSpecificChapters(
        novelId: String,
        numbers: List<Int>,
        requireAi: Boolean = false
    ): OfflineDownloadResult = ChapterOfflineDownloader(this).downloadList(novelId, numbers, requireAi)

    /**
     * Xoá nội dung chương đã tải về, giữ lại mục lục và tiến độ đọc.
     *
     * Nút "Xoá bộ nhớ đệm" trong Cài Đặt trước đây chỉ đổi nhãn thành "0.0 MB"
     * mà không xoá gì cả.
     *
     * @return số chương đã được giải phóng nội dung
     */
    suspend fun clearDownloadedContent(): Int = chapterDao.clearAllContent()

    /** Ước lượng dung lượng nội dung chương đang chiếm (byte). */
    suspend fun estimateCacheBytes(): Long = chapterDao.estimateContentBytes() ?: 0L

    // ── Per-novel storage management (Feature #3: Offline UX) ──

    /** Dung lượng offline của 1 truyện (byte). */
    suspend fun estimateCacheBytesForNovel(novelId: String): Long =
        chapterDao.estimateContentBytesForNovel(novelId)

    /** Xoá nội dung offline của 1 truyện, giữ mục lục. */
    suspend fun clearContentForNovel(novelId: String): Int =
        chapterDao.clearContentForNovel(novelId)

    /** Tóm tắt các truyện đã cache (cho StorageManager). */
    suspend fun getCachedNovelSummaries(): List<CachedNovelSummary> =
        chapterDao.getCachedNovelSummaries()

    private companion object {
        const val DEFAULT_ENGINE = "ANTIGRAVITY"
    }
}
