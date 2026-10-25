package com.truyencloud.shared.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * SavedWordDao giả trong bộ nhớ, bắt chước các câu SQL thật của SavedWordDao / VocabularySyncQueries (kể cả chỉ mục
 * unique trên `word`). [syncState] đóng vai bảng `sync_state`: [wipe] xoá cả hai như LocalDataMaintenance.wipeUserData().
 */
class InMemorySavedWordDao : SavedWordDao {
    val rows = mutableListOf<SavedWord>()
    val syncState = LinkedHashMap<String, Long>()

    private fun alive() = rows.filter { it.deletedAt == 0L }
    private fun put(w: SavedWord) {
        val i = rows.indexOfFirst { it.id == w.id }
        if (i >= 0) rows[i] = w else rows += w
    }
    private fun update(id: String, f: (SavedWord) -> SavedWord) {
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) rows[i] = f(rows[i])
    }
    private fun isNew(w: SavedWord) = w.fsrsState == 0 || w.reps == 0

    /** Xoá sạch như wipeUserData(): từ, và cả con trỏ đồng bộ nằm cùng cơ sở dữ liệu. */
    fun wipe() {
        rows.clear()
        syncState.clear()
    }

    // ---- VocabularySyncQueries ----
    override suspend fun countRowsIncludingDeleted(): Int = rows.size
    override suspend fun countWordsUpdatedUpTo(ts: Long): Int = rows.count { it.updatedAt in 1..ts }
    override suspend fun countUnsyncedWords(): Int = rows.count { !it.isSynced }
    override suspend fun getSyncCursor(key: String): Long? = syncState[key]
    override suspend fun putSyncCursor(key: String, value: Long) { syncState[key] = value }
    override suspend fun getChangedForSync(sinceEpoch: Long): List<SavedWord> =
        rows.filter { !it.isSynced || (sinceEpoch <= 0L && (it.updatedAt > 0L || it.deletedAt > 0L)) }.sortedBy { it.updatedAt }

    // ---- Đọc ----
    override fun getAllWords(): Flow<List<SavedWord>> = flowOf(alive().sortedByDescending { it.createdAt })
    override fun getDueWords(currentEpoch: Long): Flow<List<SavedWord>> = flowOf(
        alive().filter { !it.isSuspended && !isNew(it) && it.dueEpoch in 1..currentEpoch }.sortedBy { it.dueEpoch }
    )
    override fun getNewWords(): Flow<List<SavedWord>> =
        flowOf(alive().filter { !it.isSuspended && isNew(it) }.sortedBy { it.createdAt })
    override fun countDue(currentEpoch: Long): Flow<Int> = flowOf(0)
    override suspend fun countDueOnce(currentEpoch: Long): Int =
        alive().count { !it.isSuspended && !isNew(it) && it.dueEpoch in 1..currentEpoch }
    override suspend fun countNewOnce(): Int = alive().count { !it.isSuspended && isNew(it) }
    override suspend fun getWord(word: String): SavedWord? = alive().firstOrNull { it.word.equals(word, true) }
    override suspend fun getByLemma(lemma: String, senseKey: String): SavedWord? =
        alive().firstOrNull { it.lemma.equals(lemma, true) && it.senseKey == senseKey }
    override suspend fun getById(id: String): SavedWord? = alive().firstOrNull { it.id == id }
    override suspend fun getWordIncludingDeleted(word: String): SavedWord? = rows.firstOrNull { it.word.equals(word, true) }
    override suspend fun getByIdIncludingDeleted(id: String): SavedWord? = rows.firstOrNull { it.id == id }
    override fun getWordCount(): Flow<Int> = flowOf(alive().size)
    override suspend fun getWordsForChapter(novelId: String, chapterNumber: Int): List<SavedWord> =
        alive().filter { it.novelId == novelId && it.chapterNumber == chapterNumber }
    override suspend fun getWordsForNovel(novelId: String): List<SavedWord> =
        alive().filter { it.novelId == novelId }.sortedBy { it.chapterNumber }
    override suspend fun getAllWordsOnce(): List<SavedWord> = alive().sortedByDescending { it.createdAt }
    override suspend fun getLegacyWords(): List<SavedWord> = alive().filter { it.stability == 0.0 && it.srsStage > 0 }
    override suspend fun getClampedDifficultyWords(): List<SavedWord> =
        alive().filter { it.reps > 0 && it.difficulty > 0.0 && it.difficulty <= 1.0 }

    // ---- Ghi ----
    override suspend fun upsertWord(word: SavedWord) {
        val clash = rows.firstOrNull { it.word == word.word && it.id != word.id }
        check(clash == null) { "UNIQUE constraint failed: saved_words.word (${word.word})" }
        put(word)
    }
    override suspend fun upsertAll(words: List<SavedWord>) { words.forEach { put(it) } }
    override suspend fun updateDictionaryFields(
        id: String, ipaUs: String, ipaUk: String, partOfSpeech: String, cefrLevel: String, contextMeaningVi: String,
        englishDefinition: String, etymology: String, collocationsJson: String, aiExampleSentencesJson: String, updatedAt: Long
    ) = update(id) {
        it.copy(
            ipaUs = ipaUs, ipaUk = ipaUk, partOfSpeech = partOfSpeech, cefrLevel = cefrLevel, contextMeaningVi = contextMeaningVi,
            englishDefinition = englishDefinition, etymology = etymology, collocationsJson = collocationsJson,
            aiExampleSentencesJson = aiExampleSentencesJson, updatedAt = updatedAt, isSynced = false
        )
    }
    override suspend fun applyFsrsResult(
        id: String, stability: Double, difficulty: Double, fsrsState: Int, reps: Int, lapses: Int, lastReviewEpoch: Long,
        dueEpoch: Long, masteredCount: Int, legacyStage: Int, updatedAt: Long
    ) {
        if (rows.none { it.id == id && it.deletedAt == 0L }) return
        update(id) {
            it.copy(
                stability = stability, difficulty = difficulty, fsrsState = fsrsState, reps = reps, lapses = lapses,
                lastReviewEpoch = lastReviewEpoch, dueEpoch = dueEpoch, masteredCount = masteredCount, srsStage = legacyStage,
                nextReviewEpoch = dueEpoch, updatedAt = updatedAt, isSynced = false
            )
        }
    }
    override suspend fun updateBookContext(
        id: String, contextSentence: String, novelId: String, chapterNumber: Int, contextCharOffset: Int, bookTitle: String,
        updatedAt: Long
    ) = update(id) {
        it.copy(
            contextSentence = contextSentence, novelId = novelId, chapterNumber = chapterNumber,
            contextCharOffset = contextCharOffset, bookTitle = bookTitle, updatedAt = updatedAt, isSynced = false
        )
    }
    override suspend fun updateExtraContexts(id: String, json: String, updatedAt: Long) =
        update(id) { it.copy(extraContextsJson = json, updatedAt = updatedAt, isSynced = false) }
    override suspend fun setSuspended(id: String, suspended: Boolean, updatedAt: Long) =
        update(id) { it.copy(isSuspended = suspended, updatedAt = updatedAt, isSynced = false) }
    override suspend fun markSynced(id: String) = update(id) { it.copy(isSynced = true) }
    override suspend fun markSyncedIfUnchanged(id: String, updatedAt: Long, deletedAt: Long) =
        update(id) { if (it.updatedAt == updatedAt && it.deletedAt == deletedAt) it.copy(isSynced = true) else it }
    override suspend fun updateLifeScenarios(id: String, scenariosJson: String, updatedAt: Long) =
        update(id) { it.copy(lifeScenariosJson = scenariosJson, updatedAt = updatedAt, isSynced = false) }
    override suspend fun updateUserSentences(id: String, sentencesJson: String, updatedAt: Long) =
        update(id) { it.copy(userSentencesJson = sentencesJson, updatedAt = updatedAt, isSynced = false) }
    override suspend fun updateFlashcardArtAndCollocations(
        id: String, artUrl: String, mnemonic: String, paletteHex: String, collocationsJson: String, updatedAt: Long
    ) = update(id) {
        it.copy(
            flashcardArtUrl = artUrl, visualMnemonic = mnemonic, artPaletteHex = paletteHex,
            collocationsJson = collocationsJson, updatedAt = updatedAt, isSynced = false
        )
    }
    override suspend fun repairDifficulty(id: String, difficulty: Double) = update(id) { it.copy(difficulty = difficulty) }
    override suspend fun updateLemma(id: String, lemma: String) = update(id) { it.copy(lemma = lemma) }
    override suspend fun softDelete(id: String, now: Long) = update(id) {
        if (it.deletedAt == 0L) it.copy(deletedAt = now, updatedAt = now, isSynced = false) else it
    }
    override suspend fun deleteWord(word: SavedWord) { rows.removeAll { it.id == word.id } }
    override suspend fun purge(id: String) { rows.removeAll { it.id == id } }

    // ---- Thống kê (các bài kiểm thử đồng bộ / ôn tập không dùng) ----
    override suspend fun countByStabilityRange(minStab: Double, maxStab: Double): Int = 0
    override suspend fun dueCountBetween(epochStart: Long, epochEnd: Long): Int = 0
    override suspend fun countOverdueBefore(beforeEpoch: Long): Int = 0
    override suspend fun countStudied(): Int = alive().count { it.stability > 0.0 && !it.isSuspended }
    override suspend fun getLeechWords(limit: Int): List<SavedWord> = emptyList()
    override suspend fun countMatureWords(): Int = alive().count { it.stability >= 21.0 && !it.isSuspended }
}

/** ReviewLogDao giả trong bộ nhớ; id tự tăng như Room. */
class InMemoryReviewLogDao : ReviewLogDao {
    val rows = mutableListOf<ReviewLog>()
    private var nextId = 1L

    override suspend fun insert(log: ReviewLog): Long {
        // Tránh trùng id với dòng bài kiểm thử tự thêm thẳng vào [rows].
        val id = maxOf(nextId, (rows.maxOfOrNull { it.id } ?: 0L) + 1)
        nextId = id + 1
        rows += log.copy(id = id)
        return id
    }
    override suspend fun insertAll(logs: List<ReviewLog>) { logs.forEach { insert(it) } }
    override suspend fun countByKey(wordId: String, reviewedAt: Long): Int =
        rows.count { it.wordId == wordId && it.reviewedAt == reviewedAt }
    override suspend fun allKeys(): List<ReviewLogKey> = rows.map { ReviewLogKey(it.wordId, it.reviewedAt) }
    override suspend fun deleteDuplicates(): Int {
        val keep = rows.groupBy { it.wordId to it.reviewedAt }.map { (_, v) -> v.minOf { it.id } }.toSet()
        val before = rows.size
        rows.removeAll { it.id !in keep }
        return before - rows.size
    }
    override suspend fun getAllLogsOnce(limit: Int): List<ReviewLog> = rows.sortedByDescending { it.reviewedAt }.take(limit)
    override fun recent(limit: Int): Flow<List<ReviewLog>> = flowOf(rows.sortedByDescending { it.reviewedAt }.take(limit))
    override suspend fun getUnsynced(limit: Int): List<ReviewLog> =
        rows.filter { !it.isSynced }.sortedBy { it.reviewedAt }.take(limit)
    override suspend fun countUnsynced(): Int = rows.count { !it.isSynced }
    override suspend fun markSynced(ids: List<Long>) {
        rows.indices.forEach { if (rows[it].id in ids) rows[it] = rows[it].copy(isSynced = true) }
    }
    override suspend fun ratingsForWord(wordId: String): List<Int> =
        rows.filter { it.wordId == wordId && !it.isPractice }.sortedBy { it.reviewedAt }.map { it.rating }
    override suspend fun countForDateOnce(dateKey: String): Int = rows.count { it.dateKey == dateKey }
    override suspend fun countScheduledSince(sinceEpoch: Long): Int = rows.count { it.reviewedAt >= sinceEpoch && !it.isPractice }
    override fun observeScheduledSince(sinceEpoch: Long): Flow<Int> =
        flowOf(rows.count { it.reviewedAt >= sinceEpoch && !it.isPractice })
    override suspend fun countNewIntroducedSince(sinceEpoch: Long): Int = 0
    override fun observeNewIntroducedSince(sinceEpoch: Long): Flow<Int> = flowOf(0)
    override suspend fun countAll(): Int = rows.size
    override fun activeDates(limit: Int): Flow<List<String>> = flowOf(emptyList())
    override suspend fun activeDatesOnce(limit: Int): List<String> = emptyList()
    override suspend fun successCountSince(sinceEpoch: Long): Int = 0
    override suspend fun totalCountSince(sinceEpoch: Long): Int = 0
    override fun dailyStats(fromDateKey: String): Flow<List<DailyReviewStat>> = flowOf(emptyList())
    override suspend fun deleteForWord(wordId: String) { rows.removeAll { it.wordId == wordId } }
    override suspend fun deleteById(id: Long) { rows.removeAll { it.id == id } }
}
