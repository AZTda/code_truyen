package com.truyencloud.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dòng ngăn cảnh ("***", "——", "◆◆◆") không có âm nào: máy chủ không tổng hợp được (500), bộ đọc coi là giọng online hỏng
 * và cả chương còn lại bị đọc bằng giọng máy ~3 phút. Những mảnh này phải bị bỏ qua như mảnh trống.
 */
class TtsSegmentPlanSpeakableTest {

    private val texts = listOf("Câu một.", "***", "● ● ●", "Câu hai.", "——", "……", "1998", "")

    @Test
    fun mazhChiCoDauCauVaKyHieuKhongDocDuoc() {
        for (s in listOf("***", "* * *", "......", "——", "……", "◆◆◆", "※※※", "「」", "---", "   ", "")) {
            assertFalse(TtsSegmentPlan.isSpeakable(s), "'$s' không có âm nào")
        }
    }

    @Test
    fun chuSoVaChuCaiCuaMoiThuTiengDocDuoc() {
        for (s in listOf("12", "1998", "Ừ.", "Minh.", "你好", "Hello", "a", "…… Ừ ……", "𠀀")) {
            assertTrue(TtsSegmentPlan.isSpeakable(s), "'$s' đọc được")
        }
    }

    @Test
    fun timManhKeBoQuaNganCach() {
        assertEquals(0, TtsSegmentPlan.nextSpeakable(texts, 0))
        assertEquals(3, TtsSegmentPlan.nextSpeakable(texts, 1))
        assertEquals(6, TtsSegmentPlan.nextSpeakable(texts, 4))
        assertEquals(-1, TtsSegmentPlan.nextSpeakable(texts, 7))
    }

    @Test
    fun boDemVaGoiYTaiTruocKhongBaoGioChuaNganCach() {
        assertEquals(listOf(3, 6), TtsSegmentPlan.bufferAhead(texts, 0))
        assertEquals("Câu hai.", TtsSegmentPlan.prefetchHint(texts, 0))
        assertEquals("1998", TtsSegmentPlan.prefetchHint(texts, 3))
        assertNull(TtsSegmentPlan.prefetchHint(texts, 6))
    }
}
