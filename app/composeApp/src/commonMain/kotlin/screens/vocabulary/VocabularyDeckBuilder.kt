package com.truyencloud.compose.screens.vocabulary

import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.repo.VocabularyRepository
import com.truyencloud.shared.repo.toMemoryState
import com.truyencloud.shared.srs.FsrsScheduler

/**
 * Dựng bộ thẻ cho chế độ Ôn thẻ và lọc danh sách Sổ từ. Tách khỏi ViewModel để mỗi tệp một trách nhiệm.
 */
internal object VocabularyDeckBuilder {

    /** Số thẻ tối đa trong một phiên ôn nhồi — đủ dày để ôn mà không biến thành cuộc đua dài vô tận. */
    const val CRAM_LIMIT = 50

    fun applyFilterAndSearch(
        all: List<SavedWord>,
        due: List<SavedWord>,
        filter: VocabFilter,
        query: String
    ): List<SavedWord> {
        val base = when (filter) {
            VocabFilter.ALL -> all
            VocabFilter.DUE -> due
            VocabFilter.LEVEL_B1 -> all.filter { it.cefrLevel.equals("B1", true) }
            VocabFilter.LEVEL_B2 -> all.filter { it.cefrLevel.equals("B2", true) }
            VocabFilter.LEVEL_C1 -> all.filter { it.cefrLevel.equals("C1", true) }
            VocabFilter.LEVEL_C2 -> all.filter {
                it.cefrLevel.equals("C2", true) || it.cefrLevel.equals("Academic", true)
            }
            VocabFilter.MASTERED -> all.filter { it.stability >= 21.0 }
            VocabFilter.LEECH -> all.filter { it.isLeech }
        }
        if (query.isBlank()) return base
        val q = query.trim().lowercase()
        return base.filter {
            it.word.lowercase().contains(q) ||
                it.lemma.lowercase().contains(q) ||
                it.contextMeaningVi.lowercase().contains(q) ||
                it.englishDefinition.lowercase().contains(q)
        }
    }

    /**
     * Giới hạn theo cuốn truyện / chủ đề đang chọn.
     * Không còn `.ifEmpty { words }`: cuốn đó hết thẻ đến hạn thì là HẾT, không được lặng lẽ kéo thẻ của
     * cuốn khác vào (bản cũ làm phiên ôn theo truyện không bao giờ kết thúc).
     */
    fun applyContextScope(
        words: List<SavedWord>,
        state: VocabularyUiState,
        repository: VocabularyRepository
    ): List<SavedWord> = when {
        state.selectedBook != null ->
            words.filter { it.bookTitle.equals(state.selectedBook, ignoreCase = true) }
        state.selectedDomain != null ->
            words.filter { repository.getWordDomain(it) == state.selectedDomain }
        else -> words
    }

    /** Hàng đợi của ngày: thẻ ôn đến hạn rồi tới thẻ mới theo hạn mức — không còn lùi về "tất cả từ" khi hết thẻ. */
    fun buildQueue(
        repository: VocabularyRepository,
        due: List<SavedWord>,
        fresh: List<SavedWord>,
        state: VocabularyUiState
    ): List<SavedWord> = repository.buildStudyQueue(
        dueWords = applyContextScope(due, state, repository),
        newWords = applyContextScope(fresh, state, repository),
        snapshot = state.snapshot,
        activeBookTitle = state.selectedBook
    )

    /** Giữ nguyên thứ tự bộ thẻ đang học, chỉ cập nhật nội dung thẻ từ DB và bỏ thẻ đã xoá / tạm hoãn. */
    fun refreshInPlace(deck: List<SavedWord>, all: List<SavedWord>): List<SavedWord> {
        val byId = all.associateBy { it.id }
        return deck.mapNotNull { card -> byId[card.id]?.takeIf { !it.isSuspended } }
    }

    /**
     * Làm mới hàng đợi và đề Luyện tập từ DB, như [refreshInPlace] làm với bộ thẻ Ôn thẻ. Đề dựng sẵn mang ảnh chụp của thẻ lúc
     * dựng: thẻ chấm ở tab Ôn thẻ xong rồi quay lại Luyện tập thì đề vẫn giữ trạng thái trước lượt chấm đó.
     * Giữ thứ tự; chỉ số bám theo thẻ đang làm để việc bỏ thẻ đã xoá / tạm hoãn phía trước không làm nhảy sang thẻ khác.
     */
    fun refreshPractice(s: VocabularyUiState, all: List<SavedWord>): VocabularyUiState {
        if (s.practiceQueue.isEmpty() && s.practiceItem == null) return s
        val byId = all.associateBy { it.id }
        val currentId = s.practiceQueue.getOrNull(s.practiceIndex)?.id
        val queue = refreshInPlace(s.practiceQueue, all)
        val index = currentId?.let { id -> queue.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            ?: s.practiceIndex.coerceAtMost(queue.size)
        val item = s.practiceItem?.let { cur -> byId[cur.word.id]?.let { cur.copy(word = it) } ?: cur }
        return s.copy(practiceQueue = queue, practiceIndex = index, practiceItem = item)
    }

    /**
     * Bộ thẻ ôn nhồi: những từ đã học mà người học có khả năng quên nhất (R thấp) trước.
     * Thẻ chưa học xếp cuối — ôn nhồi là xem lại, không phải học từ mới.
     */
    fun cramPool(
        all: List<SavedWord>,
        state: VocabularyUiState,
        repository: VocabularyRepository,
        nowEpoch: Long,
        limit: Int = CRAM_LIMIT
    ): List<SavedWord> {
        val scoped = applyContextScope(all.filter { !it.isSuspended }, state, repository)
        return scoped
            .sortedBy { w ->
                if (w.isNeverStudied) 2.0
                else FsrsScheduler.retrievability(FsrsScheduler.elapsedDays(w.toMemoryState(), nowEpoch), w.stability)
            }
            .take(limit)
    }
}
