package com.truyencloud.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Tra từ
// ---------------------------------------------------------------------------

@Serializable
data class WordExplainRequest(
    val word: String,
    val context: String? = null,
    @SerialName("book_title") val bookTitle: String? = null
)

@Serializable
data class WordExplainResponse(
    val word: String,
    @SerialName("ipa_us") val ipaUs: String = "",
    @SerialName("ipa_uk") val ipaUk: String = "",
    @SerialName("part_of_speech") val partOfSpeech: String = "",
    @SerialName("cefr_level") val cefrLevel: String = "",
    @SerialName("context_meaning_vi") val contextMeaningVi: String = "",
    @SerialName("english_definition") val englishDefinition: String = "",
    val etymology: String = "",
    val collocations: List<String> = emptyList(),
    @SerialName("example_sentences") val exampleSentences: List<String> = emptyList(),
    @SerialName("is_fallback") val isFallback: Boolean = false,
    val error: String = "",
    val cached: Boolean = false
) {
    val hasContent: Boolean
        get() = !isFallback && (contextMeaningVi.isNotBlank() || englishDefinition.isNotBlank())
}

@Serializable
data class WordsExplainBatchRequest(
    val words: List<String>,
    val context: String? = null,
    @SerialName("book_title") val bookTitle: String? = null
)

@Serializable
data class WordsExplainBatchResponse(
    val results: List<WordExplainResponse> = emptyList(),
    val total: Int = 0,
    @SerialName("from_cache") val fromCache: Int = 0
)

// ---------------------------------------------------------------------------
// Phân tích câu
// ---------------------------------------------------------------------------

@Serializable
data class GrammarPoint(
    val structure: String = "",
    val explanation: String = ""
)

@Serializable
data class KeyVocab(
    val word: String = "",
    val meaning: String = ""
)

@Serializable
data class SentenceTranslateRequest(val sentence: String)

@Serializable
data class SentenceTranslateResponse(
    @SerialName("english_sentence") val englishSentence: String = "",
    @SerialName("vietnamese_translation") val vietnameseTranslation: String = "",
    @SerialName("grammar_points") val grammarPoints: List<GrammarPoint> = emptyList(),
    @SerialName("key_vocabulary") val keyVocabulary: List<KeyVocab> = emptyList(),
    @SerialName("is_fallback") val isFallback: Boolean = false,
    val error: String = "",
    val cached: Boolean = false
)

// ---------------------------------------------------------------------------
// Chuyển thể chương
// ---------------------------------------------------------------------------

@Serializable
data class AdaptChapterRequest(
    val title: String,
    val paragraphs: List<String>,
    val level: String = "c1"
)

@Serializable
data class AdaptChapterResponse(
    @SerialName("title_en") val titleEn: String = "",
    @SerialName("paragraphs_en") val paragraphsEn: List<String> = emptyList(),
    @SerialName("paragraphs_vi") val paragraphsVi: List<String> = emptyList(),
    @SerialName("total_paragraphs") val totalParagraphs: Int = 0,
    @SerialName("failed_chunks") val failedChunks: Int = 0,
    @SerialName("is_fallback") val isFallback: Boolean = false
) {
    val isPartial: Boolean get() = failedChunks > 0 && !isFallback
}

// ---------------------------------------------------------------------------
// Tình huống đời sống
// ---------------------------------------------------------------------------

@Serializable
data class ScenarioDetail(
    val title: String = "",
    @SerialName("dialogue_or_sentence") val dialogueOrSentence: String = "",
    @SerialName("meaning_vi") val meaningVi: String = "",
    @SerialName("usage_tip") val usageTip: String = ""
)

@Serializable
data class LifeScenariosRequest(
    val word: String,
    @SerialName("context_meaning_vi") val contextMeaningVi: String? = null,
    @SerialName("part_of_speech") val partOfSpeech: String? = null,
    @SerialName("cefr_level") val cefrLevel: String? = "B2"
)

@Serializable
data class LifeScenariosResponse(
    val word: String = "",
    @SerialName("workplace_scenario") val workplaceScenario: ScenarioDetail = ScenarioDetail(),
    @SerialName("casual_scenario") val casualScenario: ScenarioDetail = ScenarioDetail(),
    @SerialName("native_tip") val nativeTip: String = "",
    @SerialName("is_fallback") val isFallback: Boolean = false,
    val error: String = "",
    val cached: Boolean = false
)

// ---------------------------------------------------------------------------
// Chấm câu người học
// ---------------------------------------------------------------------------

@Serializable
data class SentenceEvaluateRequest(
    val word: String,
    val sentence: String,
    @SerialName("context_type") val contextType: String? = "daily"
)

@Serializable
data class SentenceEvaluateResponse(
    val word: String = "",
    @SerialName("user_sentence") val userSentence: String = "",
    @SerialName("is_correct") val isCorrect: Boolean = false,
    val score: Int = 0,
    @SerialName("feedback_vi") val feedbackVi: String = "",
    @SerialName("corrected_sentence") val correctedSentence: String = "",
    @SerialName("native_version") val nativeVersion: String = "",
    @SerialName("native_explanation_vi") val nativeExplanationVi: String = "",
    @SerialName("is_fallback") val isFallback: Boolean = false,
    val error: String = ""
)

// ---------------------------------------------------------------------------
// Quiz tình huống
// ---------------------------------------------------------------------------

@Serializable
data class ScenarioQuizRequest(val words: List<String>)

@Serializable
data class QuizQuestion(
    val id: Int = 0,
    val scenario: String = "",
    @SerialName("target_word") val targetWord: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    @SerialName("correct_answer_index") val correctAnswerIndex: Int = 0,
    @SerialName("explanation_vi") val explanationVi: String = ""
) {
    val isValid: Boolean
        get() = options.size >= 2 && correctAnswerIndex in options.indices && question.isNotBlank()
}

@Serializable
data class ScenarioQuizResponse(
    val questions: List<QuizQuestion> = emptyList()
) {
    val validQuestions: List<QuizQuestion> get() = questions.filter { it.isValid }
}

// ---------------------------------------------------------------------------
// Flashcard art & vi truyện
// ---------------------------------------------------------------------------

@Serializable
data class FlashcardArtRequest(
    val word: String,
    val ipa: String = "",
    @SerialName("cefr_level") val cefrLevel: String = "C1",
    val meaning: String = "",
    val context: String = ""
)

@Serializable
data class CollocationItem(
    val phrase: String = "",
    val meaning: String = ""
)

@Serializable
data class FlashcardArtResponse(
    val word: String = "",
    @SerialName("image_url") val imageUrl: String = "",
    val mnemonic: String = "",
    @SerialName("palette_hex") val paletteHex: String = "#D488FF",
    @SerialName("art_title") val artTitle: String = "",
    val collocations: List<CollocationItem> = emptyList(),
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
    @SerialName("english_definition") val englishDefinition: String = "",
    @SerialName("context_translation_vi") val contextTranslationVi: String = "",
    @SerialName("is_fallback") val isFallback: Boolean = false,
    val cached: Boolean = false
)

@Serializable
data class ContextStoryRequest(
    val words: List<String>,
    @SerialName("book_title") val bookTitle: String = "",
    val domain: String = ""
)

@Serializable
data class StoryHighlight(
    val word: String = "",
    val collocation: String = "",
    val explanation: String = ""
)

@Serializable
data class ContextStoryResponse(
    @SerialName("english_story") val englishStory: String = "",
    @SerialName("vietnamese_translation") val vietnameseTranslation: String = "",
    val highlights: List<StoryHighlight> = emptyList()
)
