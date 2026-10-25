package com.truyencloud.shared.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ImportedBookTest {

    @Test
    fun buildsALibraryNovelFromTheServerImportResponse() {
        val novel = assertNotNull(
            ImportedBook.parse(
                """{"status":"success","novel_id":"import_ab12cd34","title":"Kiếm Lai","author":"Tác giả","total_chapters":120,"cover_url":"https://x/c.jpg"}""",
                fallbackTitle = "tệp",
                fallbackAuthor = "tôi"
            )
        )
        assertEquals("import_ab12cd34", novel.id)
        assertEquals("Kiếm Lai", novel.displayName())
        assertEquals("Tác giả", novel.author)
        assertEquals(120, novel.totalChapters)
        assertEquals("https://x/c.jpg", novel.coverUrl)
        assertEquals(ImportedBook.SOURCE_ID, novel.sourceId)
        assertEquals(0, novel.lastReadChapter)
    }

    @Test
    fun fallsBackToWhatTheUserTypedWhenTheServerLeavesFieldsBlank() {
        val novel = assertNotNull(
            ImportedBook.parse("""{"novel_id":"import_1","title":"","author":""}""", "Tên tự nhập", "Người nhập")
        )
        assertEquals("Tên tự nhập", novel.displayName())
        assertEquals("Người nhập", novel.author)
    }

    @Test
    fun rejectsResponsesThatAreNotAValidImportResult() {
        assertNull(ImportedBook.parse("", "a", "b"))
        assertNull(ImportedBook.parse("không phải json", "a", "b"))
        assertNull(ImportedBook.parse("""{"status":"success"}""", "a", "b"))
        assertNull(ImportedBook.parse("""{"novel_id":"  "}""", "a", "b"))
    }

    private fun com.truyencloud.shared.data.Novel.displayName() = titleVi.ifBlank { title }
}
