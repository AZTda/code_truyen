package com.truyencloud.shared.repo

import com.truyencloud.shared.api.*
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.data.SavedWordDao
import com.truyencloud.shared.data.getCurrentEpochMillis
import com.truyencloud.shared.prefs.SettingsStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Điều phối các dịch vụ AI Studio từ vựng (Tình huống đời sống, Flashcard art, Đặt câu, Vi truyện).
 */
class VocabularyAiStudioCoordinator(
    private val englishApi: EnglishApi,
    private val savedWordDao: SavedWordDao,
    private val json: Json,
    private val settingsStore: SettingsStore? = null
) {
    private val artCache = mutableMapOf<String, FlashcardArtResponse>()
    suspend fun getLifeScenarios(savedWord: SavedWord): LifeScenariosResponse {
        if (savedWord.lifeScenariosJson.isNotBlank()) {
            val cachedScenarios = try {
                json.decodeFromString<LifeScenariosResponse>(savedWord.lifeScenariosJson)
            } catch (_: Exception) { null }
            if (cachedScenarios != null) return cachedScenarios
        }
        val response = englishApi.getLifeScenarios(
            word = savedWord.word,
            contextMeaningVi = savedWord.contextMeaningVi,
            partOfSpeech = savedWord.partOfSpeech,
            cefrLevel = savedWord.cefrLevel
        )
        if (!response.isFallback) {
            try {
                savedWordDao.updateLifeScenarios(
                    savedWord.id,
                    json.encodeToString(LifeScenariosResponse.serializer(), response),
                    getCurrentEpochMillis()
                )
            } catch (_: Exception) { /* bo qua */ }
        }
        return response
    }

    suspend fun evaluateSentence(word: String, sentence: String, contextType: String = "daily"): SentenceEvaluateResponse =
        englishApi.evaluateSentence(word = word, sentence = sentence, contextType = contextType)

    suspend fun saveUserSentence(savedWord: SavedWord, evaluation: SentenceEvaluateResponse) {
        if (evaluation.isFallback) return
        try {
            val current = getPracticedSentences(savedWord).toMutableList()
            current.add(0, evaluation)
            savedWordDao.updateUserSentences(
                savedWord.id,
                json.encodeToString(ListSerializer(SentenceEvaluateResponse.serializer()), current.take(10)),
                getCurrentEpochMillis()
            )
        } catch (_: Exception) { /* bo qua */ }
    }

    fun getPracticedSentences(savedWord: SavedWord): List<SentenceEvaluateResponse> =
        try {
            if (savedWord.userSentencesJson.isBlank()) emptyList()
            else json.decodeFromString(
                ListSerializer(SentenceEvaluateResponse.serializer()), savedWord.userSentencesJson
            )
        } catch (_: Exception) { emptyList() }

    fun getCollocations(savedWord: SavedWord): List<CollocationItem> {
        val raw = savedWord.collocationsJson
        if (raw.isBlank() || raw == "[]") return emptyList()
        try {
            return json.decodeFromString(ListSerializer(CollocationItem.serializer()), raw)
        } catch (_: Exception) { /* thu dinh dang cu */ }

        try {
            return json.decodeFromString(ListSerializer(String.serializer()), raw)
                .filter { it.isNotBlank() }
                .map { CollocationItem(phrase = it, meaning = "") }
        } catch (_: Exception) { /* bo qua */ }

        return emptyList()
    }

    fun getAiExamples(savedWord: SavedWord): List<String> =
        try {
            if (savedWord.aiExampleSentencesJson.isBlank()) emptyList()
            else json.decodeFromString(ListSerializer(String.serializer()), savedWord.aiExampleSentencesJson)
        } catch (_: Exception) { emptyList() }

    fun encodeCollocations(items: List<CollocationItem>): String =
        try {
            json.encodeToString(ListSerializer(CollocationItem.serializer()), items)
        } catch (_: Exception) { "[]" }

    suspend fun getOrGenerateFlashcardArt(savedWord: SavedWord): FlashcardArtResponse? {
        artCache[savedWord.id]?.let { return it }
        val cached = getCollocations(savedWord)
        if (savedWord.flashcardArtUrl.isNotBlank()) {
            val storedRaw = settingsStore?.getString("art_meta_${savedWord.id}", "").orEmpty()
            val storedMeta = if (storedRaw.isNotBlank()) {
                runCatching { json.decodeFromString(FlashcardArtResponse.serializer(), storedRaw) }.getOrNull()
            } else null
            if (storedMeta != null) {
                val merged = storedMeta.copy(
                    imageUrl = savedWord.flashcardArtUrl,
                    mnemonic = savedWord.visualMnemonic.ifBlank { storedMeta.mnemonic },
                    collocations = cached.ifEmpty { storedMeta.collocations }
                )
                artCache[savedWord.id] = merged
                return merged
            }
            if (cached.isNotEmpty()) {
                val fallback = FlashcardArtResponse(
                    word = savedWord.word,
                    imageUrl = savedWord.flashcardArtUrl,
                    mnemonic = savedWord.visualMnemonic,
                    paletteHex = savedWord.artPaletteHex.ifBlank { "#D488FF" },
                    englishDefinition = savedWord.englishDefinition,
                    collocations = cached
                )
                artCache[savedWord.id] = fallback
                return fallback
            }
        }
        return try {
            val res = englishApi.generateFlashcardArt(
                word = savedWord.word,
                ipa = savedWord.ipaUs.ifBlank { savedWord.ipaUk },
                cefrLevel = savedWord.cefrLevel,
                meaning = savedWord.contextMeaningVi,
                context = savedWord.contextSentence.ifBlank { savedWord.bookTitle }
            )
            if (res.imageUrl.isNotBlank()) {
                val now = getCurrentEpochMillis()
                savedWordDao.updateFlashcardArtAndCollocations(
                    id = savedWord.id,
                    artUrl = res.imageUrl,
                    mnemonic = res.mnemonic,
                    paletteHex = res.paletteHex,
                    collocationsJson = encodeCollocations(res.collocations),
                    updatedAt = now
                )
                if (savedWord.englishDefinition.isBlank() && res.englishDefinition.isNotBlank()) {
                    savedWordDao.updateDictionaryFields(
                        id = savedWord.id,
                        ipaUs = savedWord.ipaUs,
                        ipaUk = savedWord.ipaUk,
                        partOfSpeech = savedWord.partOfSpeech,
                        cefrLevel = savedWord.cefrLevel,
                        contextMeaningVi = savedWord.contextMeaningVi,
                        englishDefinition = res.englishDefinition,
                        etymology = savedWord.etymology,
                        collocationsJson = encodeCollocations(res.collocations),
                        aiExampleSentencesJson = savedWord.aiExampleSentencesJson,
                        updatedAt = now
                    )
                }
                artCache[savedWord.id] = res
                runCatching {
                    settingsStore?.putString(
                        "art_meta_${savedWord.id}",
                        json.encodeToString(FlashcardArtResponse.serializer(), res)
                    )
                }
            }
            res
        } catch (_: Exception) { null }
    }

    suspend fun weaveContextStory(words: List<String>, bookTitle: String = "", domain: String = ""): ContextStoryResponse =
        englishApi.weaveContextStory(words = words, bookTitle = bookTitle, domain = domain)

    suspend fun getMatureWordsForProgressive(minStability: Double = 14.0): List<MatureWordPayload> {
        val all = savedWordDao.getAllWordsOnce()
        val mature = all.filter { it.stability >= minStability }
        val target = if (mature.size >= 5) mature else all
        return target.map {
            MatureWordPayload(
                word = it.word,
                meaningVi = it.contextMeaningVi,
                stability = it.stability
            )
        }
    }

    suspend fun weaveProgressiveChapter(
        paragraphs: List<String>,
        targetRatio: Double = 0.5
    ): ProgressiveChapterResponse {
        val words = getMatureWordsForProgressive()
        if (words.isEmpty()) {
            return ProgressiveChapterResponse(paragraphs = paragraphs, replacedCount = 0)
        }
        return englishApi.progressiveChapter(paragraphs, words, targetRatio)
    }
}
