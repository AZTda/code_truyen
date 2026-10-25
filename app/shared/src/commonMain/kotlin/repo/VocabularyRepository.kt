package com.truyencloud.shared.repo

import com.truyencloud.shared.api.*
import com.truyencloud.shared.data.DailyReviewStat
import com.truyencloud.shared.data.ExtraContext
import com.truyencloud.shared.data.ReviewLogDao
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.data.SavedWordDao
import com.truyencloud.shared.data.getCurrentEpochMillis
import com.truyencloud.shared.notify.ReviewReminderConfig
import com.truyencloud.shared.prefs.SettingsStore
import com.truyencloud.shared.srs.FsrsConfig
import com.truyencloud.shared.srs.FsrsScheduler
import com.truyencloud.shared.srs.Rating
import com.truyencloud.shared.srs.ReviewOutcome
import com.truyencloud.shared.srs.ReviewPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class VocabularyRepository(
    private val englishApi: EnglishApi,
    private val savedWordDao: SavedWordDao,
    private val reviewLogDao: ReviewLogDao? = null,
    private val studyConfig: StudyConfig = StudyConfig(),
    private val vocabularySyncApi: VocabularySyncRemote = VocabularySyncApi(),
    private val settingsStore: SettingsStore? = defaultSettingsStore()
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private var activeStudyConfig = StudyConfig.load(settingsStore, studyConfig)
    private val fsrsConfig get() = FsrsConfig(desiredRetention = activeStudyConfig.desiredRetention)
    private val aiStudio = VocabularyAiStudioCoordinator(englishApi, savedWordDao, json, settingsStore)
    private val wordCreator = VocabularyWordCreator(savedWordDao, json, aiStudio)
    private val reviewCoordinator get() = VocabularyReviewCoordinator(savedWordDao, reviewLogDao, fsrsConfig)
    private val maintenance = VocabularyMaintenance(savedWordDao, reviewLogDao, reviewCoordinator, englishApi)
    private val statsCalculator = VocabularyStatsCalculator(reviewLogDao, activeStudyConfig)
    private val syncCoordinator =
        VocabularySyncCoordinator(savedWordDao, reviewLogDao, vocabularySyncApi, VocabularySyncStore(savedWordDao, settingsStore))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reminders = VocabularyReminderService(savedWordDao, settingsStore)
    private val autoSync = VocabularyAutoSync(
        sync = { syncCoordinator.sync(userInitiated = false) },
        refreshReminder = { reminders.refreshContent() }
    )
    private val syncScheduler = VocabularySyncScheduler(scope) { autoSync.run() }

    init {
        // Khi mở ứng dụng: dọn dữ liệu cũ (log trùng, lemma sai, độ khó bị kẹp) và đảm bảo lịch nhắc ôn còn tồn tại.
        scope.launch {
            runCatching { maintenance.runOnce() }
            runCatching { reminders.ensureScheduled() }
        }
    }

    // -----------------------------------------------------------------------
    // Đọc dữ liệu
    // -----------------------------------------------------------------------

    fun getAllWords(): Flow<List<SavedWord>> = savedWordDao.getAllWords()

    fun getWordCount(): Flow<Int> = savedWordDao.getWordCount()

    private fun minuteTicker(): Flow<Long> = flow {
        while (true) {
            emit(getCurrentEpochMillis())
            delay(60_000L)
        }
    }

    /** Thẻ ĐÃ HỌC đang đến hạn (không gồm thẻ mới). */
    fun getDueWords(): Flow<List<SavedWord>> =
        minuteTicker().flatMapLatest { now -> savedWordDao.getDueWords(now) }

    fun getDueCount(): Flow<Int> =
        minuteTicker().flatMapLatest { now -> savedWordDao.countDue(now) }

    /**
     * MỌI thẻ chưa từng học, cũ nhất trước. Hạn mức thẻ mới mỗi ngày được áp SAU bộ lọc truyện / chủ đề, ở
     * [buildStudyQueue] — cắt ở đây thì 12 thẻ cũ nhất của cả sổ che hết thẻ của cuốn truyện đang chọn.
     */
    fun getNewWords(): Flow<List<SavedWord>> = savedWordDao.getNewWords()

    suspend fun getWord(word: String): SavedWord? {
        val clean = EnglishLemmatizer.normalizeWord(word)
        return savedWordDao.getWord(clean) ?: savedWordDao.getByLemma(EnglishLemmatizer.lemmatize(clean))
    }

    suspend fun getWordsForChapter(novelId: String, chapterNumber: Int): List<SavedWord> =
        savedWordDao.getWordsForChapter(novelId, chapterNumber)

    suspend fun getWordsForNovel(novelId: String): List<SavedWord> =
        savedWordDao.getWordsForNovel(novelId)

    fun decodeExtraContexts(word: SavedWord): List<ExtraContext> = wordCreator.decodeExtras(word.extraContextsJson)

    // -----------------------------------------------------------------------
    // Hàng đợi học trong ngày
    // -----------------------------------------------------------------------

    /**
     * Thẻ ôn đến hạn trước (quá hạn / sắp quên nhất trước) rồi mới tới thẻ mới theo hạn mức ngày — thẻ mới
     * không còn chen lên đầu hàng đợi. [snapshot] cho biết hôm nay đã ôn và đã giới thiệu bao nhiêu.
     */
    fun buildStudyQueue(
        dueWords: List<SavedWord>,
        newWords: List<SavedWord>,
        snapshot: StudySnapshot,
        activeBookTitle: String? = null
    ): List<SavedWord> = StudyQueueBuilder.build(
        dueWords = dueWords,
        newWords = newWords,
        reviewsDoneToday = snapshot.reviewsDoneToday,
        newIntroducedToday = snapshot.newIntroducedToday,
        nowEpoch = getCurrentEpochMillis(),
        config = activeStudyConfig,
        activeBookTitle = activeBookTitle
    )

    fun studyConfig(): StudyConfig = activeStudyConfig

    fun updateStudyConfig(config: StudyConfig) {
        activeStudyConfig = config
        config.save(settingsStore)
        statsCalculator.currentConfig = config
    }

    fun previewIntervals(word: SavedWord): Map<Rating, String> {
        val now = getCurrentEpochMillis()
        return FsrsScheduler.previewIntervals(word.toMemoryState(), now, fsrsConfig, fuzzKey = word.id)
            .mapValues { (_, outcome) -> FsrsScheduler.formatInterval(outcome, now) }
    }

    // -----------------------------------------------------------------------
    // Lưu / xoá từ
    // -----------------------------------------------------------------------

    suspend fun saveWord(
        word: String,
        ipaUs: String = "",
        ipaUk: String = "",
        partOfSpeech: String = "",
        cefrLevel: String = "",
        contextMeaningVi: String = "",
        englishDefinition: String = "",
        contextSentence: String = "",
        bookTitle: String = "",
        novelId: String = "",
        chapterNumber: Int = 0,
        contextCharOffset: Int = -1,
        etymology: String = "",
        collocations: List<CollocationItem> = emptyList(),
        aiExampleSentences: List<String> = emptyList()
    ): SavedWord = wordCreator.saveWord(
        word = word,
        ipaUs = ipaUs,
        ipaUk = ipaUk,
        partOfSpeech = partOfSpeech,
        cefrLevel = cefrLevel,
        contextMeaningVi = contextMeaningVi,
        englishDefinition = englishDefinition,
        contextSentence = contextSentence,
        bookTitle = bookTitle,
        novelId = novelId,
        chapterNumber = chapterNumber,
        contextCharOffset = contextCharOffset,
        etymology = etymology,
        collocations = collocations,
        aiExampleSentences = aiExampleSentences
    ).also { requestSync(SYNC_DEBOUNCE_SAVE_MS) }

    suspend fun saveWordFromExplanation(
        explanation: WordExplainResponse,
        contextSentence: String,
        bookTitle: String,
        novelId: String = "",
        chapterNumber: Int = 0,
        contextCharOffset: Int = -1
    ): SavedWord = wordCreator.saveWordFromExplanation(
        explanation = explanation,
        contextSentence = contextSentence,
        bookTitle = bookTitle,
        novelId = novelId,
        chapterNumber = chapterNumber,
        contextCharOffset = contextCharOffset
    ).also { requestSync(SYNC_DEBOUNCE_SAVE_MS) }

    /** Xoá mềm (bia mộ) để lần đồng bộ sau xoá từ này ở máy khác, và nó không "sống lại". */
    suspend fun deleteWord(word: SavedWord) {
        wordCreator.deleteWord(word)
        requestSync(SYNC_DEBOUNCE_SAVE_MS)
    }

    /** Bỏ lưu theo chữ (từ bảng tra trong lúc đọc). @return false nếu từ chưa được lưu. */
    suspend fun deleteWordByText(text: String): Boolean =
        wordCreator.deleteWordByText(text).also { if (it) requestSync(SYNC_DEBOUNCE_SAVE_MS) }

    suspend fun setSuspended(id: String, suspended: Boolean) {
        savedWordDao.setSuspended(id, suspended, getCurrentEpochMillis())
        requestSync(SYNC_DEBOUNCE_SAVE_MS)
    }

    // Ôn tập
    suspend fun reviewWordDetailed(
        word: SavedWord,
        rating: Rating,
        reviewMode: String = "recognition",
        responseMs: Long = 0L,
        policy: ReviewPolicy = ReviewPolicy.SCHEDULE_IF_DUE
    ): ReviewResult = reviewCoordinator.review(word, rating, reviewMode, responseMs, policy)
        .also { requestSync(SYNC_DEBOUNCE_REVIEW_MS) }

    suspend fun undoReview(previousWord: SavedWord, logId: Long) {
        reviewCoordinator.undoReview(previousWord, logId)
        requestSync(SYNC_DEBOUNCE_REVIEW_MS)
    }

    suspend fun reviewWord(
        word: SavedWord,
        rating: Rating,
        reviewMode: String = "recognition",
        responseMs: Long = 0L,
        policy: ReviewPolicy = ReviewPolicy.SCHEDULE_IF_DUE
    ): ReviewOutcome = reviewWordDetailed(word, rating, reviewMode, responseMs, policy).outcome

    suspend fun reviewWord(
        wordId: String,
        rating: Int,
        reviewMode: String = "recognition",
        responseMs: Long = 0L,
        policy: ReviewPolicy = ReviewPolicy.SCHEDULE_IF_DUE
    ): ReviewOutcome? = reviewWord(wordId, Rating.from(rating), reviewMode, responseMs, policy)

    suspend fun reviewWord(
        wordId: String,
        rating: Rating,
        reviewMode: String = "recognition",
        responseMs: Long = 0L,
        policy: ReviewPolicy = ReviewPolicy.SCHEDULE_IF_DUE
    ): ReviewOutcome? {
        val word = savedWordDao.getById(wordId) ?: return null
        return reviewWord(word, rating, reviewMode, responseMs, policy)
    }

    suspend fun computeStreakDays(): Int = statsCalculator.computeStreakDays()
    suspend fun computeRetention(days: Int = 30): Double = statsCalculator.computeRetention(days)
    suspend fun reviewedTodayCount(): Int = statsCalculator.reviewedTodayCount()
    fun dailyStats(days: Int = 30): Flow<List<DailyReviewStat>> = statsCalculator.dailyStats(days)
    fun studySnapshot(): Flow<StudySnapshot> = statsCalculator.studySnapshot(getAllWords(), getDueWords())

    // -----------------------------------------------------------------------
    // Ủy quyền sang AI Studio & Dịch vụ
    // -----------------------------------------------------------------------

    suspend fun explainWord(word: String, context: String? = null, bookTitle: String? = null): WordExplainResponse =
        englishApi.explainWord(word = word, context = context, bookTitle = bookTitle)
    suspend fun explainWordsBatch(words: List<String>, context: String? = null, bookTitle: String? = null): List<WordExplainResponse> =
        englishApi.explainWordsBatch(words = words, context = context, bookTitle = bookTitle)
    suspend fun translateSentence(sentence: String): SentenceTranslateResponse =
        englishApi.translateSentence(sentence = sentence)
    suspend fun adaptChapter(title: String, paragraphs: List<String>, level: String = "c1"): AdaptChapterResponse =
        englishApi.adaptChapter(title = title, paragraphs = paragraphs, level = level)
    suspend fun generateQuiz(words: List<String>) = englishApi.practiceQuiz(words)
    suspend fun getLifeScenarios(savedWord: SavedWord): LifeScenariosResponse =
        aiStudio.getLifeScenarios(savedWord)
    suspend fun evaluateSentence(word: String, sentence: String, contextType: String = "daily"): SentenceEvaluateResponse =
        aiStudio.evaluateSentence(word, sentence, contextType)
    suspend fun saveUserSentence(savedWord: SavedWord, evaluation: SentenceEvaluateResponse) =
        aiStudio.saveUserSentence(savedWord, evaluation)
    fun getPracticedSentences(savedWord: SavedWord): List<SentenceEvaluateResponse> =
        aiStudio.getPracticedSentences(savedWord)
    fun getCollocations(savedWord: SavedWord): List<CollocationItem> =
        aiStudio.getCollocations(savedWord)
    fun getAiExamples(savedWord: SavedWord): List<String> =
        aiStudio.getAiExamples(savedWord)
    suspend fun getOrGenerateFlashcardArt(savedWord: SavedWord): FlashcardArtResponse? =
        aiStudio.getOrGenerateFlashcardArt(savedWord)
    suspend fun weaveContextStory(words: List<String>, bookTitle: String = "", domain: String = ""): ContextStoryResponse =
        aiStudio.weaveContextStory(words, bookTitle, domain)
    fun getWordDomain(savedWord: SavedWord): String =
        VocabularyDomainClassifier.getWordDomain(savedWord)
    fun lemmatize(raw: String): String = EnglishLemmatizer.lemmatize(raw)
    suspend fun getMatureWordsForProgressive(minStability: Double = 14.0): List<MatureWordPayload> =
        aiStudio.getMatureWordsForProgressive(minStability)
    suspend fun weaveProgressiveChapter(
        paragraphs: List<String>,
        targetRatio: Double = 0.5
    ): ProgressiveChapterResponse = aiStudio.weaveProgressiveChapter(paragraphs, targetRatio)

    /** Chạy lúc khởi động: dọn dữ liệu cũ rồi chuyển các thẻ còn ở thuật toán cũ sang FSRS (không reset tiến độ). */
    suspend fun migrateLegacyWords(): Int = maintenance.migrateLegacyWords()

    // -----------------------------------------------------------------------
    // Đồng bộ đám mây
    // -----------------------------------------------------------------------

    /** Đồng bộ do người dùng bấm: trả kết quả đầy đủ, gồm trạng thái "Chưa đăng nhập" riêng biệt. */
    suspend fun sync(): VocabularySyncResult = syncCoordinator.sync(userInitiated = true)

    /** Giữ chữ ký cũ: thất bại (kể cả chưa đăng nhập) là `Result.failure`, không còn báo thành công giả. */
    suspend fun syncWithServer(): Result<Pair<Int, Int>> = when (val r = sync()) {
        is VocabularySyncResult.Success -> Result.success(Pair(r.wordsPulled + r.wordsPushed, r.logsPulled + r.logsPushed))
        VocabularySyncResult.NotLoggedIn -> Result.failure(IllegalStateException(VocabularySyncCoordinator.MSG_NOT_LOGGED_IN))
        is VocabularySyncResult.Failure -> Result.failure(IllegalStateException(r.message))
    }

    /**
     * Đồng bộ im lặng nếu đã đăng nhập, rồi làm mới nội dung nhắc ôn (kể cả khách — iOS chụp số thẻ vào thông báo).
     * LifecycleSyncManager gọi khi ứng dụng xuống nền / quay lại; cũng tự chạy ít giây sau lượt ôn / lưu / xoá từ cuối cùng.
     */
    suspend fun syncIfLoggedIn() = autoSync.run()

    /** Chỉ làm mới nội dung nhắc ôn, không đụng mạng — khách chưa đăng nhập vẫn cần số thẻ đến hạn mới. */
    suspend fun refreshReminderContent() = autoSync.refreshReminderOnly()

    /** Số thay đổi từ vựng chưa lên máy chủ (từ + lượt ôn): cảnh báo trước khi xoá dữ liệu / đổi tài khoản. */
    suspend fun pendingSyncCount(): Int = savedWordDao.countUnsyncedWords() + (reviewLogDao?.countUnsynced() ?: 0)

    /** Hẹn đồng bộ sau [delayMs]; một phiên ôn gồm nhiều lượt chỉ gây MỘT lần đồng bộ sau lượt cuối. */
    fun requestSync(delayMs: Long = SYNC_DEBOUNCE_REVIEW_MS) = syncScheduler.request(delayMs)

    // -----------------------------------------------------------------------
    // Nhắc ôn hằng ngày
    // -----------------------------------------------------------------------

    fun reminderConfig(): ReviewReminderConfig = reminders.config()

    /** @return false nếu bật nhắc nhưng hệ điều hành chưa cho phép hiện thông báo (giao diện cần báo cho người dùng). */
    suspend fun setReminder(config: ReviewReminderConfig): Boolean = reminders.set(config)

    companion object {
        private const val SYNC_DEBOUNCE_REVIEW_MS = 20_000L
        private const val SYNC_DEBOUNCE_SAVE_MS = 8_000L
    }
}
