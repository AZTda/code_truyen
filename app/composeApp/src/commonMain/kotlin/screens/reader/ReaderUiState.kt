package com.truyencloud.compose.screens.reader

import com.truyencloud.compose.theme.ThemeMode
import com.truyencloud.shared.api.ChapterRecapResponse
import com.truyencloud.shared.api.ComprehensionQuestionItem
import com.truyencloud.shared.api.DEFAULT_COMPANION_NAME
import com.truyencloud.shared.api.LoreExplainResponse
import com.truyencloud.shared.api.ProgressiveGlossaryItem
import com.truyencloud.shared.api.SentenceTranslateResponse
import com.truyencloud.shared.api.WordExplainResponse
import com.truyencloud.shared.data.Bookmark
import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.ChapterFlags
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.reader.BookmarkScope
import com.truyencloud.shared.reader.ChapterNavigation
import com.truyencloud.shared.reader.PendingJump
import com.truyencloud.shared.reader.TranslationFailureKind
import com.truyencloud.shared.text.ParagraphSplitter
import com.truyencloud.shared.tts.AmbientSoundType
import com.truyencloud.shared.tts.TtsReadiness
import com.truyencloud.shared.tts.TtsVoiceCatalog
import com.truyencloud.shared.tts.SleepTimerMode
import com.truyencloud.shared.tts.TtsVoiceProfile

data class ReaderUiState(
    val novel: Novel? = null,
    val currentChapter: Chapter? = null,
    val chapters: List<Chapter> = emptyList(),
    val paragraphs: List<String> = emptyList(),
    val showOriginal: Boolean = false,
    val isTranslating: Boolean = false,
    val translatingChapterNumber: Int? = null,
    val translationError: String? = null,
    val isLoadingChapter: Boolean = false,
    val chapterError: String? = null,
    // Chương đang được tải (khác chương đang hiển thị khi bấm Sau/Trước/Mục lục) — để "Thử lại" tải đúng chương đó
    val targetChapterNumber: Int? = null,
    // Nhóm lỗi dịch (đăng nhập / máy chủ bận / mất mạng) kèm câu thân thiện ở translationError
    val translationErrorKind: TranslationFailureKind? = null,
    // Bản dịch đang xem là bản TẠM dự phòng của Google: hiện banner "Dịch lại bằng AI"
    val isProvisionalTranslation: Boolean = false,
    // Thông báo ngắn tự tắt (giữ nguyên nội dung đang đọc khi việc nền thất bại)
    val toastMessage: String? = null,
    // Hết truyện / chương mới
    val lastChapterNumber: Int = 0,
    val showEndOfBook: Boolean = false,
    val isCheckingNewChapters: Boolean = false,
    val newChaptersNotice: String? = null,
    val newChapterAlertsAvailable: Boolean = false,
    val newChapterAlertsEnabled: Boolean = false,
    // Cờ nội dung từng chương cho mục lục nhanh (chỉ nạp khi mở mục lục, tránh kéo nguyên văn 5000 chương)
    val tocFlags: Map<Int, ChapterFlags> = emptyMap(),
    val tocServerTranslated: Set<Int> = emptySet(),
    val showBars: Boolean = false,
    val showSettingsSheet: Boolean = false,
    val showQuickToc: Boolean = false,
    val showFindSheet: Boolean = false,
    val findQuery: String = "",
    val findMatches: List<Int> = emptyList(),
    val findCursor: Int = 0,
    val showAudiobookStudio: Boolean = false,
    val isTtsActive: Boolean = false,
    val ttsIsPlaying: Boolean = false,
    val ttsCurrentParagraph: Int = 0,
    val ttsSpeed: Float = 1.0f,
    val sleepTimerMode: SleepTimerMode = SleepTimerMode.OFF,
    val sleepTimerRemainingSeconds: Int? = null,
    val ambientSoundType: AmbientSoundType = AmbientSoundType.NONE,
    val ambientSoundVolume: Float = 0.25f,
    val ttsVoiceTone: String = "Mỹ Duyên",
    val ttsVoiceProfileId: String = "my_duyen",
    val ttsOnlineMode: Boolean = true,
    // Cai dat nghe lay tu ho so nguoi dung: tu cuon theo doan dang doc / tu sang chuong sau
    val ttsAutoScroll: Boolean = true,
    val ttsAutoNextChapter: Boolean = true,
    // Thong bao loi/tam dung cua phien doc (khong tai duoc chuong, chuong chua dich, bo may doc hong...)
    val ttsError: String? = null,
    // Giọng AI đang được chọn nhưng đang đọc bằng giọng của điện thoại (máy chủ lỗi) — thanh nghe đọc báo cho người nghe
    val ttsDeviceFallback: Boolean = false,
    // Bấm Phát nhưng giọng AI của chương chưa dựng đủ: đang chờ rồi tự phát (dòng "Đang chuẩn bị giọng đọc… 35%")
    val ttsPreparingLabel: String? = null,
    // Máy chủ đã dựng sẵn giọng AI của chương đang mở tới đâu (lần hỏi /v1/tts/prerender gần nhất; null = chưa biết)
    val ttsReadiness: TtsReadiness? = null,
    val voiceProfiles: List<TtsVoiceProfile> = TtsVoiceCatalog.voices,
    val fontSize: Int = 18,
    val lineHeight: Float = 1.8f,
    val fontFamilyType: String = "serif",
    val lineSpacingFactor: Float = 1.8f,
    val themeMode: String = ThemeMode.DEFAULT_STORED,
    // Giữ màn hình sáng khi đọc (mặc định bật) / lật trang bằng phím âm lượng (mặc định tắt)
    val keepScreenOn: Boolean = true,
    val volumeKeyPaging: Boolean = false,
    val showRetranslateDialog: Boolean = false,
    val retranslatePrompt: String = "",
    val showChapterRecap: Boolean = false,
    val chapterRecapData: ChapterRecapResponse? = null,
    val isLoadingRecap: Boolean = false,
    val showLoreLookup: Boolean = false,
    val loreLookupData: LoreExplainResponse? = null,
    val isLoadingLore: Boolean = false,
    val showQuoteCard: Boolean = false,
    val selectedQuoteText: String = "",
    // English Immersion & Vocabulary Studio
    val isEnglishMode: Boolean = false,
    val isBilingualMode: Boolean = false,
    val isProgressiveMode: Boolean = false,
    val progressiveRatio: Double = 0.5,
    val progressiveParagraphs: List<String> = emptyList(),
    val isWeavingProgressive: Boolean = false,
    val progressiveGlossary: List<ProgressiveGlossaryItem> = emptyList(),
    val progressiveReplacedCount: Int = 0,
    // Reading Comprehension Questions
    val showComprehensionSheet: Boolean = false,
    val comprehensionQuestions: List<ComprehensionQuestionItem> = emptyList(),
    val isLoadingComprehension: Boolean = false,
    val comprehensionAnswers: Map<String, Int> = emptyMap(),
    val isComprehensionSubmitted: Boolean = false,
    val comprehensionScore: Int = 0,
    val englishParagraphs: List<String> = emptyList(),
    // Song ngữ căn hàng: phần tiếng Việt của các cặp [EN]/[VI] máy chủ trả về (cùng chỉ số với englishParagraphs)
    val bilingualViParagraphs: List<String> = emptyList(),
    val englishLevel: String = "c1",
    val isAdaptingEnglish: Boolean = false,
    val englishNotice: String? = null,
    val showEnglishWordSheet: Boolean = false,
    val selectedEnglishWord: String = "",
    val selectedWordSentence: String = "",
    val selectedWordOffset: Int = -1,
    val englishWordData: WordExplainResponse? = null,
    val isExplainingWord: Boolean = false,
    val wordLookupError: String? = null,
    val isWordSaved: Boolean = false,
    /** Khác null khi thẻ đã lưu là một DẠNG KHÁC của từ đang xem (vd. thẻ "run" khi xem "running"). */
    val savedWordForm: String? = null,
    val showSentenceAnalysis: Boolean = false,
    val analyzedSentence: String = "",
    val sentenceAnalysis: SentenceTranslateResponse? = null,
    val isAnalyzingSentence: Boolean = false,
    val sentenceAnalysisError: String? = null,
    val showVocabDeck: Boolean = false,
    val dueWords: List<SavedWord> = emptyList(),
    val allWords: List<SavedWord> = emptyList(),
    val allSavedWordsCount: Int = 0,
    val isPaginatedMode: Boolean = false,
    val pageMargin: Int = 16,
    val restoreScrollPosition: Float = 0f,
    val scrollToParagraphIndex: Int? = null,
    val isReaderVisible: Boolean = false,
    val ttsSegments: List<ParagraphSplitter.TtsSegment> = emptyList(),
    val isLoreTermSaved: Boolean = false,
    val loreTargetInput: String = "",
    val isSavingLoreTerm: Boolean = false,
    val loreSaveMessage: String? = null,
    val glossarySize: Int = 0,
    val bookmarks: List<Bookmark> = emptyList(),
    val showBookmarksSheet: Boolean = false,
    val showAddBookmarkDialog: Boolean = false,
    val bookmarkTargetParagraphIndex: Int = -1,
    val bookmarkTargetText: String = "",
    val activeBookmarkForEdit: Bookmark? = null,
    val readerBrightness: Float = 1.0f,
    val showBrightnessHud: Boolean = false,
    val brightnessHudPercent: Int = 100,
    val isAutoScrolling: Boolean = false,
    val isAutoScrollPaused: Boolean = false,
    val autoScrollSpeed: Float = 1.0f,
    // AI Book Companion Chat
    val showCompanionChat: Boolean = false,
    val companionChatHistory: List<com.truyencloud.shared.api.CompanionChatMessage> = emptyList(),
    val companionSelectedCharacter: String = DEFAULT_COMPANION_NAME,
    // Đang chờ chữ đầu tiên của câu trả lời (hiện "AI đang suy ngẫm...")
    val isCompanionTyping: Boolean = false,
    // Câu trả lời đang được stream, tới tận lúc luồng thật sự kết thúc: khoá ô nhập (isCompanionTyping tắt từ mảnh chữ đầu tiên)
    val isCompanionStreaming: Boolean = false,
    val companionSuggestedQuestions: List<String> = emptyList(),
    val companionError: String? = null,
    // Câu hỏi của lượt vừa thất bại (nút "Thử lại" gửi lại đúng câu này)
    val companionFailedQuestion: String? = null,
    // Tóm tắt chương: lỗi, hoặc "không có gì để nhắc lại" (chương đầu / chưa có chương trước được tóm tắt)
    val recapError: String? = null,
    val recapNothingToRecap: Boolean = false,
    // Tra cứu thuật ngữ: từ đang tra, đoạn văn chứa nó (ngữ cảnh gửi AI + nơi chọn tên) và lỗi
    val loreTerm: String = "",
    val loreContext: String = "",
    val loreError: String? = null,
    val comprehensionError: String? = null,
    val glossary: GlossaryUiState = GlossaryUiState(),
    // Dấu trang cả truyện: phạm vi danh sách, chỗ cần nhảy tới sau khi tải chương, dấu trang vừa xoá (để hoàn tác)
    val bookmarkScope: BookmarkScope = BookmarkScope.CHAPTER,
    val pendingJump: PendingJump? = null,
    val deletedBookmark: Bookmark? = null
) {
    /** Danh sách đoạn đang hiển thị (hoà trộn / Anh / song ngữ nếu đã có, ngược lại là bản Việt). */
    val displayParagraphs: List<String>
        get() = when {
            isProgressiveMode && progressiveParagraphs.isNotEmpty() -> progressiveParagraphs
            (isBilingualMode || isEnglishMode) && englishParagraphs.isNotEmpty() -> englishParagraphs
            else -> paragraphs
        }

    /** Khoá chương đang mở (truyện + số chương); null khi chưa mở chương nào. */
    val chapterKey: String?
        get() {
            val ch = currentChapter ?: return null
            // Ưu tiên id truyện của chính trình đọc (mọi lượt tải dùng id này) thay vì novelId của hàng chương, vốn có thể là id chuẩn của máy chủ
            return ChapterNavigation.chapterKey(novel?.id ?: ch.novelId, ch.chapterNumber)
        }

    val chapterBookmarks: List<Bookmark>
        get() = bookmarks.filter { it.chapterNumber == (currentChapter?.chapterNumber ?: 1) }

    /**
     * Có bảng / hộp thoại nào đang che phía trước cuốn sách không (mọi cờ show*, bảng thuật ngữ, tìm trong chương, dấu trang...).
     * Phím âm lượng lật trang và các cử chỉ phía sau phải tắt khi true. Thêm bảng mới thì thêm cờ của nó vào đây.
     */
    val anyOverlayOpen: Boolean
        get() = showSettingsSheet || showQuickToc || showFindSheet || showRetranslateDialog || showEndOfBook ||
            showBookmarksSheet || showAddBookmarkDialog || showChapterRecap || showComprehensionSheet || showLoreLookup ||
            glossary.visible || showCompanionChat || showQuoteCard || showAudiobookStudio ||
            showEnglishWordSheet || showSentenceAnalysis || showVocabDeck

    val activeBookmarkSnippet: String
        get() = bookmarkTargetText
}
