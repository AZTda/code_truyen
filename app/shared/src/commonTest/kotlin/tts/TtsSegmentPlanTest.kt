package com.truyencloud.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TtsSegmentPlanTest {

    private val texts = listOf("a", "", "  ", "b", "c", "", "d")

    @Test
    fun boQuaDoanTrongKhiTimDoanKeTiep() {
        assertEquals(0, TtsSegmentPlan.nextSpeakable(texts, 0))
        assertEquals(3, TtsSegmentPlan.nextSpeakable(texts, 1))
        assertEquals(6, TtsSegmentPlan.nextSpeakable(texts, 5))
    }

    @Test
    fun hetChuongThiTraVeAm() {
        assertEquals(-1, TtsSegmentPlan.nextSpeakable(texts, 7))
        assertEquals(-1, TtsSegmentPlan.nextSpeakable(emptyList(), 0))
    }

    @Test
    fun chiSoAmDuocCoiLaTuDauDanh() {
        assertEquals(0, TtsSegmentPlan.nextSpeakable(texts, -5))
    }

    /** Lỗi cũ: so `nextPreparedIndex` với chính nó + 1 nên không bao giờ dùng được mảnh tải sẵn. */
    @Test
    fun manhKeTiepLuonNamTrongBoDemKhiDangPhatManhTruoc() {
        val playing = 0
        val buffer = TtsSegmentPlan.bufferAhead(texts, playing)
        val nextToPlay = TtsSegmentPlan.nextSpeakable(texts, playing + 1)
        assertEquals(nextToPlay, buffer.first(), "Mảnh sắp phát phải là mảnh đầu tiên được tải sẵn")
    }

    @Test
    fun boDemToiDaHaiManhVaBoQuaDoanTrong() {
        assertEquals(listOf(3, 4), TtsSegmentPlan.bufferAhead(texts, 0))
        assertEquals(listOf(4, 6), TtsSegmentPlan.bufferAhead(texts, 3))
        assertEquals(listOf(6), TtsSegmentPlan.bufferAhead(texts, 4))
        assertEquals(emptyList(), TtsSegmentPlan.bufferAhead(texts, 6))
    }

    @Test
    fun soLuongDemTuyChinh() {
        assertEquals(listOf(3, 4, 6), TtsSegmentPlan.bufferAhead(texts, 0, count = 3))
    }

    @Test
    fun goiYTaiTruocLaMotMiengKeTiep() {
        assertEquals("b", TtsSegmentPlan.prefetchHint(texts, 0))
        assertEquals("d", TtsSegmentPlan.prefetchHint(texts, 4))
        assertNull(TtsSegmentPlan.prefetchHint(texts, 6))
    }
}
