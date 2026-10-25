package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.reader.ReaderTranslationFailure
import com.truyencloud.shared.repo.TranslateRepository
import com.truyencloud.shared.repo.VocabularyRepository
import com.truyencloud.shared.tts.TtsSpeaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ReaderEnglishCoordinator(
    private val vocabularyRepository: VocabularyRepository?,
    private val translateRepository: TranslateRepository,
    private val ttsSpeaker: TtsSpeaker,
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope,
    private val onContentUpdated: () -> Unit
) {
    private val wordSaver = ReaderWordSaveCoordinator(vocabularyRepository, uiStateFlow, scope)

    // Việc nền của chương đang mở: huỷ khi sang chương khác kẻo kết quả chương trước ghi đè chương mới
    private var adaptJob: Job? = null
    private var weaveJob: Job? = null
    private val bilingualAdapter = ReaderBilingualAdapter(translateRepository, uiStateFlow, scope, onContentUpdated)
    fun onChapterChanged() { adaptJob?.cancel(); weaveJob?.cancel(); bilingualAdapter.cancel() }

    fun onSelectEnglishWord(word: String, contextSentence: String, charOffset: Int = -1) {
        val cleanWord = word.lowercase().trim().trim('"', '\'', '.', ',', '!', '?', ';', ':', '(', ')', '[', ']')
        if (cleanWord.isBlank()) return

        uiStateFlow.value = uiStateFlow.value.copy(
            showEnglishWordSheet = true,
            selectedEnglishWord = cleanWord,
            selectedWordSentence = contextSentence,
            selectedWordOffset = charOffset,
            isExplainingWord = true,
            englishWordData = null,
            wordLookupError = null,
            isWordSaved = false,
            savedWordForm = null
        )

        scope.launch {
            val existing = vocabularyRepository?.getWord(cleanWord)
            // Không ghi đè nếu người đọc đã chạm Lưu trong lúc chờ DB, hoặc đã chuyển sang từ khác.
            if (uiStateFlow.value.selectedEnglishWord == cleanWord) {
                val otherForm = existing?.word?.takeIf { !it.equals(cleanWord, ignoreCase = true) }
                uiStateFlow.value = uiStateFlow.value.copy(
                    isWordSaved = uiStateFlow.value.isWordSaved || existing != null,
                    savedWordForm = otherForm
                )
            }

            try {
                val book = uiStateFlow.value.novel?.titleVi?.ifBlank { uiStateFlow.value.novel?.title } ?: "TruyenCloud"
                val res = vocabularyRepository?.explainWord(word = cleanWord, context = contextSentence, bookTitle = book)
                // Kết quả tra của từ trước không được đè lên bảng của từ người đọc vừa chọn.
                if (uiStateFlow.value.selectedEnglishWord != cleanWord) return@launch
                if (res == null) {
                    uiStateFlow.value = uiStateFlow.value.copy(isExplainingWord = false, wordLookupError = "Chưa kết nối được sổ từ vựng.")
                } else if (res.isFallback || !res.hasContent) {
                    uiStateFlow.value = uiStateFlow.value.copy(
                        isExplainingWord = false,
                        englishWordData = null,
                        wordLookupError = res.error.ifBlank { "Máy chủ chưa phân tích được từ này. Vui lòng thử lại." }
                    )
                } else {
                    uiStateFlow.value = uiStateFlow.value.copy(isExplainingWord = false, englishWordData = res, wordLookupError = null)
                    // Đã bấm Lưu từ trước khi tra xong thì điền phần từ điển vào thẻ.
                    wordSaver.onLookupArrived(cleanWord, res)
                }
            } catch (e: Exception) {
                uiStateFlow.value = uiStateFlow.value.copy(isExplainingWord = false, englishWordData = null, wordLookupError = e.message ?: "Không kết nối được máy chủ.")
            }
        }
    }

    fun retryWordLookup() {
        val s = uiStateFlow.value
        if (s.selectedEnglishWord.isNotBlank()) onSelectEnglishWord(s.selectedEnglishWord, s.selectedWordSentence, s.selectedWordOffset)
    }

    /** Lưu NGAY (không chờ AI tra từ); chạm lần nữa để bỏ lưu. Tên giữ nguyên vì ReaderViewModel / ReaderModals gọi. */
    fun saveCurrentWord() = wordSaver.toggleSave()

    fun speakWord(word: String) = ttsSpeaker.speakWord(word, "en")
    fun closeEnglishWordSheet() { uiStateFlow.value = uiStateFlow.value.copy(showEnglishWordSheet = false) }

    fun analyzeSentence(sentence: String) {
        val clean = sentence.trim()
        if (clean.isBlank()) return
        uiStateFlow.value = uiStateFlow.value.copy(
            showSentenceAnalysis = true,
            analyzedSentence = clean,
            sentenceAnalysis = null,
            isAnalyzingSentence = true,
            sentenceAnalysisError = null
        )
        scope.launch {
            try {
                val res = vocabularyRepository?.translateSentence(clean)
                if (res == null || res.isFallback) {
                    uiStateFlow.value = uiStateFlow.value.copy(
                        isAnalyzingSentence = false,
                        sentenceAnalysis = null,
                        sentenceAnalysisError = res?.error?.ifBlank { null } ?: "Máy chủ chưa phân tích được câu này. Vui lòng thử lại."
                    )
                } else {
                    uiStateFlow.value = uiStateFlow.value.copy(isAnalyzingSentence = false, sentenceAnalysis = res, sentenceAnalysisError = null)
                }
            } catch (e: Exception) {
                uiStateFlow.value = uiStateFlow.value.copy(isAnalyzingSentence = false, sentenceAnalysis = null, sentenceAnalysisError = e.message ?: "Không kết nối được máy chủ.")
            }
        }
    }

    fun retrySentenceAnalysis() = analyzeSentence(uiStateFlow.value.analyzedSentence)
    fun closeSentenceAnalysis() { uiStateFlow.value = uiStateFlow.value.copy(showSentenceAnalysis = false) }

    fun openVocabDeck() { uiStateFlow.value = uiStateFlow.value.copy(showVocabDeck = true) }
    fun closeVocabDeck() { uiStateFlow.value = uiStateFlow.value.copy(showVocabDeck = false) }

    fun reviewWord(wordId: String, rating: Int, reviewMode: String = "recognition", responseMs: Long = 0L) {
        scope.launch { vocabularyRepository?.reviewWord(wordId, rating, reviewMode, responseMs) }
    }

    fun cycleLanguageMode() {
        val s = uiStateFlow.value
        when {
            !s.isProgressiveMode && !s.isBilingualMode && !s.isEnglishMode -> {
                uiStateFlow.value = s.copy(isProgressiveMode = false, isBilingualMode = false, isEnglishMode = true)
                if (s.englishParagraphs.isEmpty()) adaptChapterToEnglish()
            }
            s.isEnglishMode -> {
                uiStateFlow.value = s.copy(isProgressiveMode = false, isBilingualMode = true, isEnglishMode = false)
                if (s.bilingualViParagraphs.isEmpty()) adaptChapterToEnglish() // cần cặp [EN]/[VI], không dùng bản Anh riêng
            }
            s.isBilingualMode -> {
                uiStateFlow.value = s.copy(isProgressiveMode = true, isBilingualMode = false, isEnglishMode = false)
                if (s.progressiveParagraphs.isEmpty()) weaveProgressiveChapter()
            }
            else -> {
                uiStateFlow.value = s.copy(isProgressiveMode = false, isBilingualMode = false, isEnglishMode = false, englishNotice = null)
            }
        }
    }

    fun toggleProgressiveMode(ratio: Double = uiStateFlow.value.progressiveRatio) {
        val newMode = !uiStateFlow.value.isProgressiveMode
        uiStateFlow.value = uiStateFlow.value.copy(
            isProgressiveMode = newMode,
            progressiveRatio = ratio,
            isEnglishMode = if (newMode) false else uiStateFlow.value.isEnglishMode,
            isBilingualMode = if (newMode) false else uiStateFlow.value.isBilingualMode
        )
        if (newMode && uiStateFlow.value.progressiveParagraphs.isEmpty()) {
            weaveProgressiveChapter(ratio)
        }
    }

    fun setProgressiveRatio(ratio: Double) {
        uiStateFlow.value = uiStateFlow.value.copy(progressiveRatio = ratio)
        if (uiStateFlow.value.isProgressiveMode) weaveProgressiveChapter(ratio)
    }

    fun weaveProgressiveChapter(ratio: Double = uiStateFlow.value.progressiveRatio) {
        val raw = uiStateFlow.value.paragraphs
        if (raw.isEmpty()) return
        val repo = vocabularyRepository ?: return
        weaveJob?.cancel()
        weaveJob = scope.launch {
            uiStateFlow.value = uiStateFlow.value.copy(isWeavingProgressive = true)
            try {
                val res = repo.weaveProgressiveChapter(raw, ratio)
                uiStateFlow.value = uiStateFlow.value.copy(
                    isWeavingProgressive = false,
                    progressiveParagraphs = res.paragraphs,
                    progressiveGlossary = res.glossary,
                    progressiveReplacedCount = res.replacedCount
                )
                onContentUpdated()
            } catch (_: Exception) {
                uiStateFlow.value = uiStateFlow.value.copy(isWeavingProgressive = false)
            }
        }
    }

    fun toggleBilingualMode() {
        val newMode = !uiStateFlow.value.isBilingualMode
        uiStateFlow.value = uiStateFlow.value.copy(
            isBilingualMode = newMode,
            isProgressiveMode = if (newMode) false else uiStateFlow.value.isProgressiveMode,
            isEnglishMode = if (newMode) false else uiStateFlow.value.isEnglishMode
        )
        if (newMode && uiStateFlow.value.bilingualViParagraphs.isEmpty()) adaptChapterToEnglish() // cặp [EN]/[VI] căn hàng
    }

    fun toggleEnglishMode() {
        val newMode = !uiStateFlow.value.isEnglishMode
        uiStateFlow.value = uiStateFlow.value.copy(
            isEnglishMode = newMode,
            isProgressiveMode = if (newMode) false else uiStateFlow.value.isProgressiveMode,
            isBilingualMode = if (newMode) false else uiStateFlow.value.isBilingualMode
        )
        if (newMode && uiStateFlow.value.englishParagraphs.isEmpty()) adaptChapterToEnglish()
    }

    fun setEnglishLevel(level: String) {
        val cleanLevel = level.lowercase().trim()
        if (cleanLevel !in listOf("b1", "b2", "c1", "c2")) return
        if (uiStateFlow.value.englishLevel == cleanLevel) return
        uiStateFlow.value = uiStateFlow.value.copy(
            englishLevel = cleanLevel,
            bilingualViParagraphs = if (uiStateFlow.value.isBilingualMode) uiStateFlow.value.bilingualViParagraphs else emptyList()
        )
        if (uiStateFlow.value.isEnglishMode || uiStateFlow.value.isBilingualMode) {
            adaptChapterToEnglish()
        }
    }

    fun adaptChapterToEnglish() {
        if (uiStateFlow.value.isBilingualMode) { bilingualAdapter.adapt(); return } // đích "bilingual": cặp căn hàng
        val ch = uiStateFlow.value.currentChapter ?: return
        val novel = uiStateFlow.value.novel ?: return
        val currentParas = uiStateFlow.value.paragraphs
        val level = uiStateFlow.value.englishLevel
        val targetChapterNumber = ch.chapterNumber
        val novelTitle = novel.titleVi.ifBlank { novel.title }

        uiStateFlow.value = uiStateFlow.value.copy(isAdaptingEnglish = true, englishNotice = null)
        adaptJob?.cancel()
        adaptJob = scope.launch {
            try {
                val cached = translateRepository.getCachedEnglish(novel.id, targetChapterNumber, level)
                if (!cached.isNullOrBlank()) {
                    val paras = extractEnglishParagraphs(cached)
                    if (paras.isNotEmpty()) {
                        if (uiStateFlow.value.currentChapter?.chapterNumber == targetChapterNumber) {
                            uiStateFlow.value = uiStateFlow.value.copy(isAdaptingEnglish = false, englishParagraphs = paras)
                            onContentUpdated()
                            triggerGrammarExtractionAsync(scope, paras, novel.id, targetChapterNumber, novelTitle)
                        }
                        return@launch
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}

            val sourceContent = ch.originalContent
            if (!sourceContent.isNullOrBlank()) {
                try {
                    val enTrans = translateRepository.translateChapter(
                        novelId = novel.id,
                        chapterNumber = targetChapterNumber,
                        content = sourceContent,
                        force = false,
                        targetLanguage = "en",
                        englishLevel = level
                    )
                    if (enTrans.isNotBlank() && !isVietnameseOnlyText(enTrans)) {
                        val enParas = extractEnglishParagraphs(enTrans)
                        if (enParas.isNotEmpty()) {
                            if (uiStateFlow.value.currentChapter?.chapterNumber == targetChapterNumber) {
                                uiStateFlow.value = uiStateFlow.value.copy(isAdaptingEnglish = false, englishParagraphs = enParas)
                                onContentUpdated()
                                triggerGrammarExtractionAsync(scope, enParas, novel.id, targetChapterNumber, novelTitle)
                            }
                            return@launch
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }

            if (currentParas.isEmpty()) {
                if (uiStateFlow.value.currentChapter?.chapterNumber == targetChapterNumber) {
                    uiStateFlow.value = uiStateFlow.value.copy(isAdaptingEnglish = false, englishNotice = "Chưa có nội dung để chuyển sang tiếng Anh.")
                }
                return@launch
            }
            try {
                val title = ch.titleVi.ifBlank { ch.title }
                val res = vocabularyRepository?.adaptChapter(title = title, paragraphs = currentParas, level = level)
                if (uiStateFlow.value.currentChapter?.chapterNumber == targetChapterNumber) {
                    if (res != null && res.paragraphsEn.isNotEmpty()) {
                        if (res.isFallback) {
                            uiStateFlow.value = uiStateFlow.value.copy(
                                isAdaptingEnglish = false,
                                isEnglishMode = false,
                                isBilingualMode = false,
                                isProgressiveMode = false,
                                englishParagraphs = emptyList(),
                                englishNotice = "Chưa chuyển thể được sang tiếng Anh. Đang giữ bản tiếng Việt."
                            )
                            onContentUpdated()
                            return@launch
                        }
                        val notice = when {
                            res.isPartial -> "Một số đoạn chưa chuyển thể được (${res.failedChunks} lỗi) và đang giữ bản tiếng Việt."
                            sourceContent.isNullOrBlank() -> "Bản tiếng Anh này được chuyển thể từ bản dịch tiếng Việt nên có thể kém sát nghĩa hơn bản gốc."
                            else -> null
                        }
                        uiStateFlow.value = uiStateFlow.value.copy(isAdaptingEnglish = false, englishParagraphs = res.paragraphsEn, englishNotice = notice)
                        onContentUpdated()
                        triggerGrammarExtractionAsync(scope, res.paragraphsEn, novel.id, targetChapterNumber, novelTitle)
                    } else {
                        uiStateFlow.value = uiStateFlow.value.copy(isAdaptingEnglish = false, englishNotice = "Không chuyển thể được chương này sang tiếng Anh.")
                    }
                }
            } catch (e: CancellationException) {
                // Lượt bị thay bằng lượt mới (đổi trình độ, đổi chương...): không để nó tắt vòng chờ hay hiện thông báo của lượt kế
                throw e
            } catch (e: Exception) {
                if (uiStateFlow.value.currentChapter?.chapterNumber == targetChapterNumber) {
                    // englishNotice được hiện cho người đọc: câu thân thiện, không phải thân phản hồi HTTP thô
                    uiStateFlow.value = uiStateFlow.value.copy(
                        isAdaptingEnglish = false,
                        englishNotice = "${ReaderTranslationFailure.messageFor(e)}. Đang giữ bản tiếng Việt."
                    )
                }
            }
        }
    }

    private val comprehensionCoordinator = ReaderComprehensionCoordinator(uiStateFlow, scope)

    fun dismissEnglishNotice() { uiStateFlow.value = uiStateFlow.value.copy(englishNotice = null) }
    fun openComprehension() = comprehensionCoordinator.openComprehension()
    fun closeComprehension() = comprehensionCoordinator.closeComprehension()
    fun answerComprehensionQuestion(questionId: String, optionIndex: Int) = comprehensionCoordinator.answerComprehensionQuestion(questionId, optionIndex)
    fun submitComprehension() = comprehensionCoordinator.submitComprehension()
    fun retryComprehension() = comprehensionCoordinator.retryComprehension()
    fun loadComprehensionQuestions() = comprehensionCoordinator.loadComprehensionQuestions()
}
