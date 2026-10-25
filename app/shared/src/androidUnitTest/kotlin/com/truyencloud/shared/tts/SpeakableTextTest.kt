package com.truyencloud.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeakableTextTest {

    @Test
    fun separatorLinesAreNotSpeakable() {
        // Máy chủ trả 500 cho từng chuỗi này (kiểm tra với edge-tts): mỗi lần là một lần cầu dao hạ phiên xuống giọng máy
        listOf("", "   ", "***", "* * *", "——", "—", "...", "……", "(...)", "◆◆◆", "☆☆☆", "「」", "【】", "~~~", "---", "?!")
            .forEach { assertFalse(SpeakableText.isSpeakable(it), "'$it' không được đọc") }
    }

    @Test
    fun anyLetterOrDigitIsSpeakable() {
        listOf("Xin chào", "Ừ.", "1.", "2024", "好", "Hello!", "“Vâng.”", "* Chương 1 *")
            .forEach { assertTrue(SpeakableText.isSpeakable(it), "'$it' phải được đọc") }
    }

    @Test
    fun aheadSkipsSeparators() {
        val texts = listOf("một", "***", "hai", "——", "ba", "bốn")
        assertEquals(listOf(2, 4), SpeakableText.ahead(texts, 0, 2))
        assertEquals(listOf(4, 5), SpeakableText.ahead(texts, 2, 2))
        assertEquals(emptyList(), SpeakableText.ahead(texts, 5, 2))
    }

    @Test
    fun hintAfterIsTheNextSpeakableText() {
        val texts = listOf("một", "***", " hai ", "***")
        assertEquals("hai", SpeakableText.hintAfter(texts, 0))
        assertNull(SpeakableText.hintAfter(texts, 2))
    }

    @Test
    fun nextSpeakable() {
        val texts = listOf("***", "", "ba", "...")
        assertEquals(2, SpeakableText.nextSpeakable(texts, 0))
        assertEquals(-1, SpeakableText.nextSpeakable(texts, 3))
    }
}
