package com.truyencloud.shared.srs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudyDayTest {
    private val day = StudyDay.DAY_MS
    private val hour = 3_600_000L

    // 2026-01-01T00:00:00Z
    private val base = 20_454L * day

    @Test
    fun ngayHocBatDauLuc4GioSangDiaPhuong() {
        // offset 0: 03:59 vẫn thuộc ngày học trước, 04:00 là ngày học mới
        val before = base + 3 * hour + 59 * 60_000L
        val at = base + 4 * hour
        assertEquals(StudyDay.dayIndex(at, 0L) - 1, StudyDay.dayIndex(before, 0L))
        assertEquals(base, StudyDay.startOfToday(at, 0L) - 4 * hour)
    }

    @Test
    fun bamXuyenDemVanThuocNgayHocCu() {
        val oneAm = base + 1 * hour
        // 01:00 ngày 01/01 vẫn là ngày học 31/12
        assertEquals(StudyDay.startOfToday(base - hour, 0L), StudyDay.startOfToday(oneAm, 0L))
    }

    @Test
    fun hanOnMotNgayTuBuoiToiLaSang4GioHomSau() {
        val evening = base + 21 * hour
        val due = StudyDay.dueForInterval(evening, 1, 0L)
        assertEquals(base + day + 4 * hour, due)
        // và từ 2 giờ sáng (còn thuộc ngày học trước) thì đến hạn sau 2 giờ nữa
        val twoAm = base + 2 * hour
        assertEquals(base + 4 * hour, StudyDay.dueForInterval(twoAm, 1, 0L))
    }

    @Test
    fun hoatDongVoiMuiGioAmVaDuong() {
        for (offset in listOf(-8 * hour, 0L, 7 * hour, 13 * hour)) {
            val now = base + 12 * hour
            val start = StudyDay.startOfToday(now, offset)
            assertTrue(start <= now && now - start < day, "start=$start now=$now offset=$offset")
            // đầu ngày học luôn rơi đúng 04:00 giờ địa phương
            assertEquals(4 * hour, (((start + offset) % day) + day) % day)
            assertEquals(start + 3 * day, StudyDay.dueForInterval(now, 3, offset))
        }
    }

    @Test
    fun gioDiaPhuongTrongNgayLich() {
        // 20:00 giờ địa phương của ngày lịch chứa `now`
        val now = base + 10 * hour // 10:00 UTC
        assertEquals(base + 20 * hour, StudyDay.localClockTime(now, 20, 0, 0L))
        // ICT: 10:00 UTC = 17:00 ICT -> 20:00 ICT = 13:00 UTC
        assertEquals(base + 13 * hour, StudyDay.localClockTime(now, 20, 0, 7 * hour))
    }
}
