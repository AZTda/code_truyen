package com.truyencloud.shared.repo

import com.truyencloud.shared.api.CollocationItem
import com.truyencloud.shared.api.WordExplainResponse
import com.truyencloud.shared.data.ExtraContext
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.data.SavedWordDao
import com.truyencloud.shared.data.decodeExtraContexts
import com.truyencloud.shared.data.getCurrentEpochMillis
import com.truyencloud.shared.srs.CardState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Xử lý lưu, cập nhật và xoá bản ghi SavedWord trong SQLite Room.
 *
 * Quy tắc gộp thẻ khi lưu một từ:
 *  1. trùng CHÍNH XÁC dạng chữ -> cập nhật thẻ đó;
 *  2. trùng LEMMA nhưng khác dạng chữ ("running" khi đã có "run") -> KHÔNG tạo thẻ trùng,
 *     gắn câu ngữ cảnh mới vào thẻ cũ (không ghi đè phần từ điển của thẻ cũ);
 *  3. từ từng bị xoá (còn bia mộ) -> "sống lại" đúng dòng cũ với tiến độ FSRS làm mới.
 */
class VocabularyWordCreator(
    private val savedWordDao: SavedWordDao,
    private val json: Json,
    private val aiStudio: VocabularyAiStudioCoordinator
) {
    suspend fun saveWord(
        word: String,
        ipaUs: String = "",
        ipaUk: String = "",
        partOfSpeech: String = "",
        cefrLevel: String = "",
        contextMeaningVi: String = "",
        englishDefinition: String = "",
        contextSentence: String = "",
        bookTitle: String = "",
        novelId: String = "",
        chapterNumber: Int = 0,
        contextCharOffset: Int = -1,
        etymology: String = "",
        collocations: List<CollocationItem> = emptyList(),
        aiExampleSentences: List<String> = emptyList()
    ): SavedWord {
        val now = getCurrentEpochMillis()
        val cleanWord = EnglishLemmatizer.normalizeWord(word)
        val lemma = EnglishLemmatizer.lemmatize(cleanWord)
        val collocationsJson = aiStudio.encodeCollocations(collocations)
        val examplesJson = try {
            json.encodeToString(ListSerializer(String.serializer()), aiExampleSentences)
        } catch (_: Exception) { "[]" }

        val sameForm = savedWordDao.getWord(cleanWord)
        val sameLemma = if (sameForm == null) savedWordDao.getByLemma(lemma) else null
        val existing = sameForm ?: sameLemma
        if (existing != null) {
            // Khác dạng chữ thì dữ liệu từ điển của lần tra này (IPA, nghĩa...) là của dạng khác — chỉ điền chỗ trống.
            val overwrite = sameForm != null
            fun pick(incoming: String, current: String) =
                if (overwrite) incoming.ifBlank { current } else current.ifBlank { incoming }
            savedWordDao.updateDictionaryFields(
                id = existing.id,
                ipaUs = pick(ipaUs, existing.ipaUs),
                ipaUk = pick(ipaUk, existing.ipaUk),
                partOfSpeech = pick(partOfSpeech, existing.partOfSpeech),
                cefrLevel = pick(cefrLevel, existing.cefrLevel),
                contextMeaningVi = pick(contextMeaningVi, existing.contextMeaningVi),
                englishDefinition = pick(englishDefinition, existing.englishDefinition),
                etymology = pick(etymology, existing.etymology),
                collocationsJson = if (collocations.isNotEmpty() && (overwrite || existing.collocationsJson == "[]")) collocationsJson else existing.collocationsJson,
                aiExampleSentencesJson = if (aiExampleSentences.isNotEmpty() && (overwrite || existing.aiExampleSentencesJson == "[]")) examplesJson else existing.aiExampleSentencesJson,
                updatedAt = now
            )
            attachContext(existing, cleanWord, contextSentence, bookTitle, novelId, chapterNumber, contextCharOffset, now)
            return savedWordDao.getById(existing.id) ?: existing
        }

        // Từ từng bị xoá: dùng lại dòng cũ (cùng id) và đặt updatedAt lớn hơn bia mộ để bản mới thắng khi đồng bộ.
        val tombstone = savedWordDao.getWordIncludingDeleted(cleanWord)
        val fresh = SavedWord(
            id = tombstone?.id ?: "word_$cleanWord",
            word = cleanWord,
            lemma = lemma,
            senseKey = "",
            ipaUs = ipaUs,
            ipaUk = ipaUk,
            partOfSpeech = partOfSpeech,
            cefrLevel = cefrLevel.ifBlank { "B2" },
            contextMeaningVi = contextMeaningVi,
            englishDefinition = englishDefinition,
            etymology = etymology,
            collocationsJson = collocationsJson,
            contextSentence = contextSentence,
            bookTitle = bookTitle,
            novelId = novelId,
            chapterNumber = chapterNumber,
            contextCharOffset = contextCharOffset,
            aiExampleSentencesJson = examplesJson,
            stability = 0.0,
            difficulty = 0.0,
            fsrsState = CardState.NEW.value,
            reps = 0,
            lapses = 0,
            lastReviewEpoch = 0L,
            // Thẻ mới KHÔNG có hạn ôn: nó chỉ vào hàng đợi theo hạn mức thẻ mới mỗi ngày.
            dueEpoch = 0L,
            srsStage = 0,
            nextReviewEpoch = 0L,
            createdAt = now,
            updatedAt = maxOf(now, (tombstone?.deletedAt ?: 0L) + 1L),
            deletedAt = 0L
        )
        savedWordDao.upsertWord(fresh)
        return fresh
    }

    /**
     * Gắn câu ngữ cảnh vào thẻ đã có. Câu đầu tiên là ngữ cảnh chính (dùng cho điền khuyết);
     * các câu sau (khác câu, khác dạng chữ) cất vào danh sách phụ, tối đa [MAX_EXTRA_CONTEXTS].
     */
    private suspend fun attachContext(
        existing: SavedWord,
        form: String,
        sentence: String,
        bookTitle: String,
        novelId: String,
        chapterNumber: Int,
        charOffset: Int,
        now: Long
    ) {
        if (sentence.isBlank()) return
        if (existing.contextSentence.isBlank()) {
            savedWordDao.updateBookContext(
                id = existing.id,
                contextSentence = sentence,
                novelId = novelId,
                chapterNumber = chapterNumber,
                contextCharOffset = charOffset,
                bookTitle = bookTitle.ifBlank { existing.bookTitle },
                updatedAt = now
            )
            return
        }
        if (existing.contextSentence.trim() == sentence.trim()) return

        val extras = decodeExtras(existing.extraContextsJson)
        if (extras.any { it.sentence.trim() == sentence.trim() }) return
        val updated = (extras + ExtraContext(sentence, form, bookTitle, novelId, chapterNumber, charOffset))
            .takeLast(MAX_EXTRA_CONTEXTS)
        savedWordDao.updateExtraContexts(existing.id, encodeExtras(updated), now)
    }

    fun decodeExtras(raw: String): List<ExtraContext> = decodeExtraContexts(raw)

    private fun encodeExtras(list: List<ExtraContext>): String = try {
        json.encodeToString(ListSerializer(ExtraContext.serializer()), list)
    } catch (_: Exception) { "[]" }

    suspend fun saveWordFromExplanation(
        explanation: WordExplainResponse,
        contextSentence: String,
        bookTitle: String,
        novelId: String = "",
        chapterNumber: Int = 0,
        contextCharOffset: Int = -1
    ): SavedWord = saveWord(
        word = explanation.word,
        ipaUs = explanation.ipaUs,
        ipaUk = explanation.ipaUk,
        partOfSpeech = explanation.partOfSpeech,
        cefrLevel = explanation.cefrLevel,
        contextMeaningVi = explanation.contextMeaningVi,
        englishDefinition = explanation.englishDefinition,
        contextSentence = contextSentence,
        bookTitle = bookTitle,
        novelId = novelId,
        chapterNumber = chapterNumber,
        contextCharOffset = contextCharOffset,
        etymology = explanation.etymology,
        collocations = explanation.collocations.map { CollocationItem(phrase = it, meaning = "") },
        aiExampleSentences = explanation.exampleSentences
    )

    /**
     * Bỏ lưu một từ: xoá MỀM để lần đồng bộ sau báo cho máy khác (xoá cứng thì máy kia đẩy bản cũ
     * lên và từ hiện lại). Tra theo đúng dạng chữ trước, rồi theo lemma.
     */
    suspend fun deleteWord(word: SavedWord) {
        savedWordDao.softDelete(word.id, getCurrentEpochMillis())
    }

    suspend fun deleteWordByText(text: String): Boolean {
        val clean = EnglishLemmatizer.normalizeWord(text)
        val target = savedWordDao.getWord(clean) ?: savedWordDao.getByLemma(EnglishLemmatizer.lemmatize(clean))
            ?: return false
        savedWordDao.softDelete(target.id, getCurrentEpochMillis())
        return true
    }

    companion object {
        const val MAX_EXTRA_CONTEXTS = 5
    }
}
