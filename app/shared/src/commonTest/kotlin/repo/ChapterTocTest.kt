package com.truyencloud.shared.repo

import com.truyencloud.shared.data.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChapterTocTest {
    private fun ch(n: Int, title: String = "T$n", titleVi: String = "", url: String = "", translated: String? = null) =
        Chapter(id = "novel_$n", novelId = "novel", chapterNumber = n, title = title, titleVi = titleVi, url = url, translatedContent = translated)

    @Test
    fun unchangedTocNeedsNoWrites() {
        val existing = (1..3).associateWith { ch(it, titleVi = "V$it", url = "u$it") }
        val remote = (1..3).map { ch(it, titleVi = "V$it", url = "u$it") }
        assertTrue(ChapterToc.diff(existing, remote).isEmpty)
    }

    @Test
    fun newChaptersAreInsertedAndExistingOnesLeftAlone() {
        val existing = (1..2).associateWith { ch(it) }
        val diff = ChapterToc.diff(existing, (1..4).map { ch(it) })
        assertEquals(listOf(3, 4), diff.toInsert.map { it.chapterNumber })
        assertTrue(diff.toUpdate.isEmpty())
    }

    @Test
    fun changedTitleOrUrlIsUpdatedAsMetadataOnly() {
        val existing = mapOf(1 to ch(1, title = "Old", titleVi = "Cũ", url = "a"))
        val diff = ChapterToc.diff(existing, listOf(ch(1, title = "New", titleVi = "Cũ", url = "a")))
        assertEquals(1, diff.toUpdate.size)
        val (local, remote) = diff.toUpdate.single()
        assertEquals(Triple("New", "Cũ", "a"), ChapterToc.mergedMetadata(local, remote))
    }

    @Test
    fun blankRemoteFieldsNeverEraseStoredOnes() {
        val local = ch(1, title = "Giữ", titleVi = "Bản dịch tiêu đề", url = "https://x")
        val remote = ch(1, title = "", titleVi = "", url = "")
        assertFalse(ChapterToc.needsMetadataUpdate(local, remote))
        assertEquals(Triple("Giữ", "Bản dịch tiêu đề", "https://x"), ChapterToc.mergedMetadata(local, remote))
    }

    @Test
    fun remoteTocNeverCarriesContentIntoTheDiff() {
        // Làm mới mục lục chỉ đụng metadata: nội dung/bản dịch đã lưu không nằm trong bản cập nhật.
        val existing = mapOf(1 to ch(1, translated = "đã dịch"))
        val diff = ChapterToc.diff(existing, listOf(ch(1, title = "Đổi tên")))
        val (local, remote) = diff.toUpdate.single()
        assertEquals("đã dịch", local.translatedContent)
        assertEquals(null, remote.translatedContent)
    }
}
