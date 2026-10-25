package com.truyencloud.shared.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VietnameseTextTest {

    @Test
    fun foldsComposedAccents() {
        assertEquals("kiem lai", VietnameseText.fold("Kiếm Lai"))
        assertEquals("dau pha thuong khung", VietnameseText.fold("Đấu Phá Thương Khung"))
        assertEquals("pham nhan tu tien", VietnameseText.fold("PHÀM NHÂN TU TIÊN"))
    }

    @Test
    fun foldsEveryToneOnEveryVowel() {
        assertEquals("aaaaaaaaaaaaaaaaa", VietnameseText.fold("àáạảãâầấậẩẫăằắặẳẵ"))
        assertEquals("ooooooooooooooooo", VietnameseText.fold("òóọỏõôồốộổỗơờớợởỡ"))
        assertEquals("uuuuuuuuuuu", VietnameseText.fold("ùúụủũưừứựửữ"))
        assertEquals("yyyyy", VietnameseText.fold("ỳýỵỷỹ"))
        assertEquals("dd", VietnameseText.fold("đĐ"))
    }

    @Test
    fun foldsDecomposedNfdInput() {
        // "Kiếm" dạng NFD: e + dấu mũ U+0302 + dấu sắc U+0301 — viết bằng escape
        // để trình soạn thảo không tự chuẩn hóa lại chuỗi
        assertEquals("kiem", VietnameseText.fold("Kiếm"))
        assertEquals("thuong", VietnameseText.fold("thương"))
    }

    @Test
    fun leavesChineseUntouched() {
        assertEquals("剑来", VietnameseText.fold("剑来"))
    }

    @Test
    fun detectsCjk() {
        assertTrue(VietnameseText.hasCjk("剑"))
        assertTrue(VietnameseText.hasCjk("kiem 剑来"))
        assertFalse(VietnameseText.hasCjk("Kiếm Lai"))
    }
}
