package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.data.Bookmark
import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.reader.ReaderScrollRestore
import com.truyencloud.shared.repo.BookmarkRepository
import com.truyencloud.shared.repo.ChapterPrefetchCoordinator
import com.truyencloud.shared.repo.ChapterRepository
import com.truyencloud.shared.repo.ChapterUpdateChecker
import com.truyencloud.shared.repo.ReaderRepository
import com.truyencloud.shared.repo.TermMemoryRepository
import com.truyencloud.shared.repo.TranslateRepository
import com.truyencloud.shared.repo.UserRepository
import com.truyencloud.shared.repo.VocabularyRepository
import com.truyencloud.shared.tts.AmbientSoundType
import com.truyencloud.shared.tts.NoOpTtsSpeaker
import com.truyencloud.shared.tts.SleepTimerMode
import com.truyencloud.shared.tts.TtsSpeaker
import com.truyencloud.shared.tts.TtsVoiceCatalogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ReaderViewModel(
    private val chapterRepository: ChapterRepository,
    private val translateRepository: TranslateRepository,
    private val readerRepository: ReaderRepository,
    private val vocabularyRepository: VocabularyRepository? = null,
    private val ttsSpeaker: TtsSpeaker = NoOpTtsSpeaker(),
    private val userRepository: UserRepository? = null,
    private val termMemoryRepository: TermMemoryRepository? = null,
    private val bookmarkRepository: BookmarkRepository? = null,
    /** Dùng cho nút "Bật thông báo chương mới" ở thẻ hết truyện; null thì ẩn nút đó. */
    private val updateChecker: ChapterUpdateChecker? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    // Không cho một lần ghi nào xoá trắng chữ của CÙNG chương đang hiển thị (xem GuardedReaderState)
    private val _uiState: MutableStateFlow<ReaderUiState> = GuardedReaderState(MutableStateFlow(ReaderUiState()))
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    /** Vị trí cuộn (0..1) gần nhất của chương đang mở — để giữ đúng chỗ đọc khi nội dung được thay tại chỗ. */
    private var lastScrollFraction = 0f

    // ttsAutoNextChapter duoc coordinator kiem tra truoc khi goi lambda nay (tat -> ket thuc phien o cuoi chuong)
    val ttsCoordinator = ReaderTtsCoordinator(ttsSpeaker, _uiState, scope, userRepository, readerRepository) {
        if (_uiState.value.isTtsActive) advanceAfterTts()
    }

    val translationCoordinator = ReaderTranslationCoordinator(
        translateRepository, chapterRepository, _uiState, scope,
        scrollFraction = { lastScrollFraction },
        onContentUpdated = ::onContentUpdated
    )

    val englishCoordinator = ReaderEnglishCoordinator(vocabularyRepository, translateRepository, ttsSpeaker, _uiState, scope) { ttsCoordinator.rebuildTtsSegments() }
    val annotationCoordinator = ReaderAnnotationCoordinator(bookmarkRepository, readerRepository, _uiState, scope)
    val companionCoordinator = ReaderCompanionCoordinator(
        _uiState, scope,
        scrollFraction = { lastScrollFraction },
        accountId = { userRepository?.profile?.value?.id.orEmpty() }
    )
    internal val recapCoordinator = ReaderRecapCoordinator(chapterRepository, _uiState, scope)
    internal val loreCoordinator = ReaderLoreCoordinator(chapterRepository, termMemoryRepository, _uiState, scope)
    internal val glossaryCoordinator = ReaderGlossaryCoordinator(termMemoryRepository, _uiState, scope) { translationCoordinator.triggerRetranslation() }
    // Tải trước N+1..N+5 và (đã đăng nhập) dịch trước N+1, N+2 ở làn nền — chạy trong scope riêng, không chặn lượt tải chương đang đọc
    val prefetchCoordinator = ChapterPrefetchCoordinator(chapterRepository, translateRepository)
    val settingsCoordinator = ReaderSettingsCoordinator(userRepository, _uiState, scope)

    private val upgrader = ReaderProvisionalUpgrader(
        translateRepository, _uiState, scope, scrollFraction = { lastScrollFraction }, onSwapped = { ttsCoordinator.rebuildTtsSegments() }
    )
    private val chapterHooks: ReaderChapterHooks = ReaderChapterHooks(
        onSwitching = {
            lastScrollFraction = 0f
            englishCoordinator.onChapterChanged()
        },
        onReady = { novelId, number, startTts, hasParagraphs ->
            // Đầu tiên: lần nhảy từ dấu trang ghi đè vị trí đã lưu TRƯỚC khi TTS chọn đoạn bắt đầu
            bookmarkCoordinator.onChapterReady(novelId, number, hasParagraphs)
            navigator.recordCurrentHistory(startTts || _uiState.value.isTtsActive)
            ttsCoordinator.rebuildTtsSegments()
            if (startTts && hasParagraphs) ttsCoordinator.startTtsAfterChapterLoad() else if (startTts) ttsCoordinator.onChapterUnspeakable()
            annotationCoordinator.loadBookmarks(novelId, number)
            loreCoordinator.refreshGlossarySize()
            val s = _uiState.value
            if (hasParagraphs && s.isProgressiveMode) englishCoordinator.weaveProgressiveChapter()
            else if (hasParagraphs && (s.isEnglishMode || s.isBilingualMode)) englishCoordinator.adaptChapterToEnglish()
            prefetchCoordinator.triggerPrefetch(novelId, number, s.novel?.totalChapters ?: 0)
        },
        onContentChanged = ::onContentUpdated,
        // Mở truyện khác: lượt dịch lại / cào lại của truyện cũ không được áp vào truyện mới (truyện nào cũng mở ở chương 1)
        onNovelChanged = { translationCoordinator.cancelForNovelChange() }
    )
    internal val loader = ReaderChapterLoader(chapterRepository, translateRepository, readerRepository, _uiState, scope, chapterHooks, upgrader)
    internal val navigator = ReaderChapterNavigator(loader, chapterRepository, _uiState, scope, chapterHooks, upgrader)
    internal val endOfBook = ReaderEndOfBookCoordinator(chapterRepository, _uiState, scope, updateChecker, prefetchCoordinator::onTocExtended)
    private val findCoordinator = ReaderFindCoordinator(_uiState, ::jumpToParagraph)
    internal val toc = ReaderTocCoordinator(chapterRepository, _uiState, scope)
    internal val bookmarkCoordinator: ReaderBookmarkCoordinator = ReaderBookmarkCoordinator(loader, annotationCoordinator, bookmarkRepository, _uiState, scope)

    init {
        settingsCoordinator.setup(); ttsCoordinator.setup()
        ReaderReadingTimer(readerRepository, uiState, scope).start()
        vocabularyRepository?.let { repo ->
            scope.launch { repo.getDueWords().collect { _uiState.value = _uiState.value.copy(dueWords = it) } }
            scope.launch { repo.getAllWords().collect { _uiState.value = _uiState.value.copy(allWords = it) } }
            scope.launch { repo.getWordCount().collect { _uiState.value = _uiState.value.copy(allSavedWordsCount = it) } }
        }
    }

    fun init(novel: Novel, chapter: Chapter, initialChapters: List<Chapter> = emptyList(), autoStartTts: Boolean = false, initialBilingualMode: Boolean = false) {
        companionCoordinator.onReaderOpened(novel.id)
        // Mở trình đọc: tải lại danh mục giọng AI (máy chủ có thể vừa đổi mô hình; tối đa mỗi phút một lần)
        TtsVoiceCatalogRepository.shared.refreshAsync(scope, com.truyencloud.shared.api.TruyenCloudApi.baseUrl)
        navigator.prepareOpen(novel, chapter, initialChapters, autoStartTts, initialBilingualMode)
        loader.load(novel.id, chapter.chapterNumber, autoStartTts = autoStartTts)
        loadAllChapters(novel.id)
    }

    private fun loadAllChapters(novelId: String) {
        scope.launch {
            try {
                val list = chapterRepository.getChapters(novelId)
                val s = _uiState.value
                if (s.novel?.id != novelId) return@launch
                _uiState.value = s.copy(
                    chapters = list,
                    lastChapterNumber = maxOf(s.lastChapterNumber, list.maxOfOrNull { it.chapterNumber } ?: 0)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Mục lục chỉ phục vụ danh sách chương; thiếu thì mở mục lục nhanh vẫn hiện những chương đã biết
            }
        }
    }

    /** Nội dung của chương đang mở vừa đổi: đang nghe thì đọc lại từ đầu bản mới, không thì chỉ dựng lại đoạn đọc. */
    private fun onContentUpdated(paras: List<String>) {
        if (_uiState.value.isTtsActive && paras.isNotEmpty()) ttsCoordinator.restartWithNewContent()
        else ttsCoordinator.rebuildTtsSegments()
    }

    /**
     * TTS đọc hết chương. Chương hiện tại chưa có nội dung đọc được (dịch hỏng...) thì thử lại chương đó chứ không nhảy
     * qua; ngược lại sang chương kế và tính là đã đọc xong chương vừa nghe.
     */
    private fun advanceAfterTts() {
        val s = _uiState.value
        if (s.currentChapter != null && s.paragraphs.isEmpty()) loader.retry()
        else navigator.next(autoStartTts = true, finishedReading = true)
    }

    fun loadChapter(novelId: String, chapterNumber: Int, autoStartTts: Boolean = false) =
        loader.load(novelId, chapterNumber, autoStartTts)

    fun setReaderVisible(visible: Boolean) { _uiState.value = _uiState.value.copy(isReaderVisible = visible) }
    fun onScrollRestored() = annotationCoordinator.onScrollRestored()

    /** Báo vị trí cuộn của chương [chapterKey]; bỏ qua nếu ViewModel đã sang chương khác (vị trí đó là của chương cũ). */
    fun onScrollPositionChanged(chapterKey: String, position: Float) {
        if (chapterKey == _uiState.value.chapterKey) onScrollPositionChanged(position)
    }
    fun onScrollPositionChanged(position: Float) {
        lastScrollFraction = position
        ttsCoordinator.noteScrollPosition(position)
        annotationCoordinator.onScrollPositionChanged(position)
    }
    fun flushReadingProgressImmediately() {
        annotationCoordinator.flushReadingProgressImmediately()
        ttsCoordinator.flushPosition()
    }
    fun adjustBrightnessDelta(delta: Float) {
        annotationCoordinator.adjustBrightnessDelta(delta)
        settingsCoordinator.persistBrightnessSoon()
    }
    fun setReaderBrightness(brightness: Float) {
        annotationCoordinator.setReaderBrightness(brightness)
        settingsCoordinator.persistBrightnessSoon()
    }
    fun toggleAutoScroll() = annotationCoordinator.toggleAutoScroll()
    fun setAutoScrollPaused(paused: Boolean) = annotationCoordinator.setAutoScrollPaused(paused)
    fun cycleAutoScrollSpeed() = annotationCoordinator.cycleAutoScrollSpeed()
    fun stopAutoScroll() = annotationCoordinator.stopAutoScroll()
    fun loadBookmarks(novelId: String, ch: Int) = annotationCoordinator.loadBookmarks(novelId, ch)
    fun openBookmarksSheet() = annotationCoordinator.openBookmarksSheet()
    fun closeBookmarksSheet() = annotationCoordinator.closeBookmarksSheet()
    fun openAddBookmarkDialog(idx: Int, t: String, bm: Bookmark? = null) = annotationCoordinator.openAddBookmarkDialog(idx, t, bm)
    fun closeAddBookmarkDialog() = annotationCoordinator.closeAddBookmarkDialog()
    fun saveBookmark(color: String, note: String) = annotationCoordinator.saveBookmark(color, note)
    fun deleteBookmark(bookmark: Bookmark) = bookmarkCoordinator.delete(bookmark)
    fun exportCurrentBookmarks() = bookmarkCoordinator.export()
    /** Nhảy tới dấu trang (kể cả ở chương khác): tải chương rồi chỉ nhảy khi đoạn văn của chương đích đã sẵn sàng. */
    fun jumpToBookmark(chapterNumber: Int, paragraphIndex: Int) = bookmarkCoordinator.jumpTo(chapterNumber, paragraphIndex)
    fun openQuoteCard(text: String) = annotationCoordinator.openQuoteCard(text)
    fun closeQuoteCard() = annotationCoordinator.closeQuoteCard()

    fun toggleBars() { _uiState.value = _uiState.value.copy(showBars = !_uiState.value.showBars) }
    fun openFindSheet() = findCoordinator.open()
    fun closeFindSheet() = findCoordinator.close()
    fun onFindQueryChange(query: String) = findCoordinator.onQueryChange(query)
    fun findPrevious() = findCoordinator.previous()
    fun findNext() = findCoordinator.next()
    fun toggleOriginal() = navigator.toggleOriginal(lastScrollFraction)

    fun startTts() = ttsCoordinator.startTts()
    fun toggleTtsPlayPause() = ttsCoordinator.toggleTtsPlayPause()
    fun nextTtsParagraph() = ttsCoordinator.nextTtsParagraph()
    fun prevTtsParagraph() = ttsCoordinator.prevTtsParagraph()
    fun jumpTtsParagraph(index: Int) = ttsCoordinator.jumpTtsParagraph(index)
    fun cycleTtsSpeed() = ttsCoordinator.settings.cycleSpeed()
    fun closeTts() = ttsCoordinator.closeTts()
    fun openAudiobookStudio() = ttsCoordinator.settings.openStudio()
    fun closeAudiobookStudio() = ttsCoordinator.settings.closeStudio()
    fun setSleepTimer(mode: SleepTimerMode) = ttsCoordinator.settings.setSleepTimer(mode)
    fun setAmbientSound(type: AmbientSoundType) = ttsCoordinator.settings.setAmbientSound(type)
    fun setAmbientVolume(volume: Float) = ttsCoordinator.settings.setAmbientVolume(volume)
    fun setTtsVoiceProfile(profileId: String) = ttsCoordinator.setTtsVoiceProfile(profileId)
    fun setTtsSpeed(speed: Float) = ttsCoordinator.settings.setSpeed(speed)
    fun setTtsOnlineMode(enabled: Boolean) = ttsCoordinator.settings.setOnlineMode(enabled)

    fun triggerTranslation() = translationCoordinator.triggerTranslation()
    fun openRetranslateDialog() = translationCoordinator.openRetranslateDialog()
    fun closeRetranslateDialog() = translationCoordinator.closeRetranslateDialog()
    fun onRetranslatePromptChange(prompt: String) = translationCoordinator.onRetranslatePromptChange(prompt)
    fun triggerRetranslation(customPrompt: String? = null, targetLanguage: String = "vi", englishLevel: String = _uiState.value.englishLevel) =
        translationCoordinator.triggerRetranslation(customPrompt, targetLanguage, englishLevel)
    fun triggerRecrawl(autoTranslate: Boolean = true, customPrompt: String? = null) = translationCoordinator.triggerRecrawl(autoTranslate, customPrompt)
    fun openChapterRecap() = recapCoordinator.open()
    fun closeChapterRecap() = recapCoordinator.close()
    fun openLoreLookup(initialTerm: String = "", context: String = "") = loreCoordinator.open(initialTerm, context)
    fun closeLoreLookup() = loreCoordinator.close()
    fun onLoreTargetChange(value: String) = loreCoordinator.onTargetChange(value)
    fun saveLoreTermToGlossary(pinned: Boolean = false) = loreCoordinator.save(pinned)
    fun lookupLore(term: String) = loreCoordinator.lookup(term)
    fun retryLore() = loreCoordinator.retry()
    fun openGlossary() = glossaryCoordinator.open()
    fun closeGlossary() = glossaryCoordinator.close()
    /** "Xem bản gốc để chọn tên" (bảng tra cứu): đóng bảng và chuyển sang bản gốc để người đọc chạm giữ đoạn chữ Hán. */
    fun readOriginalToPickTerm() {
        loreCoordinator.close()
        if (!_uiState.value.showOriginal) toggleOriginal()
    }

    fun onSelectEnglishWord(word: String, contextSentence: String, charOffset: Int = -1) = englishCoordinator.onSelectEnglishWord(word, contextSentence, charOffset)
    fun retryWordLookup() = englishCoordinator.retryWordLookup()
    fun saveCurrentWord() = englishCoordinator.saveCurrentWord()
    fun speakWord(word: String) = englishCoordinator.speakWord(word)
    fun analyzeSentence(sentence: String) = englishCoordinator.analyzeSentence(sentence)
    fun retrySentenceAnalysis() = englishCoordinator.retrySentenceAnalysis()
    fun closeSentenceAnalysis() = englishCoordinator.closeSentenceAnalysis()
    fun closeEnglishWordSheet() = englishCoordinator.closeEnglishWordSheet()
    fun openVocabDeck() = englishCoordinator.openVocabDeck()
    fun closeVocabDeck() = englishCoordinator.closeVocabDeck()
    fun reviewWord(wordId: String, rating: Int, reviewMode: String = "recognition", responseMs: Long = 0L) = englishCoordinator.reviewWord(wordId, rating, reviewMode, responseMs)
    fun cycleLanguageMode() = englishCoordinator.cycleLanguageMode()
    fun toggleProgressiveMode(ratio: Double = _uiState.value.progressiveRatio) = englishCoordinator.toggleProgressiveMode(ratio)
    fun setProgressiveRatio(ratio: Double) = englishCoordinator.setProgressiveRatio(ratio)
    fun weaveProgressiveChapter(ratio: Double = _uiState.value.progressiveRatio) = englishCoordinator.weaveProgressiveChapter(ratio)
    fun toggleBilingualMode() = englishCoordinator.toggleBilingualMode()
    fun toggleEnglishMode() = englishCoordinator.toggleEnglishMode()
    fun setEnglishLevel(level: String) = englishCoordinator.setEnglishLevel(level)
    fun adaptChapterToEnglish() = englishCoordinator.adaptChapterToEnglish()
    fun dismissEnglishNotice() = englishCoordinator.dismissEnglishNotice()
    fun openComprehension() = englishCoordinator.openComprehension()
    fun closeComprehension() = englishCoordinator.closeComprehension()
    fun answerComprehensionQuestion(questionId: String, optionIndex: Int) = englishCoordinator.answerComprehensionQuestion(questionId, optionIndex)
    fun submitComprehension() = englishCoordinator.submitComprehension()
    fun retryComprehension() = englishCoordinator.retryComprehension()
    fun loadComprehensionQuestions() = englishCoordinator.loadComprehensionQuestions()
    fun openCompanionChat(defaultQuery: String? = null, character: String? = null) = companionCoordinator.openCompanionChat(defaultQuery, character)
    fun closeCompanionChat() = companionCoordinator.closeCompanionChat()

    fun toggleQuickToc() = toc.toggle()

    fun selectChapterFromToc(chapterNumber: Int) {
        val s = _uiState.value
        val novel = s.novel ?: return
        if (chapterNumber == s.currentChapter?.chapterNumber && s.paragraphs.isNotEmpty()) return
        loader.load(novel.id, chapterNumber, autoStartTts = s.isTtsActive)
    }
    fun nextChapter(autoStartTts: Boolean = _uiState.value.isTtsActive) = navigator.next(autoStartTts)
    fun prevChapter(autoStartTts: Boolean = _uiState.value.isTtsActive) = navigator.prev(autoStartTts)

    // Tải chương / dịch / hết truyện: giao diện gọi qua các hàm này
    fun retryLoadChapter() = loader.retry()
    fun dismissChapterError() = loader.dismissChapterError()
    fun cancelChapterLoad() = loader.cancelLoad()
    fun cancelTranslation() { loader.cancelTranslation(); translationCoordinator.cancelTranslation() }
    /** [Đọc bản gốc]: bỏ luôn lượt dịch đang chờ (nếu không, dịch xong sẽ giành lại màn hình từ bản gốc người đọc đã chọn). */
    fun readOriginal() {
        if (!_uiState.value.currentChapter?.originalContent.isNullOrBlank()) translationCoordinator.cancelTranslation()
        navigator.readOriginal()
    }
    /** Nút "Dịch lại bằng AI" của banner bản dịch tạm: dịch lại bắt buộc (force = true). */
    fun upgradeProvisionalTranslation() = translationCoordinator.triggerRetranslation()
    fun dismissTranslationError() { _uiState.value = _uiState.value.copy(translationError = null, translationErrorKind = null) }
    fun dismissToast() { _uiState.value = _uiState.value.copy(toastMessage = null) }
    fun onEndReached(chapterKey: String) { if (chapterKey == _uiState.value.chapterKey) navigator.noteEndReached() }
    fun checkForNewChapters() = endOfBook.checkForNewChapters()
    fun enableNewChapterAlerts() = endOfBook.enableAlerts()
    fun dismissEndOfBook() = endOfBook.dismiss()
    fun setKeepScreenOn(enabled: Boolean) = settingsCoordinator.setKeepScreenOn(enabled)
    fun setVolumeKeyPaging(enabled: Boolean) = settingsCoordinator.setVolumeKeyPaging(enabled)
    fun setFontSize(size: Int) = settingsCoordinator.setFontSize(size)
    fun setThemeMode(mode: String) = settingsCoordinator.setThemeMode(mode)
    fun toggleSettingsSheet() = settingsCoordinator.toggleSettingsSheet()
    fun setFontFamilyType(type: String) = settingsCoordinator.setFontFamilyType(type)
    fun setLineSpacingFactor(factor: Float) = settingsCoordinator.setLineSpacingFactor(factor)
    fun togglePaginatedMode(paginated: Boolean? = null) {
        val before = _uiState.value.isPaginatedMode
        settingsCoordinator.togglePaginatedMode(paginated)
        // Đổi giữa cuộn dọc và lật trang: mở lại đúng chỗ đang đọc (hai chế độ cùng quy về tỉ lệ 0..1 của chương).
        // Đầu chương cũng phải đặt lại (giá trị rất nhỏ > 0), kẻo chế độ kia giữ vị trí cũ của chính nó.
        if (_uiState.value.isPaginatedMode != before) {
            _uiState.value = _uiState.value.copy(restoreScrollPosition = lastScrollFraction.coerceAtLeast(ReaderScrollRestore.MIN_PLACE))
        }
    }
    fun setPageMargin(margin: Int) = settingsCoordinator.setPageMargin(margin)
    fun applySettings(themeMode: String, fontSize: Int, fontFamilyType: String, lineSpacingFactor: Float, isPaginatedMode: Boolean, pageMargin: Int, isBilingualMode: Boolean, isEnglishMode: Boolean, isProgressiveMode: Boolean, englishLevel: String, progressiveRatio: Double) =
        settingsCoordinator.applySettings(themeMode, fontSize, fontFamilyType, lineSpacingFactor, isPaginatedMode, pageMargin, isBilingualMode, isEnglishMode, isProgressiveMode, englishLevel, progressiveRatio)
    /**
     * Nhảy tới đoạn [index] của chương ĐANG HIỂN THỊ (kết quả Tìm trong chương lấy từ chính văn bản này). Nhảy sang chương khác là việc
     * của [jumpToBookmark]. Bản cũ hoãn lần nhảy tới khi chương đang tải xong, nên một chương kế tiếp tải chậm nhận luôn vị trí khớp
     * của chương CŨ và mở ở đó thay vì ở chỗ đã đọc.
     */
    fun jumpToParagraph(index: Int) {
        val total = _uiState.value.displayParagraphs.size
        if (total > 0) {
            _uiState.value = _uiState.value.copy(
                restoreScrollPosition = ReaderScrollRestore.positionForParagraph(index, total),
                scrollToParagraphIndex = index
            )
        }
    }
    fun onParagraphScrolled() { _uiState.value = _uiState.value.copy(scrollToParagraphIndex = null, restoreScrollPosition = 0f) }
}
