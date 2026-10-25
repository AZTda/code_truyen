package com.truyencloud.compose.screens.home

import com.truyencloud.compose.components.NavTab
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.library.DiscoverFeed
import com.truyencloud.shared.library.DiscoverSort
import com.truyencloud.shared.library.LibrarySelection
import com.truyencloud.shared.library.LibrarySort
import com.truyencloud.shared.library.LibraryShelves
import com.truyencloud.shared.library.SearchSuggestionSection
import com.truyencloud.shared.library.ServerNovelMetaStore

/** Truyện đang chờ xác nhận gỡ khỏi tủ, kèm dung lượng đọc offline đã đo trước khi hỏi. */
data class PendingRemoval(val novels: List<Novel>, val offlineBytes: Long)

/**
 * Tab Khám phá. [feeds] chỉ giữ bảng của TÀI KHOẢN HIỆN TẠI (đổi tài khoản là dựng lại từ bản lưu của tài khoản mới, xem
 * HomeDiscoverController); [sorts] là các lựa chọn hiện có — khách không có "Dành cho bạn".
 */
data class DiscoverUiState(
    val sort: DiscoverSort = DiscoverSort.RECENT,
    val sorts: List<DiscoverSort> = DiscoverSort.available(loggedIn = false),
    val feeds: Map<DiscoverSort, DiscoverFeed> = emptyMap(),
    /** Các bảng đang tải (lần đầu mở hoặc kéo làm mới). */
    val loading: Set<DiscoverSort> = emptySet(),
    val overnightIds: Set<String> = emptySet(),
    val translatedCounts: Map<String, Int> = emptyMap()
) {
    fun feed(sort: DiscoverSort): DiscoverFeed = feeds[sort] ?: DiscoverFeed()
    val current: DiscoverFeed get() = feed(sort)

    /** Vòng xoay kéo-để-làm-mới của bảng đang xem. */
    val isRefreshing: Boolean get() = sort in loading

    fun isOvernight(novel: Novel): Boolean =
        novel.id in overnightIds ||
            ServerNovelMetaStore.get(novel.id)?.overnightEnabled == true ||
            feeds.values.any { it.isOvernight(novel) }

    fun translatedOf(novel: Novel): Int =
        maxOf(
            translatedCounts[novel.id] ?: 0,
            ServerNovelMetaStore.get(novel.id)?.translatedChapters ?: 0,
            feeds.values.maxOfOrNull { it.translatedOf(novel) } ?: 0
        )
}

data class HomeUiState(
    val currentTab: NavTab = NavTab.BOOKSHELF,

    // ── Tìm kiếm ──
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<Novel> = emptyList(),
    val searchError: String? = null,
    val selectedSource: String = "all",
    /** Gợi ý khi chưa gõ gì: lịch sử, tủ sách, "Dành cho bạn" của CHÍNH tài khoản này (xem HomeSuggestionController). */
    val suggestions: List<SearchSuggestionSection> = emptyList(),
    /** Gợi ý tức thì từ máy chủ (< 5ms: sửa lỗi chính tả, từ khoá tiếng Trung, truyện trong kho 1TB) khi đang gõ. */
    val liveSuggestions: List<com.truyencloud.shared.api.RemoteSuggestItem> = emptyList(),

    // ── Tủ sách ──
    val selectedShelf: String = LibraryShelves.READING,
    /** Danh sách đang hiển thị: đã lọc theo kệ / yêu thích và sắp xếp. */
    val shelfNovels: List<Novel> = emptyList(),
    /** Truyện đang đọc dở, mới nhất trước: hàng "Đang đọc" ở đầu Tủ sách (tối đa 6). */
    val recentNovels: List<Novel> = emptyList(),
    val allNovelsCount: Int = 0,
    val shelfCounts: Map<String, Int> = emptyMap(),
    /** Số truyện yêu thích trong kệ đang chọn. */
    val favoriteCount: Int = 0,
    /** Số truyện đang bật dịch ngầm trong kệ đang chọn. */
    val overnightCount: Int = 0,
    /** Số truyện đã có chương dịch AI trong kệ đang chọn. */
    val aiTranslatedCount: Int = 0,
    val librarySort: LibrarySort = LibrarySort.DEFAULT,
    val favoritesOnly: Boolean = false,
    val overnightOnly: Boolean = false,
    val aiTranslatedOnly: Boolean = false,
    /** Khoá nhận diện truyện đã có trong tủ (id + địa chỉ nguồn) để đánh dấu ở Khám phá / tìm kiếm. */
    val libraryKeys: Set<String> = emptySet(),
    val selection: LibrarySelection = LibrarySelection(),
    val showLibrarySheet: Boolean = false,
    val pendingRemoval: PendingRemoval? = null,
    val libraryMessage: String? = null,
    /** Sắc thái của [libraryMessage]: quyết định biểu tượng và màu của dải báo. */
    val libraryMessageTone: NoticeTone = NoticeTone.Info,
    val isSyncingLibrary: Boolean = false,
    val isLoadingBookshelf: Boolean = true,
    val bookshelfError: String? = null,

    // ── Nạp tệp sách ──
    val showImportDialog: Boolean = false,
    val isImporting: Boolean = false,
    val importMessage: String? = null,
    /** [importMessage] là lỗi (đỏ) hay kết quả thành công (xanh). */
    val importMessageIsError: Boolean = false,

    // ── Khác ──
    /** Từ vựng hôm nay (hàng mỏng "Từ hôm nay" ở Tủ sách); null = chưa lưu từ nào nên không hiện. */
    val wordOfTheDay: SavedWord? = null,
    /** AppNav (SessionPrompts) mở hướng dẫn chào mừng ở lần chạy đầu qua openWelcomeGuide() và theo dõi cờ này. */
    val showWelcomeGuide: Boolean = false,
    val discover: DiscoverUiState = DiscoverUiState()
)
