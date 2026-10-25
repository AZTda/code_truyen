package com.truyencloud.compose.screens.home

import com.truyencloud.compose.components.NavTab
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.platform.PickedBookFile
import com.truyencloud.shared.api.NovelApi
import com.truyencloud.shared.api.TranslateApi
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.LocalDataMaintenance
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.library.DiscoverCacheStore
import com.truyencloud.shared.library.DiscoverPolicy
import com.truyencloud.shared.library.DiscoverSort
import com.truyencloud.shared.library.HomePrefsStore
import com.truyencloud.shared.library.LibrarySort
import com.truyencloud.shared.library.SearchHistoryStore
import com.truyencloud.shared.library.ServerNovelMetaStore
import com.truyencloud.shared.library.defaultHomePrefs
import com.truyencloud.shared.repo.NovelRepository
import com.truyencloud.shared.repo.ReaderRepository
import com.truyencloud.shared.repo.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Trạng thái màn hình chính. Logic nặng nằm ở các controller cùng thư mục: tủ sách
 * ([HomeLibraryController]), tìm kiếm, gợi ý tìm kiếm, Khám phá và nạp tệp.
 *
 * Đối tượng này sống suốt phiên ứng dụng, qua cả đăng nhập / đăng xuất / đổi tài khoản / xoá dữ liệu: Khám phá và gợi ý
 * tìm kiếm là dữ liệu CỦA TỪNG TÀI KHOẢN nên [HomeAccountSync] theo dõi [userRepository] và [dataMaintenance] để làm lại.
 */
class HomeViewModel(
    private val novelRepository: NovelRepository,
    private val readerRepository: ReaderRepository? = null,
    private val novelApi: NovelApi? = null,
    /** Nguồn "Từ hôm nay" của Tủ sách (từ vựng đã lưu). Null = không có hàng đó. */
    private val gamificationRepository: com.truyencloud.shared.repo.GamificationRepository? = null,
    /** Hồ sơ đang dùng (tài khoản + trạng thái đăng nhập). Null = luôn là khách. */
    private val userRepository: UserRepository? = null,
    /** Báo khi dữ liệu trên máy bị xoá, kể cả từ Cài đặt. Null = không theo dõi. */
    private val dataMaintenance: LocalDataMaintenance? = null,
    private val initialTab: NavTab = NavTab.BOOKSHELF,
    /** Nhớ kiểu sắp xếp tủ sách, bản lưu Khám phá và lịch sử tìm kiếm; null = không nhớ giữa các lần mở. */
    prefs: HomePrefsStore? = defaultHomePrefs(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val _uiState = MutableStateFlow(HomeUiState(currentTab = initialTab))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // Kiểu khai báo rõ: library và suggestions tham chiếu lẫn nhau qua lambda nên suy luận kiểu sẽ bị lặp vòng
    private val library: HomeLibraryController = HomeLibraryController(novelRepository, readerRepository, prefs, scope, _uiState) { suggestions.refresh() }
    private val search = HomeSearchController(novelRepository, scope, _uiState, novelApi) { library.snapshot() }
    private val discover: HomeDiscoverController = HomeDiscoverController(
        loader = { sort -> novelRepository.discoverEntries(sort, DiscoverPolicy.PAGE_SIZE) },
        cache = DiscoverCacheStore(prefs),
        scope = scope,
        state = _uiState,
        initialAccount = userRepository?.profile?.value?.let { HomeAccount.of(it) } ?: HomeAccount.GUEST,
        onFeedsChanged = {
            suggestions.refresh()
            library.republish()
        }
    )
    private val suggestions: HomeSuggestionController = HomeSuggestionController(
        history = SearchHistoryStore(prefs, ownerId = { userRepository?.profile?.value?.id.orEmpty() }),
        library = { library.snapshot() },
        state = _uiState
    )
    private val importer = HomeImportController(novelApi, novelRepository, scope, _uiState)

    init {
        library.start()
        syncCloudProgress()
        observeWordOfTheDay()
        HomeAccountSync(
            accounts = userRepository?.profile?.map { HomeAccount.of(it) } ?: emptyFlow(),
            wipes = dataMaintenance?.wipeCount ?: MutableStateFlow(0),
            prefs = prefs,
            discover = discover,
            suggestions = suggestions,
            scope = scope,
            // Lớp tìm kiếm đang mở còn giữ từ khoá và kết quả khớp từ tủ sách của người trước: đóng lại khi đổi tài khoản
            onAccountReset = search::close
        ).start()
        suggestions.refresh()
        discover.ensureLoaded()
    }

    private fun observeWordOfTheDay() {
        val repo = gamificationRepository ?: return
        scope.launch {
            try {
                // Theo dõi chứ không nạp một lần: ViewModel này sống suốt phiên, qua cả xoá dữ liệu và đổi tài khoản.
                repo.observeWordOfTheDay().collect { w -> _uiState.update { it.copy(wordOfTheDay = w) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    // ── Hướng dẫn chào mừng (AppNav gọi ở lần chạy đầu) ──
    fun openWelcomeGuide() { _uiState.update { it.copy(showWelcomeGuide = true) } }
    fun closeWelcomeGuide() { _uiState.update { it.copy(showWelcomeGuide = false) } }

    // ── Điều hướng tab ──
    fun selectTab(tab: NavTab) {
        val current = _uiState.value.currentTab
        if (tab == current) return
        _uiState.update { it.copy(currentTab = tab) }
        if (tab == NavTab.BOOKSHELF) {
            syncCloudProgress()
            if (_uiState.value.overnightCount > 0) discover.refresh()
        }
        if (tab == NavTab.DISCOVER) discover.ensureLoaded()
        // Rời Tủ sách thì thoát chế độ chọn nhiều để không treo thanh chọn ở tab khác
        if (current == NavTab.BOOKSHELF) library.clearSelection()
    }

    // ── Đồng bộ ──
    fun syncCloudProgress() {
        syncOvernightStatus()
        val repo = readerRepository ?: return
        scope.launch {
            try {
                repo.syncAllProgressFromServer()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun syncOvernightStatus() {
        scope.launch {
            try {
                val status = TranslateApi().getOvernightStatus()
                status.enabled_novels.forEach { n ->
                    ServerNovelMetaStore.record(
                        novelId = n.id,
                        translatedChapters = n.translated_chapters,
                        overnightEnabled = true
                    )
                }
                _uiState.update { s ->
                    val overnightIds = status.enabled_novels.mapTo(HashSet()) { it.id }
                    val counts = s.discover.translatedCounts + status.enabled_novels.associate { it.id to it.translated_chapters }
                    s.copy(discover = s.discover.copy(overnightIds = overnightIds, translatedCounts = counts))
                }
                library.republish()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** Kéo-để-làm-mới ở Tủ sách: đồng bộ tiến độ / kệ / yêu thích với máy chủ (khách thì chỉ nhắc đăng nhập). */
    fun refreshBookshelf() {
        if (_uiState.value.isSyncingLibrary) return
        discover.refresh()
        syncOvernightStatus()
        val repo = readerRepository
        if (repo == null || TruyenCloudApi.authToken.isBlank()) {
            library.showMessage("Đăng nhập để đồng bộ tủ sách giữa các thiết bị.")
            return
        }
        scope.launch {
            _uiState.update { it.copy(isSyncingLibrary = true) }
            try {
                val outcome = repo.syncAllProgress()
                val failure = outcome.error
                when {
                    failure != null -> library.showMessage(com.truyencloud.shared.api.FriendlyErrors.of(failure), NoticeTone.Error)
                    outcome.pushFailed -> library.showMessage(
                        "Một số thay đổi chưa lên được máy chủ, sẽ tự thử lại sau.",
                        NoticeTone.Warning
                    )
                    outcome.changed > 0 -> library.showMessage("Đã cập nhật ${outcome.changed} truyện từ tài khoản của bạn.", NoticeTone.Success)
                    else -> library.showMessage("Tủ sách đã là mới nhất.", NoticeTone.Success)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                library.showMessage(com.truyencloud.shared.api.FriendlyErrors.of(e), NoticeTone.Error)
            } finally {
                _uiState.update { it.copy(isSyncingLibrary = false) }
            }
        }
    }

    // ── Tủ sách ──
    fun reloadBookshelf() = library.start()
    fun onSelectShelf(shelf: String) = library.selectShelf(shelf)
    fun onSelectSort(sort: LibrarySort) = library.setSort(sort)
    fun onToggleFavoritesOnly() = library.setFavoritesOnly(!_uiState.value.favoritesOnly)
    fun onToggleOvernightOnly() = library.setOvernightOnly(!_uiState.value.overnightOnly)
    fun onToggleAiTranslatedOnly() = library.setAiTranslatedOnly(!_uiState.value.aiTranslatedOnly)
    fun onNovelLongPress(novel: Novel) = library.onNovelLongPress(novel)
    fun onToggleSelection(novel: Novel) = library.toggleSelection(novel)
    fun selectAllVisible() = library.selectAllVisible()
    fun clearSelection() = library.clearSelection()
    fun openLibrarySheet() = library.openSheet()
    fun closeLibrarySheet() = library.closeSheet()
    fun moveSelectionToShelf(shelf: String) = library.moveSelectionToShelf(shelf)
    fun toggleFavoriteForSelection() = library.toggleFavoriteForSelection()
    fun requestRemoval() = library.requestRemoval()
    fun confirmRemoval(deleteOfflineData: Boolean) = library.confirmRemoval(deleteOfflineData)
    fun cancelRemoval() = library.cancelRemoval()
    fun dismissLibraryMessage() = library.dismissMessage()

    // ── Khám phá ──
    fun refreshDiscover() = discover.refresh()
    fun onSelectDiscoverSort(sort: DiscoverSort) = discover.selectSort(sort)

    // ── Tìm kiếm ──
    fun openSearch() {
        library.clearSelection()
        search.open()
        suggestions.refresh()
    }
    fun closeSearch() = search.close()
    fun onSearchQueryChange(query: String) = search.onQueryChange(query)
    fun onSelectSource(source: String) = search.onSelectSource(source)
    fun performSearch(source: String? = null) = search.perform(source)

    /** Bấm "Tìm" / phím tìm của bàn phím: lượt tìm có chủ đích nên được ghi nhớ (gõ dở dang thì không). */
    fun submitSearch() {
        suggestions.record(_uiState.value.searchQuery)
        search.perform()
    }

    /** Chạm một chip gợi ý: điền vào ô, tìm ngay và ghi nhớ. */
    fun onSuggestionClick(query: String) {
        suggestions.record(query)
        search.onQueryChange(query)
        search.perform()
    }

    /** Chạm một kết quả: từ khoá đã cho ra thứ người dùng cần nên đáng nhớ; rồi đóng lớp tìm kiếm. */
    fun onSearchResultPicked() {
        suggestions.record(_uiState.value.searchQuery)
        search.close()
    }

    fun clearSearchHistory() = suggestions.clearHistory()

    // ── Nạp tệp sách ──
    fun openImportDialog() = importer.open()
    fun closeImportDialog() = importer.close()
    fun dismissImportMessage() = importer.dismissMessage()
    fun importNovel(title: String, author: String, content: String) = importer.importText(title, author, content)
    fun importNovelFile(title: String, author: String, file: PickedBookFile) = importer.importFile(title, author, file)
}
