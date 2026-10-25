package com.truyencloud.shared.export

import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.data.epochDayToDate
import com.truyencloud.shared.srs.StudyDay
import com.truyencloud.shared.srs.platformUtcOffsetMillis

/**
 * Tạo nội dung xuất từ vựng ở nhiều định dạng.
 *
 * Lớp thuần Kotlin — không phụ thuộc nền tảng Android/iOS.
 */
object VocabularyExporter {

    enum class Format(val label: String, val extension: String, val mimeType: String) {
        CSV("CSV", "csv", "text/csv"),
        TSV_ANKI("Anki (TSV)", "txt", "text/tab-separated-values"),
        JSON("JSON", "json", "application/json"),
        MARKDOWN("Markdown", "md", "text/markdown")
    }

    /** Dấu BOM UTF-8: thiếu nó Excel mở CSV bằng bảng mã hệ thống và tiếng Việt thành "?" / ký tự lạ. */
    const val UTF8_BOM = "\uFEFF"

    /** Ba dòng chỉ thị đầu tệp Anki: phân tách bằng tab, nội dung là HTML, cột 3 là thẻ (tags). */
    val ANKI_HEADER: List<String> = listOf("#separator:tab", "#html:true", "#tags column:3")

    /**
     * Xuất danh sách từ theo định dạng chỉ định.
     * @return Pair(fileName, content)
     */
    fun export(words: List<SavedWord>, format: Format): Pair<String, String> {
        val fileName = "truyencloud_vocab.${format.extension}"
        val content = when (format) {
            Format.CSV -> toCsv(words)
            Format.TSV_ANKI -> toAnkiTsv(words)
            Format.JSON -> toJson(words)
            Format.MARKDOWN -> toMarkdown(words)
        }
        return fileName to content
    }

    /**
     * Ngày đến hạn dạng ISO `YYYY-MM-DD` theo giờ địa phương; rỗng với thẻ chưa từng học.
     * Bản cũ xuất `dueEpoch` thô (1758000000000) — không đọc được trong bảng tính.
     */
    fun dueIsoDate(word: SavedWord, utcOffsetMillis: Long = platformUtcOffsetMillis(word.dueEpoch)): String {
        if (word.isNeverStudied || word.dueEpoch <= 0L) return ""
        val (y, m, d) = epochDayToDate(StudyDay.localCalendarDay(word.dueEpoch, utcOffsetMillis))
        return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }

    // -----------------------------------------------------------------------
    // CSV
    // -----------------------------------------------------------------------

    private fun toCsv(words: List<SavedWord>): String {
        val sb = StringBuilder(UTF8_BOM)
        sb.append("Word,IPA (US),IPA (UK),Part of Speech,CEFR,Meaning (Vietnamese),English Definition,Context Sentence,Book Title,Stability,Due Date\r\n")
        words.forEach { w ->
            sb.append(
                listOf(
                    w.word.csvEscape(),
                    w.ipaUs.csvEscape(),
                    w.ipaUk.csvEscape(),
                    w.partOfSpeech.csvEscape(),
                    w.cefrLevel.csvEscape(),
                    w.contextMeaningVi.csvEscape(),
                    w.englishDefinition.csvEscape(),
                    w.contextSentence.csvEscape(),
                    w.bookTitle.csvEscape(),
                    w.stability.toString(),
                    dueIsoDate(w)
                ).joinToString(",")
            ).append("\r\n")
        }
        return sb.toString()
    }

    // -----------------------------------------------------------------------
    // Anki TSV — Front: word + IPA, Back: meaning + definition + context, cột 3: thẻ
    // -----------------------------------------------------------------------

    private fun toAnkiTsv(words: List<SavedWord>): String {
        val sb = StringBuilder()
        ANKI_HEADER.forEach { sb.append(it).append('\n') }
        words.forEach { w ->
            val front = buildString {
                append(w.word.htmlEscape())
                if (w.ipaUs.isNotBlank()) append("  ${w.ipaUs.htmlEscape()}")
                if (w.partOfSpeech.isNotBlank()) append("  (${w.partOfSpeech.htmlEscape()})")
            }
            val back = buildString {
                if (w.contextMeaningVi.isNotBlank()) append(w.contextMeaningVi.htmlEscape())
                if (w.englishDefinition.isNotBlank()) {
                    if (isNotEmpty()) append("<br><br>")
                    append("<i>${w.englishDefinition.htmlEscape()}</i>")
                }
                if (w.contextSentence.isNotBlank()) {
                    if (isNotEmpty()) append("<br><br>")
                    append("“${w.contextSentence.htmlEscape()}”")
                }
                if (w.bookTitle.isNotBlank()) {
                    if (isNotEmpty()) append("<br>")
                    append("— ${w.bookTitle.htmlEscape()}")
                }
            }
            val tags = buildList {
                if (w.cefrLevel.isNotBlank()) add(w.cefrLevel.ankiTag())
                if (w.bookTitle.isNotBlank()) add(w.bookTitle.ankiTag())
                add("TruyenCloud")
            }.filter { it.isNotEmpty() }.joinToString(" ")
            sb.append(front.tsvCell()).append('\t').append(back.tsvCell()).append('\t').append(tags).append('\n')
        }
        return sb.toString()
    }

    // -----------------------------------------------------------------------
    // JSON
    // -----------------------------------------------------------------------

    private fun toJson(words: List<SavedWord>): String {
        val sb = StringBuilder()
        sb.appendLine("[")
        words.forEachIndexed { i, w ->
            sb.append("  {")
            sb.append("\"word\":${w.word.jsonEscape()},")
            sb.append("\"ipa_us\":${w.ipaUs.jsonEscape()},")
            sb.append("\"ipa_uk\":${w.ipaUk.jsonEscape()},")
            sb.append("\"pos\":${w.partOfSpeech.jsonEscape()},")
            sb.append("\"cefr\":${w.cefrLevel.jsonEscape()},")
            sb.append("\"meaning_vi\":${w.contextMeaningVi.jsonEscape()},")
            sb.append("\"definition_en\":${w.englishDefinition.jsonEscape()},")
            sb.append("\"context\":${w.contextSentence.jsonEscape()},")
            sb.append("\"book\":${w.bookTitle.jsonEscape()},")
            sb.append("\"etymology\":${w.etymology.jsonEscape()},")
            sb.append("\"stability\":${w.stability},")
            sb.append("\"due\":${dueIsoDate(w).jsonEscape()},")
            sb.append("\"reps\":${w.reps},")
            sb.append("\"lapses\":${w.lapses}")
            sb.append("}")
            if (i < words.lastIndex) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("]")
        return sb.toString()
    }

    // -----------------------------------------------------------------------
    // Markdown
    // -----------------------------------------------------------------------

    private fun toMarkdown(words: List<SavedWord>): String {
        val sb = StringBuilder()
        sb.appendLine("# Từ Vựng TruyenCloud")
        sb.appendLine()
        sb.appendLine("| # | Từ | IPA | Loại | CEFR | Nghĩa | Nguồn |")
        sb.appendLine("|---|---|---|---|---|---|---|")
        words.forEachIndexed { i, w ->
            sb.appendLine(
                "| ${i + 1} | **${w.word.mdEscape()}** | ${w.ipaUs.mdEscape()} | ${w.partOfSpeech.mdEscape()} | " +
                    "${w.cefrLevel.mdEscape()} | ${w.contextMeaningVi.mdEscape()} | ${w.bookTitle.mdEscape()} |"
            )
        }
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine("*Xuất từ TruyenCloud — ${words.size} từ*")
        return sb.toString()
    }

    // -----------------------------------------------------------------------
    // Escape helpers
    // -----------------------------------------------------------------------

    // CSV: ô bắt đầu bằng = + - @ được thêm dấu nháy đơn để bảng tính không chạy nó như công thức (xem ExportEscaping.csv).
    private fun String.csvEscape(): String = ExportEscaping.csv(this)

    /** `#html:true` => xuống dòng phải là `<br>`; tab sẽ làm lệch cột nên đổi thành dấu cách. */
    private fun String.tsvCell(): String =
        this.replace("\t", " ").replace("\r\n", "<br>").replace("\n", "<br>").replace("\r", "<br>")

    /** Gồm cả dấu nháy kép: một ô bắt đầu bằng `"` sẽ bị bộ nhập của Anki hiểu là ô được trích dẫn kiểu CSV. */
    private fun String.htmlEscape(): String =
        this.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Thẻ Anki phân tách bằng dấu cách nên tên thẻ không được chứa khoảng trắng. */
    private fun String.ankiTag(): String = this.trim().replace(Regex("\\s+"), "_")

    private fun String.jsonEscape(): String = ExportEscaping.json(this)

    private fun String.mdEscape(): String = ExportEscaping.markdownCell(this)
}
