package com.truyencloud.shared.repo

import com.truyencloud.shared.api.EnglishApi
import com.truyencloud.shared.data.ReviewLogDao
import com.truyencloud.shared.data.SavedWordDao
import com.truyencloud.shared.data.getCurrentEpochMillis
import com.truyencloud.shared.srs.FsrsScheduler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * Các việc dọn dữ liệu chạy một lần mỗi lần mở ứng dụng (idempotent — chạy lại không đổi gì nếu đã sạch):
 *  - xoá log ôn tập bị nhân đôi do đồng bộ cũ khoá theo `id` tự tăng của từng máy;
 *  - tính lại `lemma` bằng bộ rút gọn đã sửa (playing -> "plaie", horses -> "hors"... đã lưu sai trong DB);
 *  - tính lại độ khó của thẻ bị kẹp đúng 1.0 bởi công thức FSRS lai cũ.
 */
class VocabularyMaintenance(
    private val savedWordDao: SavedWordDao,
    private val reviewLogDao: ReviewLogDao?,
    private val review: VocabularyReviewCoordinator,
    private val englishApi: EnglishApi? = null
) {
    private val mutex = Mutex()
    private var done = false

    suspend fun runOnce() {
        mutex.withLock {
            if (done) return@withLock
            runCatching { reviewLogDao?.deleteDuplicates() }
            runCatching { refreshLemmas() }
            runCatching { repairClampedDifficulties() }
            done = true
        }
    }

    /** Chỉ sửa cột lemma, không đổi updatedAt: mọi máy cùng hàm nên tự hội tụ, không cần đồng bộ hàng loạt. */
    suspend fun refreshLemmas(): Int {
        var changed = 0
        for (w in savedWordDao.getAllWordsOnce()) {
            val lemma = EnglishLemmatizer.lemmatize(w.word)
            if (lemma != w.lemma) {
                savedWordDao.updateLemma(w.id, lemma)
                changed++
            }
        }
        return changed
    }

    /**
     * Chỉ sửa khi phát lại được ĐỦ lịch sử ([VocabularyReviewCoordinator.replayedDifficultyOrNull]). Thiếu lịch sử thì để
     * nguyên: D = 1.0 có thể là kết quả ĐÚNG của chuỗi toàn "Dễ", và ghi đè bằng D0(Tốt) = 5.16 rồi đẩy lên sẽ thắng
     * last-write-wins ở mọi máy. Bản sửa cũng không đổi updatedAt / isSynced (xem [SavedWordDao.repairDifficulty]).
     */
    suspend fun repairClampedDifficulties(): Int {
        var fixed = 0
        for (w in savedWordDao.getClampedDifficultyWords()) {
            val d = review.replayedDifficultyOrNull(w) ?: continue
            // Chỉ ghi khi thật sự khác.
            if (abs(d - w.difficulty) > 1e-6) {
                savedWordDao.repairDifficulty(w.id, d)
                fixed++
            }
        }
        return fixed
    }

    /** Chuyển thẻ còn ở thuật toán cũ (chỉ có srsStage 0..4) sang FSRS mà không reset tiến độ. */
    suspend fun migrateLegacyWords(): Int {
        runOnce()
        val now = getCurrentEpochMillis()
        var count = 0
        for (w in savedWordDao.getLegacyWords()) {
            val mem = FsrsScheduler.migrateFromLegacyStage(w.srsStage, w.nextReviewEpoch, now)
            savedWordDao.applyFsrsResult(
                id = w.id,
                stability = mem.stability,
                difficulty = mem.difficulty,
                fsrsState = mem.state.value,
                reps = mem.reps,
                lapses = mem.lapses,
                lastReviewEpoch = mem.lastReviewEpoch,
                dueEpoch = mem.dueEpoch,
                masteredCount = w.masteredCount,
                legacyStage = w.srsStage,
                updatedAt = now
            )
            count++
        }
        return count
    }

    /**
     * Bổ sung định nghĩa cho các từ bị thiếu nghĩa tiếng Việt / định nghĩa tiếng Anh theo lô tối đa 10 từ
     * qua `explainWordsBatch`, rơi về `explainWord` đơn lẻ nếu lô lỗi hoặc thiếu từ.
     */
    suspend fun fillMissingFields(): Int {
        val api = englishApi ?: return 0
        val missing = savedWordDao.getAllWordsOnce().filter {
            it.contextMeaningVi.isBlank() || it.englishDefinition.isBlank()
        }
        if (missing.isEmpty()) return 0
        val now = getCurrentEpochMillis()
        var enriched = 0
        for (chunk in missing.chunked(BATCH_EXPLAIN_CHUNK_SIZE)) {
            val batchByWord = runCatching {
                api.explainWordsBatch(chunk.map { it.word })
            }.getOrDefault(emptyList()).associateBy { it.word.trim().lowercase() }

            for (w in chunk) {
                val fromBatch = batchByWord[w.word.trim().lowercase()]?.takeIf { it.hasContent }
                val exp = fromBatch ?: runCatching {
                    api.explainWord(
                        word = w.word,
                        context = w.contextSentence.ifBlank { null },
                        bookTitle = w.bookTitle.ifBlank { null }
                    )
                }.getOrNull()?.takeIf { it.hasContent } ?: continue

                savedWordDao.updateDictionaryFields(
                    id = w.id,
                    ipaUs = w.ipaUs.ifBlank { exp.ipaUs },
                    ipaUk = w.ipaUk.ifBlank { exp.ipaUk },
                    partOfSpeech = w.partOfSpeech.ifBlank { exp.partOfSpeech },
                    cefrLevel = w.cefrLevel.ifBlank { exp.cefrLevel },
                    contextMeaningVi = w.contextMeaningVi.ifBlank { exp.contextMeaningVi },
                    englishDefinition = w.englishDefinition.ifBlank { exp.englishDefinition },
                    etymology = w.etymology.ifBlank { exp.etymology },
                    collocationsJson = w.collocationsJson,
                    aiExampleSentencesJson = w.aiExampleSentencesJson,
                    updatedAt = now
                )
                enriched++
            }
        }
        return enriched
    }

    companion object {
        const val BATCH_EXPLAIN_CHUNK_SIZE = 10
    }
}
