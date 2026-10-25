package com.truyencloud.shared.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BilingualPairsTest {

    @Test
    fun parsesAlternatingEnAndViIntoAlignedPairs() {
        val text = """
            [EN] The sword sang.
            [VI] Thanh kiếm ngân vang.

            [EN] He smiled.
            [VI] Hắn mỉm cười.
        """.trimIndent()
        assertEquals(
            listOf(
                BilingualPair("The sword sang.", "Thanh kiếm ngân vang."),
                BilingualPair("He smiled.", "Hắn mỉm cười.")
            ),
            BilingualPairs.parse(text)
        )
    }

    @Test
    fun aMissingViLineDoesNotShiftTheFollowingPairs() {
        // Ghép theo chỉ số hai danh sách tách rời sẽ cho "Hắn mỉm cười." đứng cạnh "He left." — lệch hàng.
        val text = "[EN] A\n[VI] Một\n[EN] B\n[EN] C\n[VI] Xê"
        val pairs = BilingualPairs.parse(text)
        assertEquals(listOf("A", "B", "C"), pairs.map { it.english })
        assertEquals(listOf("Một", "", "Xê"), pairs.map { it.vietnamese })
    }

    @Test
    fun tagsMayStandAloneAndParagraphsMayWrapOverSeveralLines() {
        val text = "[EN]\nLine one\nline two\n[VI]\nDòng một\ndòng hai"
        assertEquals(listOf(BilingualPair("Line one\nline two", "Dòng một\ndòng hai")), BilingualPairs.parse(text))
    }

    @Test
    fun tagsAreCaseInsensitiveAndBlankTextIsEmpty() {
        assertEquals(listOf(BilingualPair("Hi", "Chào")), BilingualPairs.parse("[en] Hi\n[vi] Chào"))
        assertTrue(BilingualPairs.parse("").isEmpty())
        assertTrue(BilingualPairs.parse("Không có thẻ nào cả").isEmpty())
    }

    @Test
    fun usableNeedsMostPairsComplete() {
        assertTrue(BilingualPairs.isUsable(BilingualPairs.parse("[EN] A\n[VI] Một\n[EN] B\n[VI] Hai")))
        assertFalse(BilingualPairs.isUsable(emptyList()))
        assertFalse(BilingualPairs.isUsable(BilingualPairs.parse("[EN] A\n[EN] B\n[EN] C\n[VI] Xê")))
    }
}
