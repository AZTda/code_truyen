package com.truyencloud.compose.screens.vocabulary

import com.truyencloud.shared.api.ContextStoryResponse
import com.truyencloud.shared.api.FlashcardArtResponse
import com.truyencloud.shared.api.LifeScenariosResponse
import com.truyencloud.shared.api.QuizQuestion
import com.truyencloud.shared.api.SentenceEvaluateResponse
import com.truyencloud.shared.data.DailyReviewStat
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.notify.ReviewReminderConfig
import com.truyencloud.shared.practice.PracticeItem
import com.truyencloud.shared.practice.PracticeMode
import com.truyencloud.shared.practice.PracticeResult
import com.truyencloud.shared.repo.StudyConfig
import com.truyencloud.shared.repo.StudySnapshot
import com.truyencloud.shared.srs.FsrsScheduler.Rating

/** Nhãn ngắn (<= 9 ký tự) để vừa 4 ô của SegmentedControl trên màn 360dp, kể cả khi chữ phóng 1,3. */
enum class VocabMode(val title: String) {
    NOTEBOOK("Sổ từ"),
    FLASHCARDS("Ôn thẻ"),
    PRACTICE("Luyện tập"),
    REAL_LIFE("Ứng dụng")
}

enum class ContextGroupMode(val title: String) {
    ALL("Tất cả"),
    BY_BOOK("Theo truyện"),
    BY_DOMAIN("Chủ đề")
}

enum class VocabFilter(val label: String) {
    ALL("Tất cả"),
    DUE("Cần ôn"),
    LEVEL_B1("B1"),
    LEVEL_B2("B2"),
    LEVEL_C1("C1"),
    LEVEL_C2("C2"),
    MASTERED("Đã thuộc"),
    LEECH("Khó nhớ")
}

/** Màu/ý nghĩa của thanh thông báo đồng bộ. */
enum class SyncTone { SUCCESS, ERROR, INFO }

data class VocabularyUiState(
    val allWords: List<SavedWord> = emptyList(),
    val dueWords: List<SavedWord> = emptyList(),
    val newWords: List<SavedWord> = emptyList(),
    val filteredWords: List<SavedWord> = emptyList(),
    val booksMap: Map<String, List<SavedWord>> = emptyMap(),
    val domainsMap: Map<String, List<SavedWord>> = emptyMap(),
    val activeMode: VocabMode = VocabMode.NOTEBOOK,
    val contextGroupMode: ContextGroupMode = ContextGroupMode.ALL,
    val selectedFilter: VocabFilter = VocabFilter.ALL,
    val selectedBook: String? = null,
    val selectedDomain: String? = null,
    val searchQuery: String = "",

    // Hàng đợi học đã tính sẵn — không sắp xếp lại trong lúc vẽ giao diện
    val studyDeck: List<SavedWord> = emptyList(),

    // Vi truyện ngữ cảnh
    val isWeavingStory: Boolean = false,
    val contextStory: ContextStoryResponse? = null,

    // Thẻ ghi nhớ
    val cardIndex: Int = 0,
    val isCardFlipped: Boolean = false,
    val isAutoSpeak: Boolean = false,
    val isClozeRevealed: Boolean = false,
    val isShuffled: Boolean = false,
    /** Chiều ngược: mặt trước chỉ hiện nghĩa tiếng Việt + câu đục lỗ để người học chủ động nhớ lại từ tiếng Anh. */
    val isReverseCard: Boolean = false,
    /** Có thể hoàn tác lượt chấm vừa rồi trong phiên Ôn thẻ. */
    val canUndoReview: Boolean = false,
    /** Số lượt đã trả lời trong phiên hiện tại — dùng cho tiến độ "Thẻ x / y" và màn hình hoàn thành. */
    val reviewedCountSession: Int = 0,
    /**
     * Ôn nhồi (opt-in sau khi xong thẻ đến hạn): KHÔNG ghi lịch FSRS, chỉ ghi nhật ký luyện tập.
     * Người học muốn xem lại từ đã thuộc thì được, nhưng không làm lệch lịch ôn.
     */
    val isCramMode: Boolean = false,
    val cardArtCache: Map<String, FlashcardArtResponse> = emptyMap(),
    /** Nhãn khoảng cách THẬT cho từng nút, thay cho "1/2/4/10 ngày" cố định. */
    val intervalPreview: Map<Rating, String> = emptyMap(),

    // Luyện tập chủ động
    val practiceMode: PracticeMode = PracticeMode.SMART,
    val practiceItem: PracticeItem? = null,
    val practiceInput: String = "",
    val practiceResult: PracticeResult? = null,
    val practiceQueue: List<SavedWord> = emptyList(),
    val practiceIndex: Int = 0,
    val practiceCorrectCount: Int = 0,
    val practiceTotalCount: Int = 0,

    // Quiz tình huống
    val isLoadingQuiz: Boolean = false,
    val quizQuestions: List<QuizQuestion> = emptyList(),
    val quizIndex: Int = 0,
    val quizSelectedOption: Int? = null,
    val quizCorrectCount: Int = 0,
    val quizError: String? = null,

    // Ứng dụng đời sống
    val selectedWord: SavedWord? = null,
    val isLoadingScenarios: Boolean = false,
    val scenarios: LifeScenariosResponse? = null,
    val scenariosError: String? = null,
    val userSentenceInput: String = "",
    val isEvaluatingSentence: Boolean = false,
    val sentenceEvaluation: SentenceEvaluateResponse? = null,
    val evaluationError: String? = null,
    val practicedSentences: List<SentenceEvaluateResponse> = emptyList(),

    // Thống kê THẬT
    val snapshot: StudySnapshot = StudySnapshot(),
    val dailyStats: List<DailyReviewStat> = emptyList(),

    val generatingArtWords: Set<String> = emptySet(),

    // Luyện phát âm (Feature #8)
    val pronunciationWord: SavedWord? = null,

    // Đồng bộ đám mây & Xuất Anki
    val isSyncing: Boolean = false,
    val isSyncSuccess: Boolean = false,
    val syncMessage: String? = null,
    val syncTone: SyncTone = SyncTone.INFO,

    // Nhắc ôn hằng ngày & Hạn mức học
    val reminder: ReviewReminderConfig = ReviewReminderConfig(),
    val showReminderDialog: Boolean = false,
    val reminderNotice: String? = null,
    val studyConfig: StudyConfig = StudyConfig(),
    val showStudyGoalDialog: Boolean = false,
    val showAddWordDialog: Boolean = false,
    val isAddingCustomWord: Boolean = false,
    val addWordError: String? = null
) {
    /** Hết thẻ trong phiên (và không phải ôn nhồi) => hiện màn hình "Hoàn thành hôm nay". */
    val isSessionComplete: Boolean get() = studyDeck.isEmpty() && allWords.isNotEmpty()

    /** Chuỗi ngày học — lấy từ nhật ký ôn tập thật, không còn là hằng số 7. */
    val streakDays: Int get() = snapshot.streakDays
}
