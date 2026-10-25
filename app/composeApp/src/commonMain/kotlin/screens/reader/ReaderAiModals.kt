package com.truyencloud.compose.screens.reader

import androidx.compose.runtime.Composable
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.reader.AiErrors
import com.truyencloud.shared.data.Chapter

/**
 * Tập hợp các modal trí tuệ nhân tạo (AI) trong màn hình đọc sách:
 * Recap (Previously On), Đọc hiểu (Comprehension), Tra cứu Lore, Bảng thuật ngữ và AI Companion Chat.
 *
 * Mỗi bảng dùng bảng màu của chủ đề đọc đang chọn (sáng, sepia, đen OLED...) qua [ReaderSheet], không còn ép nền tối.
 */
@Composable
fun ReaderAiModals(
    viewModel: ReaderViewModel,
    uiState: ReaderUiState,
    chapter: Chapter?
) {
    val novelTitle = uiState.novel?.let { n -> n.titleVi.ifBlank { n.title } }
    val chapterTitle = chapter?.let { it.titleVi.ifBlank { it.title } }

    // 1. Chapter Recap Sheet (Previously On)
    if (uiState.showChapterRecap) {
        ReaderSheetBackdrop(onDismiss = viewModel::closeChapterRecap) {
            BottomSheetAnchor {
                ChapterRecapSheet(
                    chapterTitle = chapterTitle ?: "Chương hiện tại",
                    recap = uiState.chapterRecapData,
                    isLoading = uiState.isLoadingRecap,
                    error = uiState.recapError,
                    nothingToRecap = uiState.recapNothingToRecap,
                    onRetry = viewModel::openChapterRecap,
                    onDismiss = viewModel::closeChapterRecap
                )
            }
        }
    }

    // 2. Comprehension Questions Sheet
    if (uiState.showComprehensionSheet) {
        ReaderSheetBackdrop(onDismiss = viewModel::closeComprehension) {
            BottomSheetAnchor {
                ComprehensionSheet(
                    chapterTitle = chapterTitle ?: "",
                    questions = uiState.comprehensionQuestions,
                    answers = uiState.comprehensionAnswers,
                    isSubmitted = uiState.isComprehensionSubmitted,
                    score = uiState.comprehensionScore,
                    isLoading = uiState.isLoadingComprehension,
                    errorMessage = uiState.comprehensionError,
                    onReload = viewModel::loadComprehensionQuestions,
                    onAnswerQuestion = viewModel::answerComprehensionQuestion,
                    onSubmit = viewModel::submitComprehension,
                    onRetry = viewModel::retryComprehension,
                    onDismiss = viewModel::closeComprehension
                )
            }
        }
    }

    // 3. Lore & Hanzi Lookup Sheet
    if (uiState.showLoreLookup) {
        // Chọn tên trong BẢN GỐC thì nguyên văn khớp chính xác: chỉ mời chuyển sang bản gốc khi đang xem bản dịch và có bản gốc
        val canPickFromOriginal = !uiState.showOriginal && !chapter?.originalContent.isNullOrBlank()
        ReaderSheetBackdrop(onDismiss = viewModel::closeLoreLookup) {
            BottomSheetAnchor {
                LoreLookupSheet(
                    initialTerm = uiState.loreTerm,
                    loreData = uiState.loreLookupData,
                    isLoading = uiState.isLoadingLore,
                    onLookupTerm = viewModel::lookupLore,
                    onDismiss = viewModel::closeLoreLookup,
                    isSaved = uiState.isLoreTermSaved,
                    targetInput = uiState.loreTargetInput,
                    onTargetChange = viewModel::onLoreTargetChange,
                    isSaving = uiState.isSavingLoreTerm,
                    saveMessage = uiState.loreSaveMessage,
                    glossarySize = uiState.glossarySize,
                    canSave = uiState.novel != null,
                    onSave = viewModel::saveLoreTermToGlossary,
                    context = uiState.loreContext,
                    error = uiState.loreError,
                    onRetry = viewModel::retryLore,
                    onOpenGlossary = viewModel::openGlossary,
                    onReadOriginal = if (canPickFromOriginal) viewModel::readOriginalToPickTerm else null
                )
            }
        }
    }

    // 4. Bảng thuật ngữ của truyện
    if (uiState.glossary.visible) {
        val glossary = viewModel.glossaryCoordinator
        ReaderSheetBackdrop(onDismiss = viewModel::closeGlossary) {
            BottomSheetAnchor {
                GlossarySheet(
                    state = uiState.glossary,
                    // Dịch lại bằng AI cần đăng nhập (máy chủ từ chối khách)
                    canRetranslate = TruyenCloudApi.authToken.isNotBlank(),
                    onRetranslate = glossary::retranslate,
                    onSaveTarget = glossary::saveTarget,
                    onTogglePin = glossary::togglePinned,
                    onDelete = glossary::delete,
                    onAdd = glossary::add,
                    onClearAll = glossary::clearAll,
                    onOpenLookup = { viewModel.openLoreLookup() },
                    onDismissMessage = glossary::dismissMessage,
                    onDismiss = viewModel::closeGlossary
                )
            }
        }
    }

    // 5. AI Book Companion Chat Sheet
    if (uiState.showCompanionChat) {
        val companion = viewModel.companionCoordinator
        ReaderSheetBackdrop(onDismiss = companion::closeCompanionChat) {
            BottomSheetAnchor {
                CompanionChatSheet(
                    novelTitle = novelTitle ?: "Tiểu thuyết",
                    chapterTitle = chapterTitle ?: "Chương hiện tại",
                    selectedCharacter = uiState.companionSelectedCharacter,
                    chatHistory = uiState.companionChatHistory,
                    suggestedQuestions = uiState.companionSuggestedQuestions,
                    isTyping = uiState.isCompanionTyping,
                    isStreaming = uiState.isCompanionStreaming,
                    errorMessage = uiState.companionError,
                    canRetry = uiState.companionFailedQuestion != null && uiState.companionError != AiErrors.MSG_LOGIN_REQUIRED,
                    onRetry = companion::retryLast,
                    onSendMessage = companion::sendMessage,
                    onSelectCharacter = companion::selectCharacter,
                    onClearHistory = companion::clearHistory,
                    onDismiss = companion::closeCompanionChat
                )
            }
        }
    }
}
