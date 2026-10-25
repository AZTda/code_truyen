package com.truyencloud.shared.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TextNormalizeTest {

    @Test
    fun collapseTrimsAndMergesEveryKindOfWhitespace() {
        assertEquals("Kiếm Lai", TextNormalize.collapse("  Kiếm \t\n  Lai\r\n"))
        assertEquals("那么远，那么近", TextNormalize.collapse("　那么远，那么近　"))
        assertEquals("a b", TextNormalize.collapse("a  b"))
        assertEquals("", TextNormalize.collapse(" \n\t　 "))
        assertEquals("", TextNormalize.collapse(""))
    }

    @Test
    fun collapseKeepsTheInnerPunctuationAndCasing() {
        assertEquals("Mr.Nobody, Vol.2 / 3", TextNormalize.collapse(" Mr.Nobody,   Vol.2  /  3 "))
    }

    @Test
    fun keyIgnoresCaseAndSpacingButNotAccents() {
        assertEquals(TextNormalize.key("Kiếm  Lai"), TextNormalize.key(" KIẾM lai "))
        assertEquals(TextNormalize.key("Đấu La"), TextNormalize.key("đấu la"))
        assertNotEquals(TextNormalize.key("Kiếm Lai"), TextNormalize.key("Kiem Lai"))
    }
}
