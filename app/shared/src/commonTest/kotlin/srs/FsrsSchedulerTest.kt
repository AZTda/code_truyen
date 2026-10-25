package com.truyencloud.shared.srs

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FsrsSchedulerTest {

    private val day = FsrsScheduler.MILLIS_PER_DAY
    private val hour = 3_600_000L

    /** 04:00 UTC đúng — đầu một "ngày học" khi độ lệch múi giờ là 0, nên hạn ôn rơi đúng vào mốc này. */
    private val t0 = 19_700L * day + 4 * hour

    /** Không xáo, múi giờ cố định: kết quả tất định để ghim số. */
    private val cfg = FsrsConfig(enableFuzz = false, utcOffsetMillis = 0L)

    private fun near(expected: Double, actual: Double, eps: Double = 1e-3) =
        assertTrue(abs(expected - actual) <= eps, "kỳ vọng $expected, nhận $actual")

    // -----------------------------------------------------------------------
    // Công thức FSRS-4.5 (tính tay từ w4 = 5.1618, w5 = 1.2298, w6 = 0.8975, w7 = 0.0310)
    // -----------------------------------------------------------------------

    @Test
    fun doKhoBanDauLaTuyenTinhTheoDiem() {
        // D0(G) = w4 - (G - 3) * w5 : 5.1618 + 2*1.2298 = 7.6214 ...
        near(7.6214, FsrsScheduler.initialDifficulty(Rating.AGAIN), 1e-4)
        near(6.3916, FsrsScheduler.initialDifficulty(Rating.HARD), 1e-4)
        near(5.1618, FsrsScheduler.initialDifficulty(Rating.GOOD), 1e-4)
        near(3.9320, FsrsScheduler.initialDifficulty(Rating.EASY), 1e-4)
    }

    @Test
    fun chamTotHoacDeLanDauKhongBaoGioKepDoKhoVeMot() {
        // Lỗi cũ: công thức D0 của FSRS-5 (w4 - exp(w5*(G-1)) + 1) trên trọng số 4.5 cho D0(Good) = D0(Easy) = 1.
        assertTrue(FsrsScheduler.initialDifficulty(Rating.GOOD) > 5.0)
        assertTrue(FsrsScheduler.initialDifficulty(Rating.EASY) > 3.0)
        val easyFirst = FsrsScheduler.review(MemoryState(), Rating.EASY, t0, cfg)
        assertTrue(easyFirst.state.difficulty > 3.0, "D sau lần Dễ đầu tiên: ${easyFirst.state.difficulty}")
    }

    @Test
    fun doBenBanDauLaTrongSoW0DenW3() {
        near(0.4872, FsrsScheduler.initialStability(Rating.AGAIN), 1e-9)
        near(1.4003, FsrsScheduler.initialStability(Rating.HARD), 1e-9)
        near(3.7145, FsrsScheduler.initialStability(Rating.GOOD), 1e-9)
        near(13.8206, FsrsScheduler.initialStability(Rating.EASY), 1e-9)
    }

    @Test
    fun doKhoKeTiepHoiQuyVeDiemTrungBinh() {
        // D' = w7*D0(3) + (1-w7)*(D - w6*(G-3)) từ D = 5.1618
        near(6.901155, FsrsScheduler.nextDifficulty(5.1618, Rating.AGAIN), 1e-5)
        near(6.0314775, FsrsScheduler.nextDifficulty(5.1618, Rating.HARD), 1e-5)
        near(5.1618, FsrsScheduler.nextDifficulty(5.1618, Rating.GOOD), 1e-5)
        near(4.2921225, FsrsScheduler.nextDifficulty(5.1618, Rating.EASY), 1e-5)
        // Kẹp trong [1, 10]
        assertEquals(10.0, FsrsScheduler.nextDifficulty(10.0, Rating.AGAIN), 1e-9)
        assertEquals(1.0, FsrsScheduler.nextDifficulty(1.0, Rating.EASY), 1e-9)
    }

    @Test
    fun chuoiTotTotCuaTheMoiCoKhoangCachDaTinhTay() {
        var state = MemoryState()
        var now = t0
        val intervals = mutableListOf<Long>()
        val stabilities = mutableListOf<Double>()
        repeat(7) {
            val out = FsrsScheduler.review(state, Rating.GOOD, now, cfg)
            state = out.state
            intervals += out.intervalDays
            stabilities += state.stability
            now = state.dueEpoch // ôn đúng hạn: elapsed = đúng số ngày của khoảng cách
        }
        // S0 = 3.7145 -> round(3.7145 / FACTOR * (0.9^-2 - 1)) = 4 ngày; các lần sau theo công thức tăng độ bền
        assertEquals(listOf(4L, 15L, 49L, 146L, 393L, 973L, 1095L), intervals)
        near(3.7145, stabilities[0], 1e-4)
        near(14.8081, stabilities[1], 1e-3)
        near(49.4616, stabilities[2], 1e-3)
        near(145.6706, stabilities[3], 1e-3)
        near(5.1618, state.difficulty, 1e-4) // Tốt mãi thì D đứng yên ở D0(Tốt)
    }

    @Test
    fun khoangCachLanDauTheoTungNut() {
        val days = Rating.entries.associateWith { FsrsScheduler.review(MemoryState(), it, t0, cfg).intervalDays }
        assertEquals(0L, days.getValue(Rating.AGAIN)) // bước học 5 phút
        assertEquals(0L, days.getValue(Rating.HARD)) // bước học 10 phút cho thẻ mới
        assertEquals(4L, days.getValue(Rating.GOOD))
        assertEquals(14L, days.getValue(Rating.EASY))
    }

    @Test
    fun buocHocNganLaNamPhutVaMuoiPhut() {
        val again = FsrsScheduler.review(MemoryState(), Rating.AGAIN, t0, cfg)
        assertEquals(t0 + 5 * 60_000L, again.state.dueEpoch)
        val hard = FsrsScheduler.review(MemoryState(), Rating.HARD, t0, cfg)
        assertEquals(t0 + 10 * 60_000L, hard.state.dueEpoch)
    }

    // -----------------------------------------------------------------------
    // Đếm lapse
    // -----------------------------------------------------------------------

    @Test
    fun chiTinhLapseKhiTheOTrangThaiOnTapBiChamQuen() {
        // Thẻ mới bấm Quên: chưa có gì để quên
        val a = FsrsScheduler.review(MemoryState(), Rating.AGAIN, t0, cfg)
        assertEquals(0, a.state.lapses)
        assertEquals(CardState.LEARNING, a.state.state)

        // Đang ở bước học, lại Quên: vẫn không phải lapse
        val a2 = FsrsScheduler.review(a.state, Rating.AGAIN, a.state.dueEpoch, cfg)
        assertEquals(0, a2.state.lapses)

        // Tốt nghiệp (Tốt) rồi mới quên: đây mới là lapse
        val grad = FsrsScheduler.review(a2.state, Rating.GOOD, a2.state.dueEpoch, cfg)
        assertEquals(CardState.REVIEW, grad.state.state)
        assertTrue(grad.state.stability >= FsrsScheduler.initialStability(Rating.GOOD), "Tốt nghiệp cùng ngày từ bước học 5 phút phải đạt độ bền ban đầu của Tốt (~3.7 ngày)")
        assertEquals(4L, grad.intervalDays)
        val lapse = FsrsScheduler.review(grad.state, Rating.AGAIN, grad.state.dueEpoch, cfg)
        assertEquals(1, lapse.state.lapses)
        assertEquals(CardState.RELEARNING, lapse.state.state)

        // Đang học lại mà Quên tiếp: vẫn một lapse
        val again = FsrsScheduler.review(lapse.state, Rating.AGAIN, lapse.state.dueEpoch, cfg)
        assertEquals(1, again.state.lapses)
        assertEquals(CardState.RELEARNING, again.state.state)

        // Học lại xong (Tốt) quay về Ôn tập
        val back = FsrsScheduler.review(again.state, Rating.GOOD, again.state.dueEpoch, cfg)
        assertEquals(CardState.REVIEW, back.state.state)
        assertEquals(1, back.state.lapses)
    }

    @Test
    fun quenPhaiLamGiamDoBen() {
        var state = MemoryState()
        var now = t0
        repeat(4) {
            state = FsrsScheduler.review(state, Rating.GOOD, now, cfg).state
            now = state.dueEpoch
        }
        val before = state.stability
        val lapse = FsrsScheduler.review(state, Rating.AGAIN, now, cfg)

        assertTrue(lapse.state.stability < before, "Quên phải làm giảm độ bền")
        assertEquals(1, lapse.state.lapses)
        assertEquals(CardState.RELEARNING, lapse.state.state)
        assertEquals(0L, lapse.intervalDays, "Thẻ vừa quên phải ôn lại trong cùng phiên")
    }

    @Test
    fun thuTuKhoangCachTheoDiem() {
        var state = MemoryState()
        repeat(3) {
            state = FsrsScheduler.review(state, Rating.GOOD, t0 + it * 3L * day, cfg).state
        }
        val preview = FsrsScheduler.previewIntervals(state, state.dueEpoch, cfg)
        val again = preview.getValue(Rating.AGAIN).intervalDays
        val hard = preview.getValue(Rating.HARD).intervalDays
        val good = preview.getValue(Rating.GOOD).intervalDays
        val easy = preview.getValue(Rating.EASY).intervalDays

        assertTrue(again <= hard, "Quên phải ngắn nhất")
        assertTrue(hard < good, "Khó phải ngắn hơn Tốt: $hard vs $good")
        assertTrue(good < easy, "Tốt phải ngắn hơn Dễ: $good vs $easy")
    }

    @Test
    fun doKhoVaDoBenLuonHopLe() {
        var state = MemoryState()
        var now = t0
        val sequence = listOf(
            Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY,
            Rating.AGAIN, Rating.AGAIN, Rating.EASY, Rating.EASY, Rating.GOOD
        )
        for (r in sequence) {
            state = FsrsScheduler.review(state, r, now, cfg).state
            now = state.dueEpoch + day
            assertTrue(state.difficulty in 1.0..10.0, "Độ khó ngoài khoảng: ${state.difficulty}")
            assertTrue(state.stability > 0.0, "Độ bền phải dương: ${state.stability}")
        }
    }

    @Test
    fun phatLaiLichSuDeTinhLaiDoKho() {
        // Tốt, Tốt: D giữ nguyên D0(Tốt)
        near(5.1618, FsrsScheduler.replayDifficulty(listOf(Rating.GOOD, Rating.GOOD))!!, 1e-4)
        // Chuỗi toàn Dễ kéo D về đáy hợp lệ của FSRS-4.5
        assertEquals(1.0, FsrsScheduler.replayDifficulty(List(8) { Rating.EASY })!!, 1e-9)
        assertEquals(null, FsrsScheduler.replayDifficulty(emptyList()))
    }

    @Test
    fun xacSuatNhoGiamTheoThoiGian() {
        assertTrue(abs(FsrsScheduler.retrievability(0.0, 10.0) - 1.0) < 1e-9)
        // Theo định nghĩa, sau đúng S ngày thì xác suất nhớ còn 90%
        assertTrue(abs(FsrsScheduler.retrievability(10.0, 10.0) - 0.9) < 0.01)
        assertTrue(
            FsrsScheduler.retrievability(10.0, 10.0) > FsrsScheduler.retrievability(100.0, 10.0)
        )
    }

    // -----------------------------------------------------------------------
    // Hạn ôn theo ngày học + xáo khoảng cách
    // -----------------------------------------------------------------------

    @Test
    fun hanOnTuMotNgayTroLenLaDauNgayHoc() {
        // Ôn lúc 23:00 UTC (offset 0), khoảng cách 4 ngày -> hạn đúng 04:00 UTC của ngày học thứ +4.
        val late = 19_700L * day + 23 * hour
        val out = FsrsScheduler.review(MemoryState(), Rating.GOOD, late, cfg)
        assertEquals(4L, out.intervalDays)
        val expected = 19_704L * day + 4 * hour
        assertEquals(expected, out.state.dueEpoch)
    }

    @Test
    fun hanOnTheoMuiGioDiaPhuong() {
        // ICT (+07:00): ôn lúc 23:00 địa phương = 16:00 UTC. Ngày học bắt đầu 04:00 địa phương = 21:00 UTC hôm trước.
        val ict = FsrsConfig(enableFuzz = false, utcOffsetMillis = 7 * hour)
        val now = 19_700L * day + 16 * hour
        val out = FsrsScheduler.review(MemoryState(), Rating.GOOD, now, ict)
        val localDue = out.state.dueEpoch + 7 * hour // về giờ địa phương
        assertEquals(4 * hour, ((localDue % day) + day) % day, "Hạn phải rơi đúng 04:00 giờ địa phương")
        assertTrue(out.state.dueEpoch > now)
    }

    @Test
    fun xamKhoangCachLaTatDinhVaNamTrongKhoang() {
        val state = FsrsScheduler.review(MemoryState(), Rating.GOOD, t0, cfg).state
        val fuzzCfg = FsrsConfig(utcOffsetMillis = 0L)
        val plain = FsrsScheduler.review(state, Rating.GOOD, state.dueEpoch, cfg).intervalDays
        val a1 = FsrsScheduler.review(state, Rating.GOOD, state.dueEpoch, fuzzCfg, fuzzKey = "word_a").intervalDays
        val a2 = FsrsScheduler.review(state, Rating.GOOD, state.dueEpoch, fuzzCfg, fuzzKey = "word_a").intervalDays
        assertEquals(a1, a2, "Cùng khoá -> cùng kết quả")
        val range = FsrsFuzz.fuzzRange(plain, FsrsScheduler.DEFAULT_MAX_INTERVAL_DAYS)
        assertTrue(a1 in range, "$a1 phải nằm trong $range quanh $plain")

        val distinct = (0 until 60).map {
            FsrsScheduler.review(state, Rating.GOOD, state.dueEpoch, fuzzCfg, fuzzKey = "word_$it").intervalDays
        }.toSet()
        assertTrue(distinct.size >= 3, "Các thẻ khác nhau phải bị xáo khác nhau: $distinct")

        // Xem trước và kết quả thật dùng cùng khoá nên trùng nhau
        val preview = FsrsScheduler.previewIntervals(state, state.dueEpoch, fuzzCfg, fuzzKey = "word_a")
        assertEquals(a1, preview.getValue(Rating.GOOD).intervalDays)
    }

    @Test
    fun khongXamKhoangCachDuoiBaNgay() {
        for (d in 1L..2L) assertEquals(d, FsrsFuzz.apply(d, "any", 1, 1095))
        assertEquals(2L..4L, FsrsFuzz.fuzzRange(3, 1095))
        assertEquals(8L..12L, FsrsFuzz.fuzzRange(10, 1095))
        assertEquals(93L..107L, FsrsFuzz.fuzzRange(100, 1095))
        // Không vượt trần khoảng cách tối đa
        assertTrue(FsrsFuzz.fuzzRange(1090, 1095).last <= 1095)
    }

    @Test
    fun bamXamPhanBoDeuVaKhacNhauTheoLanOn() {
        val values = (0 until 40).map { FsrsFuzz.unitHash("word_$it", 1) }
        assertTrue(values.all { it >= 0.0 && it < 1.0 })
        assertTrue(values.toSet().size > 35, "Hai khoá chỉ khác ký tự cuối không được cho cùng một giá trị")
        assertNotEquals(FsrsFuzz.unitHash("word_a", 1), FsrsFuzz.unitHash("word_a", 2))
    }

    // -----------------------------------------------------------------------
    // Hàng đợi + di trú
    // -----------------------------------------------------------------------

    @Test
    fun diTruDuLieuCuKhongResetTienDo() {
        val migrated = FsrsScheduler.migrateFromLegacyStage(4, t0 + 5 * day, t0)
        assertTrue(migrated.stability > 0.0, "Thẻ cũ phải có độ bền tương đương")
        assertEquals(t0 + 5 * day, migrated.dueEpoch, "Phải giữ nguyên hạn ôn đã có")
        assertEquals(CardState.NEW, FsrsScheduler.migrateFromLegacyStage(0, 0, t0).state)
    }

    @Test
    fun hangDoiUuTienTheQuaHanVaTheCuaTruyenDangDoc() {
        val overdue = MemoryState(
            stability = 5.0, difficulty = 5.0, state = CardState.REVIEW,
            lastReviewEpoch = t0 - 30 * day, dueEpoch = t0 - 20 * day
        )
        val fresh = MemoryState(
            stability = 5.0, difficulty = 5.0, state = CardState.REVIEW,
            lastReviewEpoch = t0 - day, dueEpoch = t0
        )
        assertTrue(
            FsrsScheduler.queuePriority(overdue, t0) > FsrsScheduler.queuePriority(fresh, t0),
            "Thẻ quá hạn phải được ưu tiên"
        )
        assertTrue(
            FsrsScheduler.queuePriority(fresh, t0, "Boston", "Boston") >
                FsrsScheduler.queuePriority(fresh, t0, "Truyện khác", "Boston"),
            "Từ trong cuốn truyện đang đọc phải được ưu tiên"
        )

        val leech = MemoryState(
            stability = 2.0, difficulty = 9.0, state = CardState.REVIEW, lapses = 10,
            lastReviewEpoch = t0 - 30 * day, dueEpoch = t0 - 20 * day
        )
        assertTrue(
            FsrsScheduler.queuePriority(leech, t0) < FsrsScheduler.queuePriority(overdue, t0),
            "Thẻ leech phải xuống cuối hàng — cần đổi cách học chứ không phải lặp thêm"
        )
    }

    @Test
    fun theMoiDuocLenLichNgay() {
        val out = FsrsScheduler.review(MemoryState(), Rating.GOOD, t0, cfg)
        assertTrue(out.state.dueEpoch > t0)
        assertEquals(1, out.state.reps)
        assertEquals(CardState.REVIEW, out.state.state)
    }

    @Test
    fun nhanKhoangCachDocDuoc() {
        val out = FsrsScheduler.review(MemoryState(), Rating.EASY, t0, cfg)
        val label = FsrsScheduler.formatInterval(out, t0)
        assertTrue(label.isNotBlank())
        assertTrue(
            label.contains("ngày") || label.contains("tháng") ||
                label.contains("năm") || label.contains("phút"),
            "Nhãn phải là đơn vị thời gian tiếng Việt: $label"
        )
    }
}
