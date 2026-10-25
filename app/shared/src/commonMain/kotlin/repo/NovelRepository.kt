package com.truyencloud.shared.repo

import com.truyencloud.shared.api.NovelApi
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.BookmarkDao
import com.truyencloud.shared.data.ChapterDao
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.NovelDao
import com.truyencloud.shared.data.SyncKeys
import com.truyencloud.shared.data.SyncState
import com.truyencloud.shared.data.SyncStateDao
import com.truyencloud.shared.data.getCurrentEpochMillis
import com.truyencloud.shared.library.DiscoverFetch
import com.truyencloud.shared.library.DiscoverMapper
import com.truyencloud.shared.library.DiscoverResult
import com.truyencloud.shared.library.DiscoverSort
import com.truyencloud.shared.prefs.PrefKeys
import com.truyencloud.shared.prefs.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Tủ sách: một truyện ở trong tủ khi và chỉ khi nó CÓ HÀNG trong bảng `novels`.
 *
 * Mở trang chi tiết / tìm kiếm / dán URL KHÔNG chèn hàng. Hàng chỉ được tạo khi người dùng
 * [addToLibrary], bật yêu thích, hoặc lưu tiến độ đọc lần đầu (xem [ReaderRepository]).
 * [removeFromLibrary] xoá hàng (và tuỳ chọn dữ liệu offline / ghi chú).
 */
class NovelRepository(
    private val novelApi: NovelApi,
    private val novelDao: NovelDao,
    /** Cần để xoá chương đã tải khi gỡ truyện khỏi tủ. Không truyền thì [removeFromLibrary] bỏ qua bước này. */
    private val chapterDao: ChapterDao? = null,
    /** Cần để xoá ghi chú/dấu trang khi gỡ truyện khỏi tủ (tuỳ chọn). */
    private val bookmarkDao: BookmarkDao? = null,
    /** Ghi bia mộ "đã xoá khỏi tủ" để lần đồng bộ sau không dựng lại truyện từ máy chủ. */
    private val syncStateDao: SyncStateDao? = null,
    /**
     * Dữ liệu trên máy có thuộc về một tài khoản không (xem [AccountSwitchPolicy.dataBelongsToAccount])? Chỉ khi đó
     * mới ghi bia mộ — kể cả lúc phiên đã hết hạn / đã đăng xuất giữ dữ liệu: xoá truyện lúc đó mà không ghi thì đăng nhập
     * lại cùng tài khoản, truyện sống lại từ máy chủ.
     */
    private val dataBelongsToAccount: () -> Boolean = defaultDataBelongsToAccount()
) {
    // Bộ nhớ đệm kết quả tìm kiếm: gõ lại cùng từ khoá trong 60s không phải gọi mạng nữa
    private val searchCache =
        LinkedHashMap<String, Pair<TimeSource.Monotonic.ValueTimeMark, List<Novel>>>()

    fun getLocalNovelsByShelf(shelf: String): Flow<List<Novel>> = novelDao.getNovelsByShelf(shelf)

    fun getAllLocalNovels(): Flow<List<Novel>> = novelDao.getAllNovels()

    suspend fun getNovelById(novelId: String): Novel? = novelDao.getNovelById(novelId)

    /** Theo dõi hàng của một truyện: null = không ở trong tủ. Đổi khi đọc tiếp, đổi kệ, yêu thích... */
    fun observeNovel(novelId: String): Flow<Novel?> = novelDao.observeNovel(novelId)

    fun getFavoriteNovels(): Flow<List<Novel>> = novelDao.getFavoriteNovels()

    /**
     * Lưu metadata lấy từ server/Khám phá/tìm kiếm MÀ KHÔNG ghi đè dữ liệu của người
     * đọc (tiến độ, kệ, yêu thích, ngày thêm). Truyện chưa có thì chèn mới — nghĩa là ĐƯA VÀO
     * TỦ; nơi chỉ muốn làm mới thông tin cho truyện đã ở trong tủ thì dùng [refreshMetadataIfInLibrary].
     */
    suspend fun saveNovelMetadata(novel: Novel) {
        val inserted = novelDao.insertIfAbsent(novel)
        if (inserted == -1L) updateMetadataRow(novel)
    }

    /**
     * Làm mới thông tin nguồn (tên, bìa, tổng chương...) CHỈ khi truyện đã ở trong tủ; không bao
     * giờ chèn. @return hàng hiện có (kèm tiến độ/kệ/yêu thích) hoặc null nếu truyện không ở trong tủ.
     */
    suspend fun refreshMetadataIfInLibrary(novel: Novel): Novel? {
        if (updateMetadataRow(novel) == 0) return null
        return novelDao.getNovelById(novel.id)
    }

    private suspend fun updateMetadataRow(novel: Novel): Int = novelDao.updateMetadata(
        id = novel.id, title = novel.title, titleVi = novel.titleVi, author = novel.author,
        url = novel.url, coverUrl = novel.coverUrl, sourceId = novel.sourceId,
        totalChapters = novel.totalChapters, description = novel.description, status = novel.status
    )

    // ── Tủ sách ──────────────────────────────────────────────────────────

    /**
     * Thêm truyện vào tủ (kệ [shelf], mặc định "đang đọc"). Đã có trong tủ thì giữ nguyên
     * tiến độ/kệ hiện có (chỉ bật yêu thích nếu [favorite]).
     * @return hàng trong tủ sau khi thêm.
     */
    suspend fun addToLibrary(novel: Novel, shelf: String = "reading", favorite: Boolean = false): Novel {
        val now = getCurrentEpochMillis()
        val row = novel.copy(
            shelfName = shelf,
            isFavorite = favorite,
            lastReadChapter = 0,
            lastReadAt = now,
            addedAt = now,
            syncUpdatedAt = now,
            syncDirty = true
        )
        val inserted = novelDao.insertIfAbsent(row)
        if (inserted == -1L) {
            // Đã ở trong tủ: chỉ bật yêu thích nếu được yêu cầu, không đụng tiến độ/kệ.
            if (favorite) novelDao.setFavorite(novel.id, true, now)
            updateMetadataRow(novel)
        } else {
            // Thêm lại sau khi từng xoá: bia mộ cũ hết nghĩa.
            syncStateDao?.delete(SyncKeys.libraryRemoved(novel.id))
        }
        return novelDao.getNovelById(novel.id) ?: row
    }

    /**
     * Bật/tắt yêu thích. Truyện CHƯA ở trong tủ mà bật yêu thích thì được thêm vào tủ kèm yêu thích.
     * @return trạng thái yêu thích mới.
     */
    suspend fun toggleFavorite(novel: Novel): Boolean {
        val existing = novelDao.getNovelById(novel.id)
        if (existing == null) {
            addToLibrary(novel, favorite = true)
            return true
        }
        val next = !existing.isFavorite
        novelDao.setFavorite(existing.id, next, getCurrentEpochMillis())
        return next
    }

    /** Bật/tắt yêu thích cho truyện ĐÃ ở trong tủ; không làm gì nếu chưa có (dùng [toggleFavorite] với cả Novel để thêm vào tủ). */
    suspend fun toggleFavorite(novelId: String) {
        val novel = novelDao.getNovelById(novelId) ?: return
        novelDao.setFavorite(novelId, !novel.isFavorite, getCurrentEpochMillis())
    }

    suspend fun updateShelf(novelId: String, shelfName: String) {
        novelDao.updateShelf(novelId, shelfName, getCurrentEpochMillis())
    }

    /**
     * Gỡ truyện khỏi tủ: xoá hàng trong `novels`.
     *
     * @param deleteOfflineData xoá luôn các chương đã tải (cả mục lục và vị trí cuộn từng chương).
     * @param deleteAnnotations xoá mềm luôn dấu trang/ghi chú của truyện (mặc định giữ lại — đó là
     * dữ liệu người dùng tự viết, không tải lại được).
     *
     * Khi dữ liệu thuộc về một tài khoản, việc xoá được ghi nhớ để báo máy chủ (kệ "removed") ở lần đồng bộ tới;
     * nếu không, lần kéo tiến độ kế tiếp sẽ dựng lại truyện từ máy chủ.
     */
    suspend fun removeFromLibrary(novelId: String, deleteOfflineData: Boolean, deleteAnnotations: Boolean = false) {
        val existing = novelDao.getNovelById(novelId)
        val now = getCurrentEpochMillis()
        // Khách thật sự không đồng bộ nên không cần bia mộ; ghi sẽ làm xoá lây sang tài khoản khi họ đăng nhập sau này.
        // Đổi sang tài khoản khác thì wipeUserData xoá cả bảng sync_state nên bia mộ không lọt sang người sau.
        if (existing != null && dataBelongsToAccount()) {
            syncStateDao?.put(
                SyncState(SyncKeys.libraryRemoved(novelId), longValue = now, textValue = existing.lastReadChapter.toString())
            )
        }
        novelDao.deleteNovelById(novelId)
        if (deleteOfflineData) chapterDao?.deleteChaptersForNovel(novelId)
        if (deleteAnnotations) bookmarkDao?.softDeleteForNovel(novelId, now)
    }

    /**
     * Máy chủ có thể trả id CHUẨN khác id ứng dụng đã dùng để mở truyện (mở bằng source_id). Truyện
     * đang ở trong tủ dưới id cũ thì chuyển hàng sang id chuẩn — giữ tiến độ/kệ/yêu thích, dời dấu trang —
     * để không sinh hai hàng cho cùng một cuốn (đọc tiếp sẽ lưu tiến độ theo id chuẩn).
     * Chương đã tải ở id cũ được chép sang khi làm mới mục lục (xem ChapterRepository.mergeRemoteChapterMetadata).
     *
     * @return true nếu đã chuyển.
     */
    suspend fun migrateNovelId(oldId: String, newId: String): Boolean {
        if (oldId.isBlank() || newId.isBlank() || oldId == newId) return false
        val old = novelDao.getNovelById(oldId) ?: return false
        if (novelDao.getNovelById(newId) != null) return false
        val now = getCurrentEpochMillis()
        val moved = novelDao.insertIfAbsent(old.copy(id = newId, syncUpdatedAt = maxOf(old.localStamp, 1L), syncDirty = true))
        if (moved == -1L) return false
        // Id cũ thành "đã xoá khỏi tủ" để máy khác không dựng lại hai hàng cho cùng một cuốn.
        if (dataBelongsToAccount()) {
            syncStateDao?.put(
                SyncState(SyncKeys.libraryRemoved(oldId), longValue = now, textValue = old.lastReadChapter.toString())
            )
        }
        novelDao.deleteNovelById(oldId)
        bookmarkDao?.migrateNovelId(oldId, newId, now)
        return true
    }

    /** Giữ để tương thích: gỡ khỏi tủ nhưng giữ chương đã tải. */
    suspend fun deleteNovel(novelId: String) {
        removeFromLibrary(novelId, deleteOfflineData = false)
    }

    /** Dung lượng chương đã tải của một truyện (byte) — hiện trong hộp thoại xác nhận gỡ khỏi tủ. */
    suspend fun offlineBytes(novelId: String): Long = chapterDao?.estimateContentBytesForNovel(novelId) ?: 0L

    // ── Khám phá ─────────────────────────────────────────────────────────

    /**
     * Truyện công khai trên máy chủ (mới cập nhật / đọc nhiều), đã đổi sang [Novel] giống kết quả tìm
     * kiếm để mở trang chi tiết y hệt. KHÔNG chèn vào tủ sách. Ném lỗi khi mất mạng / máy chủ lỗi.
     */
    suspend fun discover(sort: DiscoverSort, limit: Int = 300): List<Novel> =
        DiscoverMapper.toNovels(novelApi.discover(sort.apiValue, limit))

    /**
     * Như [discover] nhưng giữ lý do gợi ý của từng truyện và cờ [DiscoverResult.personalized]. Bảng
     * [DiscoverSort.FOR_YOU] do máy chủ xếp riêng theo tủ sách của người gọi (token đính kèm tự động); máy chủ chưa
     * biết "for_you" thì rơi về bảng chung "Mới cập nhật" với `personalized = false` thay vì ném lỗi.
     */
    suspend fun discoverEntries(sort: DiscoverSort, limit: Int = 300): DiscoverResult =
        DiscoverFetch.load(sort, limit) { apiValue, count -> novelApi.discover(apiValue, count) }

    // ── Tìm kiếm & nhập ──────────────────────────────────────────────────

    suspend fun searchNovels(query: String, source: String? = null): List<Novel> {
        val key = "${query.trim().lowercase()}|${source ?: "all"}"
        searchCache[key]?.let { (mark, cached) ->
            if (mark.elapsedNow() < SEARCH_CACHE_TTL) return cached
            searchCache.remove(key)
        }
        val res = novelApi.search(query, source)
        val results = res.results.map { item ->
            Novel(
                id = item.id ?: item.url,
                title = item.title,
                titleVi = item.title_vi ?: "",
                author = item.author ?: "",
                url = item.url,
                coverUrl = item.cover_url ?: "",
                sourceId = item.source_id,
                totalChapters = item.total_chapters
            )
        }
        if (searchCache.size >= SEARCH_CACHE_MAX_ENTRIES) {
            // Loại bỏ mục cũ nhất theo thứ tự chèn
            searchCache.keys.firstOrNull()?.let { searchCache.remove(it) }
        }
        searchCache[key] = TimeSource.Monotonic.markNow() to results
        return results
    }

    /**
     * Cào một URL nguồn và trả về thông tin truyện. KHÔNG thêm vào tủ (dán URL để xem trước chứ
     * chưa phải để lưu) trừ khi [saveToLibrary]; truyện đã ở trong tủ thì được làm mới metadata và
     * trả về hàng hiện có kèm tiến độ.
     */
    suspend fun scrapeAndSave(sourceUrl: String, saveToLibrary: Boolean = false): Novel {
        val detail = novelApi.scrapeNovel(sourceUrl)
        val novel = Novel(
            id = detail.id,
            title = detail.title,
            titleVi = detail.title_vi ?: "",
            author = detail.author ?: "",
            url = detail.source_url,
            coverUrl = detail.cover_url ?: "",
            sourceId = detail.source_id,
            totalChapters = detail.total_chapters,
            description = detail.description ?: "",
            status = detail.status
        )
        refreshMetadataIfInLibrary(novel)?.let { return it }
        return if (saveToLibrary) addToLibrary(novel) else novel
    }

    /**
     * Ghi đè CẢ HÀNG (kể cả tiến độ/kệ/yêu thích). Chỉ dành cho nhập/khởi tạo dữ liệu mẫu — đừng
     * dùng khi mở trang chi tiết (dùng [refreshMetadataIfInLibrary]).
     */
    suspend fun upsertNovel(novel: Novel) {
        novelDao.upsertNovel(novel)
    }

    private companion object {
        val SEARCH_CACHE_TTL = 60.seconds
        const val SEARCH_CACHE_MAX_ENTRIES = 20
    }
}

/**
 * Mặc định: có phiên đang chạy, hoặc LAST_USER_ID còn ghi chủ sở hữu. Kho cài đặt được mở LAZY ở lần gọi đầu: NovelRepository
 * được tạo trước khi nền tảng khởi tạo SettingsStore, nhưng việc xoá truyện thì luôn diễn ra sau đó.
 */
internal fun defaultDataBelongsToAccount(): () -> Boolean {
    var store: SettingsStore? = null
    return {
        store = store ?: defaultSettingsStore()
        AccountSwitchPolicy.dataBelongsToAccount(
            hasSession = TruyenCloudApi.authToken.isNotBlank(),
            lastUserId = store?.getString(PrefKeys.LAST_USER_ID, "").orEmpty()
        )
    }
}
