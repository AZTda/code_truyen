package com.truyencloud.compose.screens.reader

import com.truyencloud.shared.api.WordExplainResponse
import com.truyencloud.shared.repo.VocabularyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lưu / bỏ lưu từ đang tra trong lúc đọc.
 *
 * - Lưu NGAY (từ + câu + truyện + chương), KHÔNG chờ kết quả tra từ của AI: trước đây nút Lưu chỉ có khi tra từ
 *   thành công nên mất mạng / máy chủ lỗi là không lưu được từ nào.
 * - Kết quả tra về sau thì điền vào thẻ đã lưu (updateDictionaryFields) — chỉ khi từ vẫn đang được lưu.
 * - Nhấn lần nữa để BỎ LƯU (xoá mềm, đồng bộ sang máy khác).
 */
internal class ReaderWordSaveCoordinator(
    private val vocabularyRepository: VocabularyRepository?,
    private val uiStateFlow: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope
) {
    // Hai lần chạm liên tiếp (Lưu rồi Bỏ lưu) phải ghi DB đúng thứ tự, nếu không "Bỏ lưu" có thể chạy trước "Lưu".
    private val writeLock = Mutex()

    fun toggleSave() {
        val repo = vocabularyRepository ?: return
        val state = uiStateFlow.value
        val word = state.selectedEnglishWord
        if (word.isBlank()) return

        if (state.isWordSaved) {
            uiStateFlow.value = state.copy(isWordSaved = false, savedWordForm = null)
            scope.launch {
                writeLock.withLock { runCatching { repo.deleteWordByText(word) } }
            }
        } else {
            uiStateFlow.value = state.copy(isWordSaved = true)
            scope.launch { writeLock.withLock { saveNow(repo, word) } }
        }
    }

    /** Tra từ xong: nếu người đọc đã bấm Lưu từ trước đó thì điền phần từ điển còn trống vào thẻ. */
    fun onLookupArrived(word: String, data: WordExplainResponse) {
        val repo = vocabularyRepository ?: return
        scope.launch {
            writeLock.withLock {
                val state = uiStateFlow.value
                if (state.selectedEnglishWord != word || !state.isWordSaved) return@withLock
                runCatching { saveNow(repo, word, data) }
            }
        }
    }

    private suspend fun saveNow(repo: VocabularyRepository, word: String, data: WordExplainResponse? = null) {
        val state = uiStateFlow.value
        val novel = state.novel
        val book = novel?.titleVi?.ifBlank { novel.title } ?: "TruyenCloud"
        val chapter = state.currentChapter?.chapterNumber ?: 0
        val explanation = data ?: state.englishWordData
        if (explanation != null) {
            repo.saveWordFromExplanation(
                explanation = explanation,
                contextSentence = state.selectedWordSentence,
                bookTitle = book,
                novelId = novel?.id ?: "",
                chapterNumber = chapter,
                contextCharOffset = state.selectedWordOffset
            )
        } else {
            repo.saveWord(
                word = word,
                contextSentence = state.selectedWordSentence,
                bookTitle = book,
                novelId = novel?.id ?: "",
                chapterNumber = chapter,
                contextCharOffset = state.selectedWordOffset
            )
        }
    }
}
