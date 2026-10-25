package com.truyencloud.compose.screens.home

import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.shared.api.FriendlyErrors
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.displayTitle
import com.truyencloud.shared.library.HomePrefKeys
import com.truyencloud.shared.library.HomePrefsStore
import com.truyencloud.shared.library.LibraryListLogic
import com.truyencloud.shared.library.LibrarySort
import com.truyencloud.shared.library.LibraryShelves
import com.truyencloud.shared.library.ServerNovelMetaStore
import com.truyencloud.shared.repo.NovelRepository
import com.truyencloud.shared.repo.ReaderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Tủ sách: theo dõi Room, lọc kệ / yêu thích, sắp xếp (nhớ lựa chọn), chọn nhiều và các thao tác
 * chuyển kệ / yêu thích / gỡ khỏi tủ. Mọi thay đổi đi qua Room nên danh sách tự cập nhật.
 */
internal class HomeLibraryController(
    private val novelRepository: NovelRepository,
    private val readerRepository: ReaderRepository?,
    private val prefs: HomePrefsStore?,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<HomeUiState>,
    /** Gọi sau mỗi lần Room phát danh sách mới (gợi ý tìm kiếm lấy tên truyện / tác giả từ tủ sách). */
    private val onLibraryChanged: () -> Unit = {}
) {
    private var all: List<Novel> = emptyList()
    private var collectJob: Job? = null
    private var metaJob: Job? = null
    private var messageJob: Job? = null

    /** Toàn bộ tủ sách (mới nhất từ Room) — dùng cho tìm kiếm cục bộ. */
    fun snapshot(): List<Novel> = all

    init {
        val saved = LibrarySort.fromKey(readPref(HomePrefKeys.LIBRARY_SORT))
        state.update { it.copy(librarySort = saved) }
    }

    /** Bắt đầu (hoặc bắt đầu lại, khi bấm "Thử lại") theo dõi tủ sách. */
    fun start() {
        collectJob?.cancel()
        collectJob = scope.launch {
            novelRepository.getAllLocalNovels()
                .onStart { state.update { it.copy(isLoadingBookshelf = true, bookshelfError = null) } }
                .catch {
                    // Lỗi đọc Room: không đưa thông báo kỹ thuật ra cho người dùng
                    state.update {
                        it.copy(
                            isLoadingBookshelf = false,
                            bookshelfError = "Không đọc được dữ liệu tủ sách trên thiết bị. Hãy thử lại hoặc khởi động lại ứng dụng."
                        )
                    }
                }
                .collect { list ->
                    all = list
                    publish()
                    onLibraryChanged()
                }
        }
        if (metaJob == null) {
            metaJob = scope.launch {
                ServerNovelMetaStore.state.collect { publish() }
            }
        }
    }

    /** Tính lại mọi trường dẫn xuất từ [all] và lựa chọn hiện tại. */
    fun republish() = publish()

    private fun publish() {
        state.update { s ->
            val shelfScoped = LibraryListLogic.inShelf(all, s.selectedShelf)
            val overnightCount = shelfScoped.count { s.discover.isOvernight(it) }
            val aiTranslatedCount = shelfScoped.count { s.discover.translatedOf(it) > 0 }
            val rawVisible = LibraryListLogic.visible(all, s.selectedShelf, s.favoritesOnly, s.librarySort)
            val visible = rawVisible.filter { novel ->
                (!s.overnightOnly || s.discover.isOvernight(novel)) &&
                    (!s.aiTranslatedOnly || s.discover.translatedOf(novel) > 0)
            }
            val selection = s.selection.retainOnly(visible.map { it.id })
            s.copy(
                shelfNovels = visible,
                recentNovels = LibraryListLogic.continueReading(all).take(RECENT_LIMIT),
                allNovelsCount = all.size,
                shelfCounts = LibraryListLogic.shelfCounts(all),
                favoriteCount = LibraryListLogic.favoriteCount(all, s.selectedShelf),
                overnightCount = overnightCount,
                aiTranslatedCount = aiTranslatedCount,
                libraryKeys = LibraryListLogic.libraryKeys(all),
                selection = selection,
                showLibrarySheet = s.showLibrarySheet && selection.isActive,
                isLoadingBookshelf = false,
                bookshelfError = null
            )
        }
    }

    // ── Bộ lọc & sắp xếp ─────────────────────────────────────────────────

    fun selectShelf(shelf: String) {
        state.update { it.copy(selectedShelf = LibraryShelves.normalize(shelf)) }
        publish()
    }

    fun setSort(sort: LibrarySort) {
        writePref(HomePrefKeys.LIBRARY_SORT, sort.key)
        state.update { it.copy(librarySort = sort) }
        publish()
    }

    fun setFavoritesOnly(enabled: Boolean) {
        state.update { it.copy(favoritesOnly = enabled) }
        publish()
    }

    fun setOvernightOnly(enabled: Boolean) {
        state.update { it.copy(overnightOnly = enabled) }
        publish()
    }

    fun setAiTranslatedOnly(enabled: Boolean) {
        state.update { it.copy(aiTranslatedOnly = enabled) }
        publish()
    }

    // ── Chọn nhiều ───────────────────────────────────────────────────────

    /** Nhấn giữ một bìa: chọn nó (giữ nguyên các truyện đã chọn) và mở tờ hành động cho cả tập đã chọn. */
    fun onNovelLongPress(novel: Novel) {
        state.update { it.copy(selection = it.selection.add(novel.id), showLibrarySheet = true) }
    }

    fun toggleSelection(novel: Novel) {
        state.update { it.copy(selection = it.selection.toggle(novel.id), showLibrarySheet = false) }
    }

    fun selectAllVisible() {
        state.update { it.copy(selection = it.selection.selectAll(it.shelfNovels.map { n -> n.id })) }
    }

    fun clearSelection() {
        state.update { it.copy(selection = it.selection.clear(), showLibrarySheet = false) }
    }

    fun openSheet() {
        state.update { if (it.selection.isActive) it.copy(showLibrarySheet = true) else it }
    }

    /** Đóng tờ hành động nhưng GIỮ lựa chọn để người dùng chạm thêm bìa khác. */
    fun closeSheet() {
        state.update { it.copy(showLibrarySheet = false) }
    }

    // ── Thao tác trên các truyện đang chọn ───────────────────────────────

    private fun targets(): List<Novel> {
        val ids = state.value.selection.ids
        return all.filter { it.id in ids }
    }

    fun moveSelectionToShelf(shelf: String) {
        val target = LibraryShelves.normalize(shelf)
        val moving = LibraryListLogic.needingShelfMove(targets(), target)
        clearSelection()
        if (moving.isEmpty()) return
        runAction("Đã chuyển ${countLabel(moving)} sang “${LibraryShelves.label(target)}”") {
            moving.forEach { novelRepository.updateShelf(it.id, target) }
            pushProgress(moving)
        }
    }

    /** Tất cả đã yêu thích thì bỏ yêu thích, còn lại thì đặt yêu thích cho cả tập. */
    fun toggleFavoriteForSelection() {
        val targets = targets()
        val change = LibraryListLogic.favoriteChange(targets)
        clearSelection()
        if (targets.isEmpty()) return
        val text = if (change.makeFavorite) "Đã thêm ${countLabel(targets)} vào yêu thích" else "Đã bỏ yêu thích ${countLabel(targets)}"
        runAction(text) {
            change.toChange.forEach { novelRepository.toggleFavorite(it.id) }
            pushProgress(targets)
        }
    }

    /** Đo dung lượng offline rồi mở hộp thoại xác nhận gỡ khỏi tủ. */
    fun requestRemoval() {
        val targets = targets()
        if (targets.isEmpty()) return
        state.update { it.copy(showLibrarySheet = false) }
        scope.launch {
            val bytes = try {
                targets.sumOf { novelRepository.offlineBytes(it.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                0L
            }
            state.update { it.copy(pendingRemoval = PendingRemoval(targets, bytes)) }
        }
    }

    fun cancelRemoval() {
        state.update { it.copy(pendingRemoval = null) }
    }

    fun confirmRemoval(deleteOfflineData: Boolean) {
        val pending = state.value.pendingRemoval ?: return
        state.update { it.copy(pendingRemoval = null) }
        clearSelection()
        val label = if (pending.novels.size == 1) "“${pending.novels.first().displayTitle}”" else "${pending.novels.size} truyện"
        runAction("Đã xóa $label khỏi tủ sách") {
            pending.novels.forEach { novelRepository.removeFromLibrary(it.id, deleteOfflineData) }
            // Báo máy chủ ngay (bia mộ "đã xóa") để máy khác không dựng lại truyện; khách thì không làm gì
            readerRepository?.flushDirtyProgress()
        }
    }

    private fun pushProgress(novels: List<Novel>) {
        val repo = readerRepository ?: return
        novels.forEach { repo.syncProgressToServer(it.id) }
    }

    private fun runAction(success: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                showMessage(success, NoticeTone.Success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Không cập nhật được tủ sách. ${FriendlyErrors.of(e)}", NoticeTone.Error)
            }
        }
    }

    private fun countLabel(novels: List<Novel>): String =
        if (novels.size == 1) "“${novels.first().displayTitle}”" else "${novels.size} truyện"

    // ── Thông báo ngắn ───────────────────────────────────────────────────

    fun showMessage(text: String, tone: NoticeTone = NoticeTone.Info) {
        messageJob?.cancel()
        state.update { it.copy(libraryMessage = text, libraryMessageTone = tone) }
        messageJob = scope.launch {
            delay(MESSAGE_MS)
            state.update { it.copy(libraryMessage = null) }
        }
    }

    fun dismissMessage() {
        messageJob?.cancel()
        state.update { it.copy(libraryMessage = null) }
    }

    // Đọc/ghi cài đặt không bao giờ được làm hỏng giao diện
    private fun readPref(key: String): String? = try { prefs?.getString(key) } catch (_: Exception) { null }

    private fun writePref(key: String, value: String) { try { prefs?.putString(key, value) } catch (_: Exception) { } }

    private companion object {
        /** Số thẻ tối đa ở hàng "Đang đọc" đầu Tủ sách. */
        const val RECENT_LIMIT = 6
        const val MESSAGE_MS = 4_000L
    }
}
