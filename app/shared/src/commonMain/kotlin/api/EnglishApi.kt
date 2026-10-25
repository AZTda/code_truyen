package com.truyencloud.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * Ktor HTTP Client cho phân hệ English Immersion, Grammar, SRS & Vocabulary Studio.
 *
 * Mọi lời gọi ở đây đều chạm vào Antigravity CLI phía máy chủ (25-90 giây),
 * nên nới thời gian chờ riêng thay vì dùng mặc định của client.
 */
class EnglishApi(
    private val client: HttpClient = TruyenCloudApi.httpClient,
    private val baseUrl: () -> String = { TruyenCloudApi.baseUrl }
) {
    private val base get() = baseUrl().trimEnd('/')

    private suspend inline fun <reified TReq : Any, reified TRes> postJson(
        path: String,
        body: TReq,
        timeoutMs: Long = TruyenCloudApi.AI_REQUEST_TIMEOUT_MS
    ): TRes {
        val resp = client.post("$base$path") {
            contentType(ContentType.Application.Json)
            setBody(body)
            timeout {
                requestTimeoutMillis = timeoutMs
                socketTimeoutMillis = timeoutMs
            }
        }
        if (!resp.status.isSuccess()) {
            throw IllegalStateException("API error (${resp.status.value}): ${resp.bodyAsText()}")
        }
        return resp.body()
    }

    private suspend inline fun <reified TRes> getJson(
        path: String,
        timeoutMs: Long = 15_000L
    ): TRes {
        val resp = client.get("$base$path") {
            contentType(ContentType.Application.Json)
            timeout {
                requestTimeoutMillis = timeoutMs
                socketTimeoutMillis = timeoutMs
            }
        }
        if (!resp.status.isSuccess()) {
            throw IllegalStateException("API error (${resp.status.value}): ${resp.bodyAsText()}")
        }
        return resp.body()
    }

    suspend fun explainWord(word: String, context: String? = null, bookTitle: String? = null): WordExplainResponse =
        postJson("/v1/english/explain-word", WordExplainRequest(word, context, bookTitle))

    /** Tra nhiều từ một lượt — dùng khi nạp trước từ khó của cả chương. */
    suspend fun explainWordsBatch(
        words: List<String>,
        context: String? = null,
        bookTitle: String? = null
    ): List<WordExplainResponse> {
        if (words.isEmpty()) return emptyList()
        val res: WordsExplainBatchResponse = postJson(
            "/v1/english/explain-words-batch",
            WordsExplainBatchRequest(words, context, bookTitle),
            timeoutMs = TruyenCloudApi.AI_BATCH_TIMEOUT_MS
        )
        return res.results
    }

    suspend fun translateSentence(sentence: String): SentenceTranslateResponse =
        postJson("/v1/english/translate-sentence", SentenceTranslateRequest(sentence))

    suspend fun adaptChapter(title: String, paragraphs: List<String>, level: String = "c1"): AdaptChapterResponse =
        postJson(
            "/v1/english/adapt-chapter",
            AdaptChapterRequest(title, paragraphs, level),
            timeoutMs = TruyenCloudApi.TRANSLATE_TIMEOUT_MS
        )

    suspend fun getLifeScenarios(
        word: String,
        contextMeaningVi: String? = null,
        partOfSpeech: String? = null,
        cefrLevel: String? = "B2"
    ): LifeScenariosResponse =
        postJson(
            "/v1/english/life-scenarios",
            LifeScenariosRequest(word, contextMeaningVi, partOfSpeech, cefrLevel)
        )

    suspend fun evaluateSentence(
        word: String,
        sentence: String,
        contextType: String? = "daily"
    ): SentenceEvaluateResponse =
        postJson("/v1/english/evaluate-sentence", SentenceEvaluateRequest(word, sentence, contextType))

    suspend fun practiceQuiz(words: List<String>): ScenarioQuizResponse =
        postJson("/v1/english/practice-quiz", ScenarioQuizRequest(words))

    suspend fun generateFlashcardArt(
        word: String,
        ipa: String = "",
        cefrLevel: String = "C1",
        meaning: String = "",
        context: String = ""
    ): FlashcardArtResponse =
        postJson(
            "/v1/english/generate-flashcard-art",
            FlashcardArtRequest(word, ipa, cefrLevel, meaning, context)
        )

    suspend fun weaveContextStory(
        words: List<String>,
        bookTitle: String = "",
        domain: String = ""
    ): ContextStoryResponse =
        postJson("/v1/english/weave-context-story", ContextStoryRequest(words, bookTitle, domain))

    // ── Grammar Pattern Recognition (Feature #4) ──

    suspend fun getGrammarStats(limit: Int = 15): GrammarStatsResponse =
        getJson("/v1/english/grammar-stats?limit=$limit")

    suspend fun extractGrammarPatterns(
        text: String,
        novelId: String = "",
        chapterNumber: Int = 0,
        novelTitle: String = "",
        autoRecord: Boolean = true
    ): ExtractGrammarResponse =
        postJson(
            "/v1/english/grammar-patterns",
            ExtractGrammarRequest(
                text = text,
                novelId = novelId,
                chapterNumber = chapterNumber,
                novelTitle = novelTitle,
                autoRecord = autoRecord
            )
        )

    // ── Progressive English Reading (Feature #5 - Diglot Weave) ──

    suspend fun progressiveChapter(
        paragraphs: List<String>,
        matureWords: List<MatureWordPayload>,
        targetRatio: Double = 0.5
    ): ProgressiveChapterResponse =
        postJson(
            "/v1/english/progressive-chapter",
            ProgressiveChapterRequest(paragraphs, matureWords, targetRatio)
        )

    // ── Reading Comprehension Questions (Feature #6) ──

    suspend fun generateComprehension(
        title: String,
        paragraphs: List<String>,
        novelId: String = "",
        chapterNumber: Int = 0
    ): ComprehensionResponse =
        postJson(
            "/v1/english/comprehension-questions",
            ComprehensionRequest(title, paragraphs, novelId, chapterNumber)
        )

    // ── Word Relationship Graph (Feature #7) ──

    suspend fun getWordGraph(
        words: List<WordGraphPayloadItem>? = null,
        limit: Int = 60
    ): WordGraphResponse =
        postJson(
            "/v1/english/word-graph",
            WordGraphRequest(words, limit)
        )

    // ── Pronunciation Practice & Evaluation (Feature #8) ──

    suspend fun evaluatePronunciation(
        word: String,
        targetIpa: String = "",
        spokenText: String = ""
    ): PronunciationEvaluateResponse =
        postJson(
            "/v1/english/pronunciation/evaluate",
            PronunciationEvaluateRequest(word, targetIpa, spokenText)
        )
}
