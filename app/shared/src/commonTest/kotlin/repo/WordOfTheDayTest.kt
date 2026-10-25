package com.truyencloud.shared.repo

import com.truyencloud.shared.data.SavedWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WordOfTheDayTest {
    private val day = 24L * 60 * 60 * 1000

    private fun word(name: String) = SavedWord(id = "word_$name", word = name)

    @Test
    fun khoTrongThiKhongCoTuHomNay() {
        // Sau "Xoá dữ liệu trên máy" hoặc với tài khoản mới: thẻ phải biến mất, không còn từ của tài khoản trước.
        assertNull(WordOfTheDay.pick(emptyList(), 1_790_000_000_000L))
    }

    @Test
    fun motTuCoDinhTrongNgayVaXoayVongTheoNgay() {
        val words = listOf(word("a"), word("b"), word("c"))
        val base = 20_000L * day
        val today = WordOfTheDay.pick(words, base + 5_000)
        assertEquals(today, WordOfTheDay.pick(words, base + day - 1), "cả ngày dùng cùng một từ")
        assertEquals(words[((base / day + 1) % 3).toInt()], WordOfTheDay.pick(words, base + day), "sang ngày thì xoay sang từ kế")
    }

    @Test
    fun khoNhoLaiThiVanChonTrongKhoMoi() {
        // Chỉ số tính theo kích thước kho HIỆN TẠI nên không bao giờ vượt cỡ kho sau khi đồng bộ về ít từ hơn.
        val now = 20_123L * day
        for (size in 1..6) {
            val words = (1..size).map { word("w$it") }
            assertEquals(words[((now / day) % size).toInt()], WordOfTheDay.pick(words, now))
        }
    }
}
