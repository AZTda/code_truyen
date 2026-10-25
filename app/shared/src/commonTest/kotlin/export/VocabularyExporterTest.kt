package com.truyencloud.shared.export

import com.truyencloud.shared.data.SavedWord
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VocabularyExporterTest {
    private val day = 86_400_000L

    private val studied = SavedWord(
        id = "word_ephemeral", word = "ephemeral", ipaUs = "/ɪˈfem.ər.əl/", partOfSpeech = "adjective",
        cefrLevel = "C1", contextMeaningVi = "phù du, ngắn ngủi", englishDefinition = "lasting a very short time",
        contextSentence = "Youth & \"beauty\" are <ephemeral>.", bookTitle = "Chuyện Tình Boston",
        stability = 12.5, fsrsState = 2, reps = 4,
        dueEpoch = 20_454L * day + 12 * 3_600_000L // 2026-01-01 12:00 UTC
    )
    private val fresh = SavedWord(id = "word_new", word = "new", fsrsState = 0, reps = 0, dueEpoch = 0)

    @Test
    fun ankiCoBaDongChiThiDauTep() {
        val (name, content) = VocabularyExporter.export(listOf(studied), VocabularyExporter.Format.TSV_ANKI)
        assertEquals("truyencloud_vocab.txt", name)
        val lines = content.lines()
        assertEquals("#separator:tab", lines[0])
        assertEquals("#html:true", lines[1])
        assertEquals("#tags column:3", lines[2])
    }

    @Test
    fun ankiMoiTheLaBaCotVaThoatHtml() {
        val content = VocabularyExporter.export(listOf(studied), VocabularyExporter.Format.TSV_ANKI).second
        val row = content.lines().first { !it.startsWith("#") && it.isNotBlank() }
        val cols = row.split('\t')
        assertEquals(3, cols.size, "front, back, tags: $row")
        assertTrue(cols[0].startsWith("ephemeral"))
        assertTrue(cols[1].contains("&amp;") && cols[1].contains("&lt;ephemeral&gt;"), "Văn bản người dùng phải được thoát HTML: ${cols[1]}")
        assertTrue(cols[1].contains("<br>"), "Xuống dòng của HTML là <br>")
        assertFalse(cols[2].contains('\t'))
        // thẻ không chứa dấu cách (Anki tách thẻ bằng dấu cách)
        assertTrue(cols[2].split(' ').contains("Chuyện_Tình_Boston"), cols[2])
        assertTrue(cols[2].split(' ').contains("C1"))
    }

    @Test
    fun csvCoBomUtf8ChoExcel() {
        val csv = VocabularyExporter.export(listOf(studied), VocabularyExporter.Format.CSV).second
        assertTrue(csv.startsWith("﻿"), "Thiếu BOM thì Excel mở tiếng Việt sai bảng mã")
        assertTrue(csv.removePrefix("﻿").startsWith("Word,"))
    }

    @Test
    fun csvNgayDenHanDangIso() {
        val csv = VocabularyExporter.export(listOf(studied), VocabularyExporter.Format.CSV).second
        val header = csv.removePrefix("﻿").lines().first()
        assertTrue(header.endsWith("Due Date"))
        val row = csv.lines().first { it.contains("ephemeral") }
        // ngày theo giờ địa phương của máy chạy kiểm thử nên chấp nhận 31/12 hoặc 01/01 hoặc 02/01 quanh mốc UTC
        val iso = Regex("\\d{4}-\\d{2}-\\d{2}").findAll(row).last().value
        assertTrue(iso in listOf("2025-12-31", "2026-01-01", "2026-01-02"), iso)
        assertFalse(row.endsWith(studied.dueEpoch.toString()), "Không xuất epoch thô")
    }

    @Test
    fun ngayDenHanTheoMuiGioCoDinh() {
        assertEquals("2026-01-01", VocabularyExporter.dueIsoDate(studied, utcOffsetMillis = 0L))
        // 12:00 UTC + 13h = 01:00 ngày 02/01 ở múi giờ +13
        assertEquals("2026-01-02", VocabularyExporter.dueIsoDate(studied, utcOffsetMillis = 13 * 3_600_000L))
        assertEquals("", VocabularyExporter.dueIsoDate(fresh, utcOffsetMillis = 0L), "Thẻ mới chưa có hạn")
    }

    @Test
    fun csvThoatDauPhayVaNhayKep() {
        val csv = VocabularyExporter.export(listOf(studied), VocabularyExporter.Format.CSV).second
        assertTrue(csv.contains("\"phù du, ngắn ngủi\""))
        assertTrue(csv.contains("\"Youth & \"\"beauty\"\" are <ephemeral>.\""))
    }

    // ---- Đầu vào thù địch: nội dung thẻ lấy từ truyện cào về và từ AI ----

    /** Mọi ký tự điều khiển U+0000..U+001F, đứng giữa chữ. */
    private val allControlChars = (0..0x1F).map { it.toChar() }.joinToString("")

    @Test
    fun jsonEscapesEveryControlCharacterSoStrictParsersAcceptTheFile() {
        val hostile = studied.copy(
            englishDefinition = "def${allControlChars}end",
            contextSentence = "line1\nline2\u0008\u000c\u000b\u0000 \"q\" \\",
            bookTitle = "Sách\u0001\u001f"
        )
        val json = VocabularyExporter.export(listOf(hostile), VocabularyExporter.Format.JSON).second

        // Ngoài các dấu xuống dòng ngăn cách giữa các phần tử, không được còn ký tự điều khiển THÔ nào.
        val raw = json.replace("\n", "").filter { it < ' ' }
        assertEquals("", raw, "Còn ký tự điều khiển chưa thoát: ${raw.map { it.code }}")

        val parsed = Json.parseToJsonElement(json) as JsonArray
        val entry = parsed.single() as JsonObject
        assertEquals("def${allControlChars}end", entry.getValue("definition_en").jsonPrimitive.content, "Giải mã lại phải ra đúng nguyên văn")
        assertEquals(hostile.contextSentence, entry.getValue("context").jsonPrimitive.content)
        assertEquals(hostile.bookTitle, entry.getValue("book").jsonPrimitive.content)
    }

    @Test
    fun jsonUsesUnicodeEscapesForTheControlCharsWithoutAShortForm() {
        assertEquals("\"\\u0000\"", ExportEscaping.json("\u0000"))
        assertEquals("\"\\u0008\"", ExportEscaping.json("\b"))
        assertEquals("\"\\u000c\"", ExportEscaping.json("\u000c"))
        assertEquals("\"\\u001f\"", ExportEscaping.json("\u001f"))
        assertEquals("\"a\\nb\\tc\\rd\"", ExportEscaping.json("a\nb\tc\rd"))
        assertEquals("\"x\\\\y\\\"z\"", ExportEscaping.json("x\\y\"z"))
        for (code in 0..0x1F) {
            val encoded = ExportEscaping.json("a${code.toChar()}b")
            assertEquals("", encoded.filter { it < ' ' }, "U+${code.toString(16).padStart(4, '0')} chưa được thoát")
            assertEquals("a${code.toChar()}b", Json.decodeFromString(String.serializer(), encoded))
        }
    }

    @Test
    fun csvNeutralisesCellsThatWouldRunAsSpreadsheetFormulas() {
        for (trigger in listOf("=", "+", "-", "@")) {
            assertEquals("'${trigger}SUM(A1)", ExportEscaping.csv("${trigger}SUM(A1)"), "Ô bắt đầu bằng $trigger")
        }
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\"", ExportEscaping.csv("=HYPERLINK(\"http://x\",\"y\")"))
        // Chữ thường và dấu đặt ở giữa ô giữ nguyên
        assertEquals("a-b", ExportEscaping.csv("a-b"))
        assertEquals("x=1", ExportEscaping.csv("x=1"))
        assertEquals("", ExportEscaping.csv(""))
    }

    @Test
    fun csvExportNeutralisesHostileCellsInEveryTextColumn() {
        val hostile = studied.copy(
            word = "=cmd", contextMeaningVi = "+84 nghĩa", englishDefinition = "-ness: suffix", contextSentence = "@mention", bookTitle = "=1+1"
        )
        val csv = VocabularyExporter.export(listOf(hostile), VocabularyExporter.Format.CSV).second
        val row = csv.removePrefix("\uFEFF").lines()[1]
        assertTrue(row.startsWith("'=cmd,"), row)
        assertTrue(row.contains("'+84 nghĩa"), row)
        assertTrue(row.contains("'-ness: suffix"), row)
        assertTrue(row.contains("'@mention"), row)
        assertTrue(row.contains("'=1+1"), row)
    }

    @Test
    fun markdownEscapesPipesInEveryCellSoTheTableKeepsItsColumns() {
        val hostile = studied.copy(word = "a|b", ipaUs = "/x|y/", partOfSpeech = "n|v", cefrLevel = "B|1", bookTitle = "Tập 1 | Tập 2")
        val md = VocabularyExporter.export(listOf(hostile), VocabularyExporter.Format.MARKDOWN).second
        val row = md.lines().first { it.startsWith("| 1 |") }
        // 7 cột => 8 dấu | cấu trúc; các | trong dữ liệu phải được thoát bằng \|
        assertEquals(8, row.replace("\\|", "").count { it == '|' }, row)
        assertTrue(row.contains("a\\|b") && row.contains("/x\\|y/") && row.contains("n\\|v") && row.contains("Tập 1 \\| Tập 2"), row)
    }

    @Test
    fun markdownEscapesBackslashesSoAnEscapedPipeCannotBeForgedFromData() {
        assertEquals("a\\\\\\|b", ExportEscaping.markdownCell("a\\|b"))
        assertEquals("một hai", ExportEscaping.markdownCell("một\nhai"))
    }

    @Test
    fun ankiExportStaysThreeColumnsForHostileInput() {
        val hostile = studied.copy(word = "x\ty", contextMeaningVi = "a\u0000\u0008b\r\nc\td", bookTitle = "=A\tB")
        val anki = VocabularyExporter.export(listOf(hostile), VocabularyExporter.Format.TSV_ANKI).second
        val row = anki.lines().first { !it.startsWith("#") && it.isNotBlank() }
        assertEquals(3, row.split('\t').size, row)
        assertTrue(!row.contains('\r'))
    }
}

