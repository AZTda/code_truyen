package com.truyencloud.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Grammar Pattern Recognition (Feature #4)
// ---------------------------------------------------------------------------

@Serializable
data class GrammarExample(
    val sentence: String = "",
    @SerialName("novel_id") val novelId: String = "",
    @SerialName("novel_title") val novelTitle: String = "",
    @SerialName("chapter_number") val chapterNumber: Int = 0,
    @SerialName("recorded_at") val recordedAt: Long = 0L
)

@Serializable
data class GrammarPatternItem(
    val id: String = "",
    val pattern: String = "",
    @SerialName("pattern_name") val patternName: String = "",
    val category: String = "",
    @SerialName("explanation_vi") val explanationVi: String = "",
    val frequency: Int = 0,
    val examples: List<GrammarExample> = emptyList(),
    @SerialName("last_seen_at") val lastSeenAt: Long = 0L
)

@Serializable
data class GrammarStatsResponse(
    @SerialName("total_patterns_cataloged") val totalCataloged: Int = 0,
    @SerialName("total_patterns_encountered") val totalEncountered: Int = 0,
    @SerialName("total_occurrences") val totalOccurrences: Int = 0,
    @SerialName("category_distribution") val categoryDistribution: Map<String, Int> = emptyMap(),
    val patterns: List<GrammarPatternItem> = emptyList()
)

@Serializable
data class ExtractGrammarRequest(
    val text: String,
    @SerialName("novel_id") val novelId: String = "",
    @SerialName("chapter_number") val chapterNumber: Int = 0,
    @SerialName("novel_title") val novelTitle: String = "",
    @SerialName("auto_record") val autoRecord: Boolean = true
)

@Serializable
data class DetectedGrammarPattern(
    @SerialName("pattern_id") val patternId: String = "",
    val pattern: String = "",
    @SerialName("pattern_name") val patternName: String = "",
    val category: String = "",
    @SerialName("explanation_vi") val explanationVi: String = "",
    @SerialName("matched_sentence") val matchedSentence: String = "",
    @SerialName("novel_id") val novelId: String = "",
    @SerialName("chapter_number") val chapterNumber: Int = 0,
    @SerialName("novel_title") val novelTitle: String = ""
)

@Serializable
data class ExtractGrammarResponse(
    @SerialName("detected_count") val detectedCount: Int = 0,
    val patterns: List<DetectedGrammarPattern> = emptyList()
)

// ---------------------------------------------------------------------------
// Progressive English Reading (Feature #5 - Diglot Weave)
// ---------------------------------------------------------------------------

@Serializable
data class MatureWordPayload(
    val word: String,
    @SerialName("meaning_vi") val meaningVi: String = "",
    val stability: Double = 0.0
)

@Serializable
data class ProgressiveChapterRequest(
    val paragraphs: List<String>,
    @SerialName("mature_words") val matureWords: List<MatureWordPayload>,
    @SerialName("target_ratio") val targetRatio: Double = 0.5
)

@Serializable
data class ProgressiveGlossaryItem(
    val english: String = "",
    val vietnamese: String = "",
    val count: Int = 0
)

@Serializable
data class ProgressiveChapterResponse(
    val paragraphs: List<String> = emptyList(),
    @SerialName("replaced_count") val replacedCount: Int = 0,
    @SerialName("total_candidates") val totalCandidates: Int = 0,
    @SerialName("target_ratio") val targetRatio: Double = 0.5,
    @SerialName("ratio_achieved") val ratioAchieved: Double = 0.0,
    val glossary: List<ProgressiveGlossaryItem> = emptyList()
)

// ---------------------------------------------------------------------------
// Reading Comprehension Questions (Feature #6)
// ---------------------------------------------------------------------------

@Serializable
data class ComprehensionQuestionItem(
    val id: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    @SerialName("correct_index") val correctIndex: Int = 0,
    val explanation: String = "",
    @SerialName("focus_type") val focusType: String = "plot"
)

@Serializable
data class ComprehensionRequest(
    val title: String,
    val paragraphs: List<String>,
    @SerialName("novel_id") val novelId: String = "",
    @SerialName("chapter_number") val chapterNumber: Int = 0
)

@Serializable
data class ComprehensionResponse(
    @SerialName("chapter_title") val chapterTitle: String = "",
    val questions: List<ComprehensionQuestionItem> = emptyList(),
    /** true: bộ câu hỏi MẪU của máy chủ (AI không tạo được), không phải câu hỏi thật về chương — không hiện, không lưu. */
    @SerialName("is_fallback") val isFallback: Boolean = false,
    val error: String? = null
)

// ---------------------------------------------------------------------------
// Word Relationship Graph (Feature #7)
// ---------------------------------------------------------------------------

@Serializable
data class WordGraphNode(
    val id: String = "",
    val word: String = "",
    @SerialName("meaning_vi") val meaningVi: String = "",
    @SerialName("cefr_level") val cefrLevel: String = "c1",
    val cluster: String = "",
    @SerialName("mastery_stage") val masteryStage: String = "learning",
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val size: Float = 20f
)

@Serializable
data class WordGraphEdge(
    val source: String = "",
    val target: String = "",
    val relation: String = "synonym",
    val weight: Float = 0.8f
)

@Serializable
data class WordGraphCluster(
    val name: String = "",
    val count: Int = 0,
    val color: String = "#D4AF37",
    val bg: String = "#252115"
)

@Serializable
data class WordGraphResponse(
    @SerialName("total_nodes") val totalNodes: Int = 0,
    @SerialName("total_edges") val totalEdges: Int = 0,
    val clusters: List<WordGraphCluster> = emptyList(),
    val nodes: List<WordGraphNode> = emptyList(),
    val edges: List<WordGraphEdge> = emptyList()
)

@Serializable
data class WordGraphPayloadItem(
    val word: String,
    @SerialName("meaning_vi") val meaningVi: String = "",
    @SerialName("cefr_level") val cefrLevel: String = "c1",
    val stage: String = "learning"
)

@Serializable
data class WordGraphRequest(
    val words: List<WordGraphPayloadItem>? = null,
    val limit: Int = 60
)

// ---------------------------------------------------------------------------
// Pronunciation Practice & Evaluation (Feature #8)
// ---------------------------------------------------------------------------

/**
 * Một đoạn căn chỉnh THẬT giữa từ đích và những gì bộ nhận dạng giọng nói nghe được:
 * với từ đơn là từng chữ cái, với cụm từ là từng từ. `status`: correct | acceptable | improve.
 */
@Serializable
data class PronunciationPhoneme(
    val symbol: String = "",
    val label: String = "",
    val heard: String = "",
    val score: Int = 0,
    val status: String = "correct",
    /** match | sub | missing */
    val op: String = ""
)

/** Gợi ý IPA tham khảo (không có điểm) cho vài từ phổ biến. */
@Serializable
data class PronunciationReferencePhoneme(
    val symbol: String = "",
    val label: String = "",
    val difficulty: String = "easy"
)

@Serializable
data class PronunciationEvaluateRequest(
    val word: String,
    @SerialName("target_ipa") val targetIpa: String = "",
    /** Bản chép lời THẬT do bộ nhận dạng giọng nói của thiết bị trả về — không bao giờ là chính từ đích. */
    @SerialName("spoken_text") val spokenText: String = ""
)

@Serializable
data class PronunciationEvaluateResponse(
    val word: String = "",
    @SerialName("target_ipa") val targetIpa: String = "",
    /** null khi không nghe được gì (xem [isFallback]) — KHÔNG được hiển thị điểm. */
    val score: Int? = null,
    @SerialName("overall_score") val overallScore: Int? = null,
    @SerialName("is_fallback") val isFallback: Boolean = false,
    val message: String = "",
    val transcript: String = "",
    val heard: String = "",
    @SerialName("alignment_unit") val alignmentUnit: String = "",
    @SerialName("accuracy_level") val accuracyLevel: String = "good",
    val phonemes: List<PronunciationPhoneme> = emptyList(),
    @SerialName("reference_phonemes") val referencePhonemes: List<PronunciationReferencePhoneme> = emptyList(),
    @SerialName("feedback_vi") val feedbackVi: String = "",
    @SerialName("common_mistakes") val commonMistakes: List<String> = emptyList()
) {
    /** Điểm 0..100 nếu có điểm thật, ngược lại null. Ưu tiên `score`, lùi về `overall_score` của máy chủ cũ. */
    val realScore: Int? get() = if (isFallback) null else (score ?: overallScore)
}
