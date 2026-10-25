package com.truyencloud.shared.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewReminderMathTest {
    private val day = 86_400_000L
    private val hour = 3_600_000L
    private val base = 20_454L * day // 2026-01-01T00:00Z

    @Test
    fun chuaQuaGioHomNayThiNoHomNay() {
        // 10:00 UTC, nhắc 20:00 (offset 0)
        assertEquals(base + 20 * hour, ReviewReminderMath.nextFireMillis(base + 10 * hour, 20, 0) { 0L })
    }

    @Test
    fun daQuaGioHomNayThiNoNgayMai() {
        val next = ReviewReminderMath.nextFireMillis(base + 21 * hour, 20, 0) { 0L }
        assertEquals(base + day + 20 * hour, next)
        assertTrue(next > base + 21 * hour)
    }

    @Test
    fun dungGioThiSangNgayMai() {
        assertEquals(base + day + 20 * hour, ReviewReminderMath.nextFireMillis(base + 20 * hour, 20, 0) { 0L })
    }

    @Test
    fun theoMuiGioDiaPhuong() {
        // ICT +7: 20:00 ICT = 13:00 UTC; bây giờ 10:00 UTC (17:00 ICT) -> 13:00 UTC hôm nay
        assertEquals(base + 13 * hour, ReviewReminderMath.nextFireMillis(base + 10 * hour, 20, 0) { 7 * hour })
    }

    @Test
    fun noiDungThongBao() {
        assertEquals("12 từ cần ôn", ReviewReminderMath.message(12))
        assertTrue(ReviewReminderMath.message(0).contains("ôn"))
    }

    @Test
    fun cauHinhMacDinhLa2000() {
        val cfg = ReviewReminderConfig()
        assertEquals(20, cfg.hour)
        assertEquals("20:00", cfg.timeLabel)
        assertEquals("07:05", ReviewReminderConfig(true, 7, 5).timeLabel)
    }
}
