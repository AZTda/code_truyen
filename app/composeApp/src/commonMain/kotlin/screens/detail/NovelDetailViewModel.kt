package com.truyencloud.compose.screens.detail

import com.truyencloud.shared.api.NovelApi
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.displayTitle
import com.truyencloud.shared.repo.ChapterRepository
import com.truyencloud.shared.repo.NovelRepository
import com.truyencloud.shared.repo.ReadTarget
import com.truyencloud.shared.repo.ReaderRepository
import com.truyencloud.shared.repo.TermMemoryRepository
import com.truyencloud.shared.repo.TranslateRepository
import com.truyencloud.shared.repo.UserRepository
import com.truyencloud.shared.repo.VocabularyRepository
import com.truyencloud.shared.tts.TtsSpeaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NovelDetailViewModel(
    private val novelRepository: NovelRepository,
    private val chapterRepository: ChapterRepository,
    private val novelApi: NovelApi,
    private val translateRepository: TranslateRepository? = null,
    /** Can de lay lai tien do doc tu may chu khi mo truyen tren mot may khac. */
    private val readerRepository: ReaderRepository? = null,
    termMemoryRepository: TermMemoryRepository? = null,
    vocabularyRepository: VocabularyRepository? = null,
    userRepository: UserRepository? = null,
    ttsSpeaker: TtsSpeaker? = null,
    private val scope: CoroutineScope = detailViewModelScope()
) {
    private val _uiState = MutableStateFlow(NovelDetailUiState())
    val uiState: StateFlow<NovelDetailUiState> = _uiState.asStateFlow()

    val studio = DetailStudioCoordinator(
        termMemoryRepository = termMemoryRepository,
        vocabularyRepository = vocabularyRepository,
        userRepository = userRepository,
        ttsSpeaker = ttsSpeaker,
        scope = scope,
        onBatchFeedback = { queuing, progress, msg ->
            _uiState.value = _uiState.value.copy(isBatchQueuing = queuing, batchProgress = progress, batchMessage = msg)
        }
    )

    private val remoteLoader = NovelDetailRemoteLoader(novelApi, novelRepository, chapterRepository)
    private val batch = BatchDownloadCoordinator(
        chapterRepository = chapterRepository,
        translateRepository = translateRepository,
        scope = scope,
        isLoggedIn = { TruyenCloudApi.authToken.isNotBlank() },
        update = { transform -> _uiState.value = transform(_uiState.value) },
        novelApi = novelApi
    )

    private val library = NovelLibraryActions(
        novelRepository = novelRepository,
        scope = scope,
        update = { transform -> _uiState.value = transform(_uiState.value) }
    )

    private var loadNovelJob: Job? = null
    private var currentNovelId = ""

    /** Metadata nguồn mới nhất (máy chủ > dữ liệu truyền vào). */
    private var sourceNovel: Novel? = null

    /** Hàng trong tủ sách (null = không ở trong tủ). Nguồn sự thật cho tiến độ / kệ / yêu thích. */
    private var storedNovel: Novel? = null

    /** Theo dõi Room (hàng trong tủ + cờ chương); chỉ chạy khi màn hình đang hiển thị. */
    private val observers = NovelDetailObservers(
        scope = scope,
        novelRepository = novelRepository,
        chapterRepository = chapterRepository,
        onStored = { id, stored ->
            if (id == currentNovelId) {
                storedNovel = stored
                _uiState.value = _uiState.value.copy(libraryLoaded = true)
                publishNovel()
            }
        },
        onFlags = { id, flags ->
            if (id == currentNovelId) {
                _uiState.value = _uiState.value.copy(chapterFlags = ChapterFlagIndex.build(flags))
                refreshReadState(id)
            }
        }
    )

    // ── Vòng đời màn hình ────────────────────────────────────────────────

    /** Màn hình hiện lại (vd. quay về từ trình đọc): bật lại theo dõi và nạp trạng thái đọc mới nhất. */
    fun onScreenShown() {
        if (currentNovelId.isBlank()) return
        observers.watch(currentNovelId)
        refreshReadState(currentNovelId)
    }

    /** Màn hình rời đi (mở trình đọc, về Home): ngừng theo dõi Room cho đỡ tốn. */
    fun onScreenHidden() = observers.pause()

    /** Cờ "đã đọc", vị trí cuộn và tên chương tiếng Việt đổi khi đọc/dịch: nạp lại danh sách nhẹ và cập nhật vào mục lục. */
    private fun refreshReadState(novelId: String) {
        scope.launch {
            try {
                val local = chapterRepository.getChapters(novelId).associateBy { it.chapterNumber }
                if (currentNovelId != novelId || local.isEmpty()) return@launch
                val merged = _uiState.value.chapters.map { ch ->
                    local[ch.chapterNumber]?.let {
                        ch.copy(
                            title = it.title.ifBlank { ch.title },
                            titleVi = it.titleVi.ifBlank { ch.titleVi },
                            isRead = it.isRead,
                            scrollPosition = it.scrollPosition
                        )
                    } ?: ch
                }
                _uiState.value = _uiState.value.copy(chapters = merged)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    // ── Nạp truyện ───────────────────────────────────────────────────────

    fun loadNovel(novel: Novel) {
        loadNovelJob?.cancel()
        val sameNovel = novel.id == currentNovelId && _uiState.value.novel != null
        if (!sameNovel) {
            batch.cancel()
            currentNovelId = novel.id
            sourceNovel = novel
            storedNovel = null
            _uiState.value = NovelDetailUiState(novel = novel, isLoading = true)
        } else {
            _uiState.value = _uiState.value.copy(
                isLoading = _uiState.value.chapters.isEmpty(),
                errorMessage = null,
                isOfflineMode = false
            )
        }
        observers.watch(novel.id)
        studio.bindNovel(novel.id, novel.displayTitle)

        // 1. Cache-First: nạp mục lục nhẹ từ SQLite ngay lập tức
        if (novel.id.isNotBlank()) {
            scope.launch {
                try {
                    val local = chapterRepository.getChapters(novel.id)
                    if (local.isNotEmpty() && currentNovelId == novel.id && _uiState.value.chapters.isEmpty()) {
                        _uiState.value = _uiState.value.copy(chapters = local, isLoading = false)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }

        // 2. Chay ngam keo du lieu moi nhat tu may chu (Stale-While-Revalidate)
        loadNovelJob = scope.launch { loadRemote(novel) }
    }

    private suspend fun loadRemote(novel: Novel) {
        try {
            val loaded = remoteLoader.load(novel, sourceNovel ?: novel)
            val remoteId = loaded.remote.id
            // Neu nguoi dung da chuyen sang xem truyen khac trong luc cho mang thi bo qua
            if (currentNovelId != novel.id && currentNovelId != remoteId) return

            // Máy chủ trả id chuẩn khác id đã dùng để mở: chuyển hàng trong tủ (nếu có) sang id chuẩn.
            if (remoteId != novel.id) {
                val rekeyed = try {
                    novelRepository.migrateNovelId(novel.id, remoteId)
                    novelRepository.getNovelById(remoteId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                currentNovelId = remoteId
                storedNovel = rekeyed // đọc sẵn để khỏi nhấp nháy "chưa có trong tủ" trước lần phát kế của Room
                observers.watch(remoteId)
            }

            sourceNovel = loaded.source
            studio.bindNovel(remoteId, loaded.source.displayTitle)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                chapters = loaded.chapters,
                serverTranslatedCount = loaded.remote.translated_chapters,
                serverTranslatedChapters = loaded.remote.translated_chapter_numbers,
                isOvernightEnabled = loaded.remote.overnight_enabled,
                isOfflineMode = false,
                errorMessage = null
            )
            publishNovel()

            scope.launch {
                try {
                    remoteLoader.persist(loaded)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                syncProgressFromServer(loaded.source)
                if (storedNovel != null || loaded.remote.overnight_enabled) {
                    batch.autoPullMissingTranslated(
                        remoteId,
                        loaded.remote.translated_chapter_numbers,
                        _uiState.value.downloadedChapterNumbers,
                        anchorChapter = (_uiState.value.novel?.lastReadChapter ?: 1).coerceAtLeast(1)
                    )
                }
                batch.watchOvernight(
                    novelId = remoteId,
                    enabled = loaded.remote.overnight_enabled,
                    anchorChapter = { (_uiState.value.novel?.lastReadChapter ?: 1).coerceAtLeast(1) },
                    localReady = { _uiState.value.downloadedChapterNumbers }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (currentNovelId != novel.id) return
            showOfflineOrError(novel, e)
        }
    }

    private suspend fun showOfflineOrError(novel: Novel, e: Exception) {
        val local = try { chapterRepository.getChapters(novel.id) } catch (c: CancellationException) { throw c } catch (_: Exception) { emptyList() }
        _uiState.value = _uiState.value.afterRemoteFailure(local, e)
    }

    fun retryLoad() {
        val n = _uiState.value.novel ?: return
        loadNovel(n)
    }

    private suspend fun syncProgressFromServer(novel: Novel) {
        val repo = readerRepository ?: return
        val restored = repo.pullProgressFromServer(novel.id, novel) ?: return
        if (currentNovelId != novel.id) return
        _uiState.value = _uiState.value.copy(restoredProgressMessage = "Đã lấy lại chỗ đang đọc từ máy chủ — chương $restored.")
    }

    fun dismissRestoredProgressMessage() { _uiState.value = _uiState.value.copy(restoredProgressMessage = null) }
    fun dismissCheckUpdateMessage() { _uiState.value = _uiState.value.copy(checkUpdateMessage = null) }

    private fun publishNovel() {
        val source = sourceNovel ?: return
        val stored = storedNovel
        _uiState.value = _uiState.value.copy(
            novel = composeDisplayNovel(source, stored),
            isInLibrary = stored != null,
            isFavorite = stored?.isFavorite == true
        )
    }

    fun readTarget(): Chapter? {
        val state = _uiState.value
        val novel = state.novel ?: return null
        return ReadTarget.resolve(novel, state.chapters)
    }

    fun addToLibrary() { _uiState.value.novel?.let(library::add) }
    fun toggleFavorite() { _uiState.value.novel?.let { library.toggleFavorite(it, wasInLibrary = _uiState.value.isInLibrary) } }
    fun moveToShelf(shelf: String) { _uiState.value.novel?.let { library.moveToShelf(it.id, shelf) } }
    fun askRemoveFromLibrary() { _uiState.value = _uiState.value.copy(showRemoveConfirm = true) }
    fun dismissRemoveConfirm() { _uiState.value = _uiState.value.copy(showRemoveConfirm = false) }
    fun removeFromLibrary(deleteOfflineData: Boolean) {
        val novel = _uiState.value.novel ?: return
        _uiState.value = _uiState.value.copy(showRemoveConfirm = false)
        library.remove(novel.id, deleteOfflineData)
    }
    fun dismissLibraryMessage() { _uiState.value = _uiState.value.copy(libraryMessage = null) }

    fun openBatchDownloadDialog() {
        _uiState.value = _uiState.value.copy(showBatchDownloadDialog = true, batchMessage = null, batchRequiresLogin = TruyenCloudApi.authToken.isBlank())
    }
    fun closeBatchDownloadDialog() { _uiState.value = _uiState.value.copy(showBatchDownloadDialog = false, batchMessage = null) }
    fun startBatchDownload(fromChapter: Int, toChapter: Int) { _uiState.value.novel?.let { batch.start(it.id, fromChapter, toChapter) } }
    fun startOvernightTranslation(fromChapter: Int, toChapter: Int) {
        _uiState.value.novel?.let { n ->
            batch.startOvernight(n.id, fromChapter, toChapter)
            batch.watchOvernight(
                novelId = n.id,
                enabled = true,
                anchorChapter = { (_uiState.value.novel?.lastReadChapter ?: 1).coerceAtLeast(1) },
                localReady = { _uiState.value.downloadedChapterNumbers }
            )
        }
    }
    fun stopOvernightTranslation() { _uiState.value.novel?.let { batch.stopOvernight(it.id) } }

    fun syncAllServerTranslated() {
        val state = _uiState.value
        val novel = state.novel ?: return
        batch.syncAllTranslated(novel.id, state.serverTranslatedChapters, state.downloadedChapterNumbers)
    }

    fun exportEpub() {
        val novel = _uiState.value.novel ?: return
        batch.exportEpub(novel.id)
    }

    // ── Quét chương mới ──────────────────────────────────────────────────

    fun checkForUpdates() {
        val novel = _uiState.value.novel ?: return
        scope.launch {
            _uiState.value = _uiState.value.copy(isCheckingUpdates = true, checkUpdateMessage = null)
            try {
                novelApi.checkUpdates(novel.id)
                loadNovel(novel)
                _uiState.value = _uiState.value.copy(
                    isCheckingUpdates = false,
                    checkUpdateMessage = "Đã quét xong, mục lục đã cập nhật từ nguồn web."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isCheckingUpdates = false,
                    checkUpdateMessage = "Lỗi kiểm tra: ${e.message ?: "Không thể kết nối máy chủ."}"
                )
            }
        }
    }
}
