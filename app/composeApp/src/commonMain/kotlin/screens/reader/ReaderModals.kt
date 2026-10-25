package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.truyencloud.shared.data.Chapter

/**
 * Tập hợp toàn bộ Modal Bottom Sheet và Hộp thoại trong màn hình đọc truyện.
 *
 * Tách riêng khỏi [ReaderScreen] nhằm tuân thủ nguyên lý Single Responsibility (SRP)
 * và khắc phục triệt để giới hạn 18,945 bytecode instructions của bộ biên dịch Android ART.
 */
@Composable
fun ReaderModals(
    viewModel: ReaderViewModel,
    uiState: ReaderUiState,
    chapter: Chapter?
) {
    // Tìm trong chương: lớp mờ nhạt để vẫn thấy chữ phía sau; thanh tìm dính đáy và nâng lên cùng bàn phím
    if (uiState.showFindSheet) {
        ReaderSheetBackdrop(onDismiss = viewModel::closeFindSheet, dim = 0.5f) {
            BottomSheetAnchor {
                ReaderFindSheet(
                    query = uiState.findQuery,
                    matchCount = uiState.findMatches.size,
                    currentMatch = uiState.findCursor,
                    onQueryChange = viewModel::onFindQueryChange,
                    onPrevious = viewModel::findPrevious,
                    onNext = viewModel::findNext,
                    onDismiss = viewModel::closeFindSheet
                )
            }
        }
    }

    // 1. Settings Sheet Backdrop & Sheet
    if (uiState.showSettingsSheet) {
        ReaderSheetBackdrop(onDismiss = viewModel::toggleSettingsSheet) {
            BottomSheetAnchor {
                Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
                    ReaderSettingsSheet(
                        fontSize = uiState.fontSize,
                        themeMode = uiState.themeMode,
                        fontFamilyType = uiState.fontFamilyType,
                        lineSpacingFactor = uiState.lineSpacingFactor,
                        isPaginatedMode = uiState.isPaginatedMode,
                        pageMargin = uiState.pageMargin,
                        isBilingualMode = uiState.isBilingualMode,
                        isEnglishMode = uiState.isEnglishMode,
                        isProgressiveMode = uiState.isProgressiveMode,
                        currentEnglishLevel = uiState.englishLevel,
                        progressiveRatio = uiState.progressiveRatio,
                        dueWordsCount = uiState.dueWords.size,
                        allWordsCount = uiState.allSavedWordsCount,
                        keepScreenOn = uiState.keepScreenOn,
                        volumeKeyPaging = uiState.volumeKeyPaging,
                        onKeepScreenOnChange = viewModel::setKeepScreenOn,
                        onVolumeKeyPagingChange = viewModel::setVolumeKeyPaging,
                        onFontSizeChange = viewModel::setFontSize,
                        onThemeChange = viewModel::setThemeMode,
                        onFontFamilyChange = viewModel::setFontFamilyType,
                        onLineSpacingChange = viewModel::setLineSpacingFactor,
                        onTogglePaginatedMode = viewModel::togglePaginatedMode,
                        onPageMarginChange = viewModel::setPageMargin,
                        onToggleBilingualMode = viewModel::toggleBilingualMode,
                        onToggleEnglishMode = viewModel::toggleEnglishMode,
                        onToggleProgressiveMode = viewModel::toggleProgressiveMode,
                        onSelectProgressiveRatio = viewModel::setProgressiveRatio,
                        onSelectEnglishLevel = viewModel::setEnglishLevel,
                        onApplySettings = viewModel::applySettings,
                        onOpenVocabDeck = viewModel::openVocabDeck,
                        onOpenCompanionChat = { viewModel.openCompanionChat() },
                        onOpenRecap = viewModel::openChapterRecap,
                        onOpenComprehension = viewModel::openComprehension,
                        onOpenLoreLookup = { viewModel.openLoreLookup() },
                        onOpenGlossary = viewModel::openGlossary,
                        onRetranslate = viewModel::openRetranslateDialog,
                        onDismiss = viewModel::toggleSettingsSheet
                    )
                }
            }
        }
    }

    // 2. AI Modals (Recap, Comprehension, Lore Lookup, Companion Chat)
    ReaderAiModals(viewModel, uiState, chapter)

    // 5. Quote Card Sheet
    if (uiState.showQuoteCard) {
        ReaderSheetBackdrop(onDismiss = viewModel::closeQuoteCard) {
            BottomSheetAnchor {
                QuoteCardSheet(
                    quoteText = uiState.selectedQuoteText,
                    novelTitle = uiState.novel?.let { n -> n.titleVi.ifBlank { n.title } } ?: "Truyện",
                    author = uiState.novel?.author ?: "Tác giả",
                    chapterTitle = chapter?.titleVi?.ifBlank { chapter.title } ?: "Chương hiện tại",
                    onDismiss = viewModel::closeQuoteCard
                )
            }
        }
    }

    // 6. Retranslate Dialog
    if (uiState.showRetranslateDialog && chapter != null) {
        RetranslateDialog(
            chapterNumber = chapter.chapterNumber,
            chapterTitle = chapter.titleVi.ifBlank { chapter.title },
            prompt = uiState.retranslatePrompt,
            initialTargetLanguage = if (uiState.isEnglishMode) "en" else if (uiState.isBilingualMode) "bilingual" else "vi",
            initialEnglishLevel = uiState.englishLevel,
            onPromptChange = viewModel::onRetranslatePromptChange,
            onConfirm = { customPrompt, recrawl, targetLanguage, englishLevel ->
                viewModel.setEnglishLevel(englishLevel)
                if (recrawl) {
                    viewModel.triggerRecrawl(autoTranslate = true, customPrompt = customPrompt)
                } else {
                    viewModel.triggerRetranslation(
                        customPrompt = customPrompt,
                        targetLanguage = targetLanguage,
                        englishLevel = englishLevel
                    )
                }
            },
            onRecrawlOnly = { viewModel.triggerRecrawl(autoTranslate = false) },
            onDismiss = viewModel::closeRetranslateDialog
        )
    }

    // 6b. Hết truyện: bấm Chương sau ở chương cuối
    if (uiState.showEndOfBook) {
        ReaderEndOfBookDialog(
            ui = uiState.endOfBookUi(),
            onCheckNewChapters = viewModel::checkForNewChapters,
            onEnableAlerts = viewModel::enableNewChapterAlerts,
            onNextChapter = viewModel::nextChapter,
            onDismiss = viewModel::dismissEndOfBook
        )
    }

    // 7. Audiobook Studio Sheet
    if (uiState.showAudiobookStudio) {
        AudiobookStudioSheet(
            sleepTimerMode = uiState.sleepTimerMode,
            sleepTimerRemainingSeconds = uiState.sleepTimerRemainingSeconds,
            ambientSoundType = uiState.ambientSoundType,
            ambientSoundVolume = uiState.ambientSoundVolume,
            speed = uiState.ttsSpeed,
            voiceProfileId = uiState.ttsVoiceProfileId,
            voiceProfiles = uiState.voiceProfiles,
            isOnlineMode = uiState.ttsOnlineMode,
            onToggleOnlineMode = viewModel::setTtsOnlineMode,
            onSelectSleepTimer = viewModel::setSleepTimer,
            onSelectAmbientSound = viewModel::setAmbientSound,
            onChangeAmbientVolume = viewModel::setAmbientVolume,
            onSelectSpeed = viewModel::setTtsSpeed,
            onSelectVoiceProfile = viewModel::setTtsVoiceProfile,
            onDismiss = viewModel::closeAudiobookStudio
        )
    }

    // 8. English Word Sheet
    if (uiState.showEnglishWordSheet) {
        EnglishWordSheet(
            word = uiState.selectedEnglishWord,
            data = uiState.englishWordData,
            isLoading = uiState.isExplainingWord,
            isSaved = uiState.isWordSaved,
            savedForm = uiState.savedWordForm,
            contextSentence = uiState.selectedWordSentence,
            errorMessage = uiState.wordLookupError,
            onRetry = viewModel::retryWordLookup,
            onSaveWord = viewModel::saveCurrentWord,
            onSpeakWord = viewModel::speakWord,
            onDismiss = viewModel::closeEnglishWordSheet
        )
    }

    // 9. Sentence Analysis Sheet
    if (uiState.showSentenceAnalysis) {
        SentenceAnalysisSheet(
            sentence = uiState.analyzedSentence,
            data = uiState.sentenceAnalysis,
            isLoading = uiState.isAnalyzingSentence,
            errorMessage = uiState.sentenceAnalysisError,
            onRetry = viewModel::retrySentenceAnalysis,
            onSpeak = viewModel::speakWord,
            onDismiss = viewModel::closeSentenceAnalysis
        )
    }

    // 10. Vocabulary Deck Sheet
    if (uiState.showVocabDeck) {
        VocabularyDeckSheet(
            words = uiState.dueWords,
            allWords = uiState.allWords,
            onReviewWord = viewModel::reviewWord,
            onSpeakWord = viewModel::speakWord,
            onDismiss = viewModel::closeVocabDeck
        )
    }

    // 11-12. Dấu trang: bảng danh sách (chương này / cả truyện), hộp thoại tạo - sửa và thanh hoàn tác
    ReaderBookmarkModals(viewModel, uiState)
}
