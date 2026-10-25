package com.truyencloud.shared.repo

import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityStreakTest {
    private val today = "2026-09-28"

    private fun streak(vararg dates: String, now: String = today) = ActivityStreak.fromDates(dates.toList(), now)

    @Test
    fun khongCoHoatDongLaKhongCoChuoi() {
        assertEquals(StreakInfo(0, 0), streak())
    }

    @Test
    fun homNayVaHomQuaLienTiep() {
        assertEquals(3, streak("2026-09-28", "2026-09-27", "2026-09-26").days)
        // hôm nay chưa học thì chuỗi chưa đứt
        assertEquals(2, streak("2026-09-27", "2026-09-26").days)
    }

    @Test
    fun boLoHaiNgayThiDut() {
        assertEquals(0, streak("2026-09-25", "2026-09-24").days)
    }

    @Test
    fun docVaOnTronChungMotChuoi() {
        // ngày 26 chỉ đọc, ngày 27 chỉ ôn, ngày 28 cả hai -> 3 ngày (trước đây mỗi màn hình đếm một kiểu)
        val reading = listOf("2026-09-26", "2026-09-28")
        val reviews = listOf("2026-09-27", "2026-09-28")
        assertEquals(3, ActivityStreak.fromDates(reading + reviews, today).days)
    }

    @Test
    fun trungLapVaThuTuBatKyKhongAnhHuong() {
        assertEquals(2, streak("2026-09-27", "2026-09-28", "2026-09-28", "2026-09-27").days)
    }

    @Test
    fun ngayTuongLaiBiBoQua() {
        assertEquals(1, streak("2026-09-28", "2026-10-05").days)
    }

    @Test
    fun khienBoMotNgayLeSauBayNgayLienTiep() {
        // 7 ngày liên tiếp 10..16/09 tặng 1 khiên; bỏ lỡ 17/09; quay lại 18/09 -> chuỗi KHÔNG đứt
        val week = (10..16).map { "2026-09-$it" }
        val info = ActivityStreak.fromDates(week + listOf("2026-09-18", "2026-09-19"), "2026-09-19")
        assertEquals(9, info.days)
        assertEquals(0, info.shields, "Khiên đã dùng để bù ngày 17")
    }

    @Test
    fun khongCoKhienThiBoLoMotNgayLaDut() {
        val info = ActivityStreak.fromDates(listOf("2026-09-10", "2026-09-11", "2026-09-13"), "2026-09-13")
        assertEquals(1, info.days)
    }

    @Test
    fun khienKhongBuHaiNgayLienTiep() {
        val week = (10..16).map { "2026-09-$it" }
        val info = ActivityStreak.fromDates(week + listOf("2026-09-19"), "2026-09-19")
        assertEquals(1, info.days)
    }

    @Test
    fun khienToiDaHai() {
        val info = ActivityStreak.fromDates((1..30).map { "2026-09-" + it.toString().padStart(2, '0') }, "2026-09-30")
        assertEquals(30, info.days)
        assertEquals(ActivityStreak.MAX_SHIELDS, info.shields)
    }

    @Test
    fun homQuaBoLoNhungConKhienThiChuoiConSong() {
        // 7 ngày liên tiếp đến 25/09, bỏ lỡ 26/09, hôm nay 27/09 chưa học: còn khiên nên chưa đứt
        val week = (19..25).map { "2026-09-$it" }
        val alive = ActivityStreak.fromDates(week, "2026-09-27")
        assertEquals(7, alive.days)
        assertEquals(1, alive.shields)
        // không có khiên (chỉ 3 ngày) thì đứt
        assertEquals(0, ActivityStreak.fromDates(listOf("2026-09-23", "2026-09-24", "2026-09-25"), "2026-09-27").days)
    }
}
