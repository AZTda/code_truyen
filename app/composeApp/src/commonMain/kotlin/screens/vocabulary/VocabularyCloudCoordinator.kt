package com.truyencloud.compose.screens.vocabulary

import com.truyencloud.shared.export.PlatformShareHandler
import com.truyencloud.shared.export.VocabularyExporter
import com.truyencloud.shared.notify.ReviewReminderConfig
import com.truyencloud.shared.repo.StudyConfig
import com.truyencloud.shared.repo.VocabularyRepository
import com.truyencloud.shared.repo.VocabularySyncResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Đồng bộ đám mây, xuất Anki và nhắc ôn hằng ngày của màn Sổ từ. */
class VocabularyCloudCoordinator(
    private val vocabularyRepository: VocabularyRepository,
    private val uiState: MutableStateFlow<VocabularyUiState>,
    private val scope: CoroutineScope
) {
    fun syncWithCloud() {
        if (uiState.value.isSyncing) return
        scope.launch {
            uiState.update { it.copy(isSyncing = true, isSyncSuccess = false, syncMessage = null) }
            val (message, tone) = when (val r = vocabularyRepository.sync()) {
                is VocabularySyncResult.Success ->
                    "Đã đồng bộ: gửi ${r.wordsPushed} từ / ${r.logsPushed} lượt ôn, nhận ${r.wordsPulled} từ / ${r.logsPulled} lượt ôn." to SyncTone.SUCCESS
                // Chưa đăng nhập KHÔNG phải thành công (trước đây báo xanh "0 từ, 0 lượt ôn").
                VocabularySyncResult.NotLoggedIn ->
                    "Chưa đăng nhập — hãy đăng nhập để đồng bộ từ vựng giữa các thiết bị." to SyncTone.INFO
                is VocabularySyncResult.Failure ->
                    (if (r.sessionExpired) "Phiên đăng nhập đã hết hạn — hãy đăng nhập lại để đồng bộ."
                    else "Đồng bộ thất bại: ${r.message}") to SyncTone.ERROR
            }
            uiState.update {
                it.copy(
                    isSyncing = false,
                    isSyncSuccess = tone == SyncTone.SUCCESS,
                    syncMessage = message,
                    syncTone = tone
                )
            }
            delay(if (tone == SyncTone.SUCCESS) 3500L else 5000L)
            uiState.update { it.copy(isSyncSuccess = false, syncMessage = null) }
        }
    }

    fun exportAnkiDeck() {
        val words = uiState.value.allWords
        if (words.isEmpty()) return
        val format = VocabularyExporter.Format.TSV_ANKI
        val (fileName, content) = VocabularyExporter.export(words, format)
        PlatformShareHandler.shareTextFile(fileName = fileName, content = content, mimeType = format.mimeType)
    }

    // -----------------------------------------------------------------------
    // Nhắc ôn hằng ngày
    // -----------------------------------------------------------------------

    fun loadReminder() {
        uiState.update {
            it.copy(
                reminder = vocabularyRepository.reminderConfig(),
                studyConfig = vocabularyRepository.studyConfig()
            )
        }
    }

    fun openReminderDialog() {
        uiState.update { it.copy(showReminderDialog = true, reminder = vocabularyRepository.reminderConfig(), reminderNotice = null) }
    }

    fun closeReminderDialog() {
        uiState.update { it.copy(showReminderDialog = false, reminderNotice = null) }
    }

    fun saveReminder(config: ReviewReminderConfig) {
        scope.launch {
            val allowed = vocabularyRepository.setReminder(config)
            val denied = config.enabled && !allowed
            uiState.update {
                it.copy(
                    reminder = config,
                    // Chưa được cấp quyền thông báo thì giữ hộp thoại mở để nói rõ lý do thay vì im lặng.
                    showReminderDialog = denied,
                    reminderNotice = if (denied) {
                        "Chưa có quyền hiện thông báo. Hãy bật thông báo cho TruyenCloud trong Cài đặt của hệ thống rồi lưu lại."
                    } else null
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Hạn mức học mỗi ngày & Thêm từ thủ công
    // -----------------------------------------------------------------------

    fun openStudyGoalDialog() {
        uiState.update { it.copy(showStudyGoalDialog = true, studyConfig = vocabularyRepository.studyConfig()) }
    }

    fun closeStudyGoalDialog() {
        uiState.update { it.copy(showStudyGoalDialog = false) }
    }

    fun saveStudyConfig(config: StudyConfig, onSaved: () -> Unit) {
        vocabularyRepository.updateStudyConfig(config)
        uiState.update { it.copy(studyConfig = config, showStudyGoalDialog = false) }
        onSaved()
    }

    fun openAddWordDialog() {
        uiState.update { it.copy(showAddWordDialog = true, isAddingCustomWord = false, addWordError = null) }
    }

    fun closeAddWordDialog() {
        uiState.update { it.copy(showAddWordDialog = false, isAddingCustomWord = false, addWordError = null) }
    }

    fun addCustomWord(wordText: String, contextSentence: String, meaningVi: String) {
        val clean = wordText.trim()
        if (clean.isBlank() || uiState.value.isAddingCustomWord) return
        uiState.update { it.copy(isAddingCustomWord = true, addWordError = null) }
        scope.launch {
            try {
                val explained = runCatching {
                    vocabularyRepository.explainWord(
                        word = clean,
                        context = contextSentence.ifBlank { null },
                        bookTitle = "Sổ từ cá nhân"
                    )
                }.getOrNull()

                if (explained != null && !explained.isFallback) {
                    val chosenSentence = contextSentence.ifBlank {
                        explained.exampleSentences.firstOrNull().orEmpty()
                    }
                    vocabularyRepository.saveWord(
                        word = explained.word.ifBlank { clean },
                        ipaUs = explained.ipaUs,
                        ipaUk = explained.ipaUk,
                        partOfSpeech = explained.partOfSpeech,
                        cefrLevel = explained.cefrLevel,
                        contextMeaningVi = meaningVi.ifBlank { explained.contextMeaningVi },
                        englishDefinition = explained.englishDefinition,
                        contextSentence = chosenSentence,
                        bookTitle = "Sổ từ cá nhân",
                        etymology = explained.etymology,
                        collocations = explained.collocations.map {
                            com.truyencloud.shared.api.CollocationItem(phrase = it, meaning = "")
                        },
                        aiExampleSentences = explained.exampleSentences
                    )
                } else {
                    vocabularyRepository.saveWord(
                        word = clean,
                        contextMeaningVi = meaningVi,
                        contextSentence = contextSentence,
                        bookTitle = "Sổ từ cá nhân"
                    )
                }
                uiState.update { it.copy(isAddingCustomWord = false, showAddWordDialog = false, addWordError = null) }
            } catch (e: Exception) {
                uiState.update {
                    it.copy(isAddingCustomWord = false, addWordError = e.message ?: "Không thể lưu từ vựng.")
                }
            }
        }
    }
}
