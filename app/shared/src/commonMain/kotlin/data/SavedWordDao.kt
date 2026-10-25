package com.truyencloud.shared.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Quy ước chung: mọi truy vấn HIỂN THỊ đều lọc `deletedAt = 0` (xoá mềm, xem [SavedWord.deletedAt]).
 * Chỉ các hàm có đuôi `IncludingDeleted` / `ForSync` mới thấy "bia mộ".
 *
 * Thẻ CHƯA TỪNG HỌC (`fsrsState = 0` hoặc `reps = 0`) không bao giờ tính là "đến hạn":
 * chúng chỉ được đưa vào hàng đợi theo hạn mức thẻ mới mỗi ngày (xem StudyQueueBuilder).
 */
@Dao
interface SavedWordDao : VocabularySyncQueries {

    @Query("SELECT * FROM saved_words WHERE deletedAt = 0 ORDER BY createdAt DESC")
    fun getAllWords(): Flow<List<SavedWord>>

    /**
     * Thẻ đã học và đến hạn ôn.
     *
     * LƯU Ý: tham số `currentEpoch` được chốt tại thời điểm gọi. Tầng repository
     * phải phát lại theo nhịp (ticker) để danh sách vẫn đúng khi ứng dụng mở
     * xuyên qua nửa đêm — lỗi cũ là chốt một lần rồi không bao giờ cập nhật.
     */
    @Query(
        """
        SELECT * FROM saved_words
        WHERE deletedAt = 0 AND isSuspended = 0
          AND fsrsState != 0 AND reps > 0
          AND dueEpoch > 0 AND dueEpoch <= :currentEpoch
        ORDER BY dueEpoch ASC
        """
    )
    fun getDueWords(currentEpoch: Long): Flow<List<SavedWord>>

    /**
     * TẤT CẢ thẻ chưa từng được học, cũ nhất trước — KHÔNG giới hạn số lượng ở đây: bộ lọc truyện / chủ đề của
     * màn Thẻ SRS phải chạy TRƯỚC khi cắt theo hạn mức ngày (StudyQueueBuilder), nếu không 12 thẻ cũ nhất của cả sổ
     * chiếm hết chỗ và một cuốn truyện mới lưu không bao giờ có thẻ mới.
     */
    @Query(
        """
        SELECT * FROM saved_words
        WHERE deletedAt = 0 AND isSuspended = 0 AND (fsrsState = 0 OR reps = 0)
        ORDER BY createdAt ASC
        """
    )
    fun getNewWords(): Flow<List<SavedWord>>

    @Query(
        """
        SELECT COUNT(*) FROM saved_words
        WHERE deletedAt = 0 AND isSuspended = 0
          AND fsrsState != 0 AND reps > 0
          AND dueEpoch > 0 AND dueEpoch <= :currentEpoch
        """
    )
    fun countDue(currentEpoch: Long): Flow<Int>

    /** Bản không-reactive của [countDue] — dùng cho nhắc ôn chạy nền (worker không có Flow). */
    @Query(
        """
        SELECT COUNT(*) FROM saved_words
        WHERE deletedAt = 0 AND isSuspended = 0
          AND fsrsState != 0 AND reps > 0
          AND dueEpoch > 0 AND dueEpoch <= :currentEpoch
        """
    )
    suspend fun countDueOnce(currentEpoch: Long): Int

    @Query("SELECT COUNT(*) FROM saved_words WHERE deletedAt = 0 AND isSuspended = 0 AND (fsrsState = 0 OR reps = 0)")
    suspend fun countNewOnce(): Int

    @Query("SELECT * FROM saved_words WHERE word = :word COLLATE NOCASE AND deletedAt = 0 LIMIT 1")
    suspend fun getWord(word: String): SavedWord?

    /** Tra theo dạng nguyên thể: "running" tìm ra thẻ "run" đã lưu. */
    @Query("SELECT * FROM saved_words WHERE lemma = :lemma COLLATE NOCASE AND senseKey = :senseKey AND deletedAt = 0 LIMIT 1")
    suspend fun getByLemma(lemma: String, senseKey: String = ""): SavedWord?

    @Query("SELECT * FROM saved_words WHERE id = :id AND deletedAt = 0 LIMIT 1")
    suspend fun getById(id: String): SavedWord?

    /** Kể cả thẻ đã xoá mềm — để lưu lại một từ từng xoá thì "sống lại" đúng dòng cũ. */
    @Query("SELECT * FROM saved_words WHERE word = :word COLLATE NOCASE LIMIT 1")
    suspend fun getWordIncludingDeleted(word: String): SavedWord?

    @Query("SELECT * FROM saved_words WHERE id = :id LIMIT 1")
    suspend fun getByIdIncludingDeleted(id: String): SavedWord?

    @Query("SELECT COUNT(*) FROM saved_words WHERE deletedAt = 0")
    fun getWordCount(): Flow<Int>

    /** Các từ đã lưu thuộc một chương cụ thể — dùng cho tính năng "ôn trước khi đọc". */
    @Query("SELECT * FROM saved_words WHERE novelId = :novelId AND chapterNumber = :chapterNumber AND deletedAt = 0")
    suspend fun getWordsForChapter(novelId: String, chapterNumber: Int): List<SavedWord>

    @Query("SELECT * FROM saved_words WHERE novelId = :novelId AND deletedAt = 0 ORDER BY chapterNumber ASC")
    suspend fun getWordsForNovel(novelId: String): List<SavedWord>

    /**
     * Ghi thẻ, GIỮ NGUYÊN mọi dữ liệu đã có nếu thẻ đã tồn tại.
     *
     * Trước đây dùng `@Insert(onConflict = REPLACE)` — mà REPLACE trong SQLite là
     * DELETE rồi INSERT, nên lưu lại một từ đã có sẽ XOÁ SẠCH tiến độ FSRS, kịch bản
     * đời sống, câu người học tự đặt và poster đã sinh. `@Upsert` chỉ cập nhật các cột
     * được truyền vào nên không còn mất dữ liệu.
     */
    @Upsert
    suspend fun upsertWord(word: SavedWord)

    @Upsert
    suspend fun upsertAll(words: List<SavedWord>)

    /** Chỉ cập nhật phần từ điển, TUYỆT ĐỐI không đụng tới các cột tiến độ học. */
    @Query(
        """
        UPDATE saved_words SET
            ipaUs = :ipaUs,
            ipaUk = :ipaUk,
            partOfSpeech = :partOfSpeech,
            cefrLevel = :cefrLevel,
            contextMeaningVi = :contextMeaningVi,
            englishDefinition = :englishDefinition,
            etymology = :etymology,
            collocationsJson = :collocationsJson,
            aiExampleSentencesJson = :aiExampleSentencesJson,
            updatedAt = :updatedAt,
            isSynced = 0
        WHERE id = :id AND deletedAt = 0
        """
    )
    suspend fun updateDictionaryFields(
        id: String,
        ipaUs: String,
        ipaUk: String,
        partOfSpeech: String,
        cefrLevel: String,
        contextMeaningVi: String,
        englishDefinition: String,
        etymology: String,
        collocationsJson: String,
        aiExampleSentencesJson: String,
        updatedAt: Long
    )

    /** Ghi kết quả một lần ôn theo FSRS. */
    @Query(
        """
        UPDATE saved_words SET
            stability = :stability,
            difficulty = :difficulty,
            fsrsState = :fsrsState,
            reps = :reps,
            lapses = :lapses,
            lastReviewEpoch = :lastReviewEpoch,
            dueEpoch = :dueEpoch,
            masteredCount = :masteredCount,
            srsStage = :legacyStage,
            nextReviewEpoch = :dueEpoch,
            updatedAt = :updatedAt,
            isSynced = 0
        WHERE id = :id AND deletedAt = 0
        """
    )
    suspend fun applyFsrsResult(
        id: String,
        stability: Double,
        difficulty: Double,
        fsrsState: Int,
        reps: Int,
        lapses: Int,
        lastReviewEpoch: Long,
        dueEpoch: Long,
        masteredCount: Int,
        legacyStage: Int,
        updatedAt: Long
    )

    /** Gắn ngữ cảnh gốc trong truyện cho một thẻ đã có. */
    @Query(
        """
        UPDATE saved_words SET
            contextSentence = :contextSentence,
            novelId = :novelId,
            chapterNumber = :chapterNumber,
            contextCharOffset = :contextCharOffset,
            bookTitle = :bookTitle,
            updatedAt = :updatedAt,
            isSynced = 0
        WHERE id = :id AND deletedAt = 0
        """
    )
    suspend fun updateBookContext(
        id: String,
        contextSentence: String,
        novelId: String,
        chapterNumber: Int,
        contextCharOffset: Int,
        bookTitle: String,
        updatedAt: Long
    )

    /** Thêm một ngữ cảnh phụ (xem [SavedWord.extraContextsJson]). */
    @Query("UPDATE saved_words SET extraContextsJson = :json, updatedAt = :updatedAt, isSynced = 0 WHERE id = :id AND deletedAt = 0")
    suspend fun updateExtraContexts(id: String, json: String, updatedAt: Long)

    @Query("UPDATE saved_words SET isSuspended = :suspended, updatedAt = :updatedAt, isSynced = 0 WHERE id = :id AND deletedAt = 0")
    suspend fun setSuspended(id: String, suspended: Boolean, updatedAt: Long)

    @Query("UPDATE saved_words SET isSynced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    /**
     * Đánh dấu đã đồng bộ CHỈ KHI dòng chưa bị sửa kể từ lúc đọc để gửi — nếu người học vừa
     * ôn/sửa từ trong lúc đang đẩy thì cờ `isSynced = 0` phải được giữ để lần sau gửi bản mới.
     */
    @Query("UPDATE saved_words SET isSynced = 1 WHERE id = :id AND updatedAt = :updatedAt AND deletedAt = :deletedAt")
    suspend fun markSyncedIfUnchanged(id: String, updatedAt: Long, deletedAt: Long)

    @Query("UPDATE saved_words SET lifeScenariosJson = :scenariosJson, updatedAt = :updatedAt, isSynced = 0 WHERE id = :id AND deletedAt = 0")
    suspend fun updateLifeScenarios(id: String, scenariosJson: String, updatedAt: Long)

    @Query("UPDATE saved_words SET userSentencesJson = :sentencesJson, updatedAt = :updatedAt, isSynced = 0 WHERE id = :id AND deletedAt = 0")
    suspend fun updateUserSentences(id: String, sentencesJson: String, updatedAt: Long)

    @Query(
        """
        UPDATE saved_words SET
            flashcardArtUrl = :artUrl,
            visualMnemonic = :mnemonic,
            artPaletteHex = :paletteHex,
            collocationsJson = :collocationsJson,
            updatedAt = :updatedAt,
            isSynced = 0
        WHERE id = :id AND deletedAt = 0
        """
    )
    suspend fun updateFlashcardArtAndCollocations(
        id: String,
        artUrl: String,
        mnemonic: String,
        paletteHex: String,
        collocationsJson: String,
        updatedAt: Long
    )

    /** Các thẻ còn ở định dạng cũ (chưa có dữ liệu FSRS) — dùng cho di trú một lần. */
    @Query("SELECT * FROM saved_words WHERE stability = 0.0 AND srsStage > 0 AND deletedAt = 0")
    suspend fun getLegacyWords(): List<SavedWord>

    /**
     * Thẻ bị kẹp độ khó về đúng 1.0 bởi công thức FSRS lai cũ (D0 của FSRS-5 trên bộ trọng số 4.5):
     * khoảng cách của chúng dài gần gấp đôi thực tế. Dùng để tính lại độ khó một lần.
     */
    @Query("SELECT * FROM saved_words WHERE deletedAt = 0 AND reps > 0 AND difficulty > 0.0 AND difficulty <= 1.0")
    suspend fun getClampedDifficultyWords(): List<SavedWord>

    /**
     * Sửa độ khó đã bị kẹp — KHÔNG đổi updatedAt / isSynced: bản sửa chỉ là hệ quả tất định của lịch sử ôn nên máy nào
     * cũng tự ra cùng kết quả, còn đẩy nó lên với updatedAt = bây giờ sẽ thắng last-write-wins trước cả lượt ôn mới
     * hơn của máy khác (trạng thái cũ của máy này đè lên trạng thái mới của máy kia).
     */
    @Query("UPDATE saved_words SET difficulty = :difficulty WHERE id = :id AND deletedAt = 0")
    suspend fun repairDifficulty(id: String, difficulty: Double)

    /** Chỉ chỉnh lemma (kết quả của bộ rút gọn đã sửa) — không đổi updatedAt để khỏi gây đồng bộ hàng loạt. */
    @Query("UPDATE saved_words SET lemma = :lemma WHERE id = :id")
    suspend fun updateLemma(id: String, lemma: String)

    /**
     * Xoá mềm: giữ dòng làm bia mộ. `updatedAt = deletedAt = now` để so sánh last-write-wins
     * trên máy chủ thấy đây là thay đổi mới nhất.
     */
    @Query("UPDATE saved_words SET deletedAt = :now, updatedAt = :now, isSynced = 0 WHERE id = :id AND deletedAt = 0")
    suspend fun softDelete(id: String, now: Long)

    /** Xoá hẳn một dòng (chỉ dùng để dọn bia mộ đã hết hạn hoặc nhường chỗ cho bản đồng bộ về). */
    @Delete
    suspend fun deleteWord(word: SavedWord)

    @Query("DELETE FROM saved_words WHERE id = :id")
    suspend fun purge(id: String)

    // ---- Insights Dashboard queries ----------------------------------------

    /**
     * Tổng số từ ĐÃ HỌC theo nhóm stability — vẽ biểu đồ phân bố trí nhớ.
     * Thẻ mới (stability = 0) không thuộc nhóm nào: chúng chưa có trí nhớ để đo.
     */
    @Query(
        """
        SELECT COUNT(*) FROM saved_words
        WHERE deletedAt = 0 AND isSuspended = 0 AND stability > 0.0
          AND stability >= :minStab AND stability < :maxStab
        """
    )
    suspend fun countByStabilityRange(minStab: Double, maxStab: Double): Int

    /** Số thẻ đã học đến hạn trong [start, end) — cột theo NGÀY LỊCH của biểu đồ dự báo. */
    @Query(
        """
        SELECT COUNT(*) FROM saved_words
        WHERE deletedAt = 0 AND isSuspended = 0 AND fsrsState != 0 AND reps > 0
          AND dueEpoch >= :epochStart AND dueEpoch < :epochEnd
        """
    )
    suspend fun dueCountBetween(epochStart: Long, epochEnd: Long): Int

    /** Số thẻ đã quá hạn từ các ngày học trước (đến hạn trước [beforeEpoch]). */
    @Query(
        """
        SELECT COUNT(*) FROM saved_words
        WHERE deletedAt = 0 AND isSuspended = 0 AND fsrsState != 0 AND reps > 0
          AND dueEpoch > 0 AND dueEpoch < :beforeEpoch
        """
    )
    suspend fun countOverdueBefore(beforeEpoch: Long): Int

    /** Số từ đã học (có stability) — mẫu số cho biểu đồ phân bố. */
    @Query("SELECT COUNT(*) FROM saved_words WHERE deletedAt = 0 AND isSuspended = 0 AND stability > 0.0")
    suspend fun countStudied(): Int

    /** Tất cả từ leech (quên nhiều lần) — hiển thị danh sách cần chú ý. */
    @Query("SELECT * FROM saved_words WHERE deletedAt = 0 AND lapses >= 8 AND isSuspended = 0 ORDER BY lapses DESC LIMIT :limit")
    suspend fun getLeechWords(limit: Int = 20): List<SavedWord>

    /** Tổng số từ mature (stability >= 21 ngày). */
    @Query("SELECT COUNT(*) FROM saved_words WHERE deletedAt = 0 AND stability >= 21.0 AND isSuspended = 0")
    suspend fun countMatureWords(): Int

    /** Tất cả từ (one-shot, không reactive) để export. Không gồm bia mộ. */
    @Query("SELECT * FROM saved_words WHERE deletedAt = 0 ORDER BY createdAt DESC")
    suspend fun getAllWordsOnce(): List<SavedWord>
}
