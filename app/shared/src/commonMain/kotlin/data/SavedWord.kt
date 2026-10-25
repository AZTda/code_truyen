package com.truyencloud.shared.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
@Entity(
    tableName = "saved_words",
    indices = [
        // Giu unique tren `word` nhu ban v7. KHONG duoc dat unique tren (lemma, senseKey):
        // moi dong du lieu cu deu co lemma = "" nen index unique se vo ngay khi migrate.
        Index(value = ["word"], unique = true),
        Index(value = ["lemma"]),
        Index(value = ["dueEpoch"]),
        Index(value = ["novelId"])
    ]
)
data class SavedWord(
    @PrimaryKey
    val id: String,

    // ---- Nội dung từ điển -------------------------------------------------
    val word: String,
    /**
     * Dạng nguyên thể dùng làm khóa gộp thẻ: "ran"/"running"/"runs" đều về "run",
     * nên người học không bị ba thẻ riêng cho cùng một từ.
     */
    @ColumnInfo(defaultValue = "")
    val lemma: String = "",
    /**
     * Phân biệt các nghĩa khác nhau của cùng một chữ (spring = mùa xuân / nhảy bật).
     * Rỗng = nghĩa mặc định.
     */
    @ColumnInfo(defaultValue = "")
    val senseKey: String = "",
    val ipaUs: String = "",
    val ipaUk: String = "",
    val partOfSpeech: String = "",
    val cefrLevel: String = "B2",
    val contextMeaningVi: String = "",
    val englishDefinition: String = "",
    val etymology: String = "",
    val collocationsJson: String = "[]",

    // ---- Ngữ cảnh gốc trong tiểu thuyết ------------------------------------
    // Đây là tài sản khác biệt của TruyenCloud: câu THẬT nơi người học gặp từ.
    /** Câu nguyên văn trong truyện, không phải câu ví dụ do AI bịa ra. */
    val contextSentence: String = "",
    val bookTitle: String = "",
    @ColumnInfo(defaultValue = "")
    val novelId: String = "",
    @ColumnInfo(defaultValue = "0")
    val chapterNumber: Int = 0,
    /** Vị trí ký tự của từ trong câu — để tô sáng và tạo bài điền khuyết (cloze). */
    @ColumnInfo(defaultValue = "-1")
    val contextCharOffset: Int = -1,
    /** Câu ví dụ do AI sinh, tách riêng khỏi câu gốc trong truyện. */
    @ColumnInfo(defaultValue = "[]")
    val aiExampleSentencesJson: String = "[]",

    // ---- Lịch ôn tập FSRS --------------------------------------------------
    /** Độ bền ký ức (ngày để xác suất nhớ rơi xuống 90%). */
    @ColumnInfo(defaultValue = "0.0")
    val stability: Double = 0.0,
    /** Độ khó nội tại của thẻ, thang 1..10. */
    @ColumnInfo(defaultValue = "0.0")
    val difficulty: Double = 0.0,
    /** 0 = Mới, 1 = Đang học, 2 = Ôn tập, 3 = Học lại. */
    @ColumnInfo(defaultValue = "0")
    val fsrsState: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val reps: Int = 0,
    /** Số lần quên — dùng để phát hiện từ "leech" (học mãi không vào). */
    @ColumnInfo(defaultValue = "0")
    val lapses: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val lastReviewEpoch: Long = 0L,
    /** Thời điểm đến hạn ôn tiếp theo. Thay thế `nextReviewEpoch` của bản cũ. */
    @ColumnInfo(defaultValue = "0")
    val dueEpoch: Long = 0L,
    /** Bị treo tạm: người học chủ động gác từ này lại. */
    @ColumnInfo(defaultValue = "0")
    val isSuspended: Boolean = false,

    // ---- Giữ lại để tương thích ngược & hiển thị --------------------------
    /** Giai đoạn theo thuật toán cũ. Chỉ còn dùng cho di trú dữ liệu và bộ lọc cũ. */
    val srsStage: Int = 0,
    /** Mốc ôn theo thuật toán cũ. Chỉ còn dùng cho di trú dữ liệu. */
    val nextReviewEpoch: Long = 0L,

    val createdAt: Long = 0L,
    val lifeScenariosJson: String = "",
    val userSentencesJson: String = "[]",
    /** Số lần trả lời đúng liên tiếp gần nhất. */
    val masteredCount: Int = 0,
    val flashcardArtUrl: String = "",
    val visualMnemonic: String = "",
    val artPaletteHex: String = "#D488FF",

    /** Mốc thời gian phục vụ đồng bộ đám mây (last-write-wins). */
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    val isSynced: Boolean = false,

    /**
     * Mốc xoá mềm (epoch ms, > 0 = đã xoá). Dòng bị xoá được GIỮ LẠI làm "bia mộ" để lần
     * đồng bộ sau báo cho máy khác — nếu xoá cứng thì máy kia đẩy bản cũ lên và từ hiện lại.
     * Mọi truy vấn hiển thị đều phải lọc `deletedAt = 0`.
     */
    @ColumnInfo(defaultValue = "0")
    val deletedAt: Long = 0L,

    /**
     * Các ngữ cảnh gặp thêm của cùng một lemma ("ran" rồi "running" gộp vào thẻ "run"):
     * JSON mảng [ExtraContext]. Ngữ cảnh đầu tiên vẫn nằm ở [contextSentence].
     */
    @ColumnInfo(defaultValue = "[]")
    val extraContextsJson: String = "[]"
) {
    /** Khóa gộp thẻ: ưu tiên lemma, lùi về word nếu chưa có lemma. */
    @get:Ignore
    val groupKey: String get() = (lemma.ifBlank { word }).lowercase() + (if (senseKey.isBlank()) "" else "#$senseKey")

    /** Có câu gốc trong truyện để tạo bài điền khuyết không. */
    @get:Ignore
    val hasBookContext: Boolean
        get() = contextSentence.isNotBlank() &&
            (contextSentence.contains(word, ignoreCase = true) ||
                (lemma.isNotBlank() && contextSentence.contains(lemma, ignoreCase = true)))

    @get:Ignore
    val isLeech: Boolean get() = lapses >= 8

    @get:Ignore
    val isDeleted: Boolean get() = deletedAt > 0L

    /** Thẻ chưa từng được học lần nào (không tính là "đến hạn", chỉ được giới thiệu theo hạn mức ngày). */
    @get:Ignore
    val isNeverStudied: Boolean get() = fsrsState == 0 || reps == 0
}

/** Một câu ngữ cảnh bổ sung gắn vào thẻ đã có (xem [SavedWord.extraContextsJson]). */
@Serializable
data class ExtraContext(
    val sentence: String,
    /** Dạng chữ thực tế gặp trong câu ("running" khi thẻ là "run"). */
    val form: String = "",
    val bookTitle: String = "",
    val novelId: String = "",
    val chapterNumber: Int = 0,
    val charOffset: Int = -1
)

private val extraContextJson = Json { ignoreUnknownKeys = true }

/** Giải mã danh sách ngữ cảnh phụ; dữ liệu hỏng thì coi như không có. */
fun decodeExtraContexts(raw: String): List<ExtraContext> = try {
    extraContextJson.decodeFromString(ListSerializer(ExtraContext.serializer()), raw.ifBlank { "[]" })
} catch (_: Exception) {
    emptyList()
}

fun SavedWord.extraContexts(): List<ExtraContext> = decodeExtraContexts(extraContextsJson)
