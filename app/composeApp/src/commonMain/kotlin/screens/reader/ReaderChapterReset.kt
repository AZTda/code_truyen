package com.truyencloud.compose.screens.reader

/**
 * Mọi trạng thái gắn với MỘT chương cụ thể. Phải xoá khi sang chương khác — bản cũ không xoá nên sau khi đổi chương
 * chế độ Anh / hoà trộn / đọc hiểu vẫn hiện bản của chương TRƯỚC (các nút bật chế độ chỉ tải khi danh sách còn rỗng).
 * Chỉ gọi khi đổi số chương (hoặc đổi truyện), KHÔNG gọi khi làm mới lại cùng một chương.
 */
internal fun ReaderUiState.withChapterScopedStateCleared(): ReaderUiState = copy(
    // Bản Anh / hoà trộn / song ngữ của chương trước
    englishParagraphs = emptyList(),
    bilingualViParagraphs = emptyList(),
    progressiveParagraphs = emptyList(),
    progressiveGlossary = emptyList(),
    progressiveReplacedCount = 0,
    isWeavingProgressive = false,
    isAdaptingEnglish = false,
    englishNotice = null,
    // Câu hỏi đọc hiểu và điểm
    showComprehensionSheet = false,
    comprehensionQuestions = emptyList(),
    comprehensionAnswers = emptyMap(),
    isComprehensionSubmitted = false,
    comprehensionScore = 0,
    isLoadingComprehension = false,
    comprehensionError = null,
    // Tóm tắt chương / tra cứu thuật ngữ (kể cả lỗi, từ đang tra và đoạn văn ngữ cảnh — của chương trước)
    showChapterRecap = false,
    chapterRecapData = null,
    isLoadingRecap = false,
    recapError = null,
    recapNothingToRecap = false,
    showLoreLookup = false,
    loreLookupData = null,
    isLoadingLore = false,
    loreTerm = "",
    loreContext = "",
    loreError = null,
    isLoreTermSaved = false,
    loreTargetInput = "",
    loreSaveMessage = null,
    isSavingLoreTerm = false,
    // Trợ lý: câu gợi ý, lỗi và câu hỏi vừa thất bại gắn với nội dung chương trước. Lịch sử trò chuyện và vai đã chọn thuộc về
    // CẢ TRUYỆN (đổi truyện / tài khoản mới bỏ — xem ReaderCompanionCoordinator.onReaderOpened). isCompanionStreaming cũng KHÔNG
    // xoá ở đây: lượt stream còn đang chạy, cờ đó chỉ tắt khi luồng thật sự kết thúc.
    companionSuggestedQuestions = emptyList(),
    companionError = null,
    companionFailedQuestion = null,
    isCompanionTyping = false,
    // Tìm trong chương
    showFindSheet = false,
    findQuery = "",
    findMatches = emptyList(),
    findCursor = 0,
    scrollToParagraphIndex = null,
    // Banner của chương trước
    isProvisionalTranslation = false,
    translationError = null,
    translationErrorKind = null,
    showEndOfBook = false,
    newChaptersNotice = null
)

/** Tách đoạn theo dòng trống kép (định dạng máy chủ trả về). */
internal fun splitReaderParagraphs(content: String): List<String> =
    com.truyencloud.shared.text.ParagraphSplitter.splitReaderParagraphs(content)

/** Bản "dịch" thực ra là câu từ chối / mẫu giữ chỗ của mô hình chứ không phải nội dung truyện. */
internal fun isJunkTranslation(text: String?): Boolean =
    !text.isNullOrBlank() && (
        text.contains("[Bản dịch TruyenCloud AI") || text.contains("Đoạn 3: Hành trình tiếp tục") ||
            text.contains("The prompt could not be submitted") || text.contains("violate Google")
        )
