package com.truyencloud.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TtsResilienceTest {

    @Test
    fun cauDaoMoMacDinh() {
        val b = OnlineTtsCircuitBreaker()
        assertTrue(b.allowOnline(0))
        assertFalse(b.isTripped)
    }

    @Test
    fun mootLanLoiOLaiOfflineTrongThoiGianCho() {
        val b = OnlineTtsCircuitBreaker(cooldownMs = 180_000)
        b.recordFailure(nowMs = 1_000)
        assertTrue(b.isTripped)
        assertFalse(b.allowOnline(1_000))
        assertFalse(b.allowOnline(100_000), "Chưa hết 3 phút thì không thử online lại")
        assertFalse(b.allowOnline(180_999))
    }

    @Test
    fun hetHanThiChoThuLaiVaThanhCongDongCauDao() {
        val b = OnlineTtsCircuitBreaker(cooldownMs = 180_000)
        b.recordFailure(nowMs = 1_000)
        assertTrue(b.allowOnline(181_000))
        b.recordSuccess()
        assertFalse(b.isTripped)
        assertTrue(b.allowOnline(181_001))
    }

    @Test
    fun thuLaiThatBaiThiNganLaiTuMocMoi() {
        val b = OnlineTtsCircuitBreaker(cooldownMs = 10_000)
        b.recordFailure(0)
        assertTrue(b.allowOnline(10_000))
        b.recordFailure(10_000)
        assertFalse(b.allowOnline(15_000))
        assertTrue(b.allowOnline(20_000))
    }

    @Test
    fun baLanLoiLienTiepThiBoCuoc() {
        val t = ConsecutiveFailureTracker(maxFailures = 3)
        assertFalse(t.recordFailure())
        assertFalse(t.recordFailure())
        assertTrue(t.recordFailure())
    }

    @Test
    fun doanNgheDuocThiDemLaiTuDau() {
        val t = ConsecutiveFailureTracker(maxFailures = 3)
        t.recordFailure()
        t.recordFailure()
        t.reset()
        assertEquals(0, t.count)
        assertFalse(t.recordFailure())
        assertFalse(t.recordFailure())
        assertTrue(t.recordFailure())
    }

    // region Đổi địa chỉ máy chủ

    @Test
    fun doiSangDiaChiKhacMoLaiCauDaoNgay() {
        val b = OnlineTtsCircuitBreaker(cooldownMs = 180_000)
        val server = TtsServerEndpoint("https://cu.example", b)
        b.recordFailure(nowMs = 1_000)
        assertFalse(b.allowOnline(2_000), "Máy chủ cũ vừa lỗi: đang ở giọng máy")

        assertTrue(server.update("https://moi.example/"))

        // Lỗi cũ: chỉ đổi địa chỉ, cầu dao vẫn mở -> sửa địa chỉ xong vẫn nghe giọng máy ~3 phút
        assertTrue(b.allowOnline(2_000), "Máy chủ mới phải được thử ngay")
        assertEquals("https://moi.example", server.url, "Dấu / cuối bị bỏ")
    }

    @Test
    fun diaChiKhongDoiThiKhongDongChamVaoCauDao() {
        val b = OnlineTtsCircuitBreaker(cooldownMs = 180_000)
        val server = TtsServerEndpoint("https://a.example", b)
        b.recordFailure(nowMs = 1_000)
        assertFalse(server.update("https://a.example/"))
        assertFalse(server.update("   "))
        assertFalse(b.allowOnline(2_000), "Đẩy lại cùng địa chỉ (hồ sơ phát lại cài đặt) không được xoá lỗi")
    }

    @Test
    fun doiDiaChiBaoChoNenTangDeBoManhTaiSan() {
        val changed = mutableListOf<String>()
        val server = TtsServerEndpoint("https://a.example", OnlineTtsCircuitBreaker()) { changed += it }
        server.update("https://b.example")
        server.update("https://b.example")
        assertEquals(listOf("https://b.example"), changed)
    }

    // endregion

    // region Đồng hồ chờ tải

    @Test
    fun manhTreoQuaHanKhiDangNgheThiBiBao() {
        val clock = TtsLoadWatchdog(timeoutMs = 25_000)
        clock.restart(nowMs = 0, running = true)
        assertFalse(clock.isExpired(25_000))
        assertTrue(clock.isExpired(25_001))
    }

    @Test
    fun thoiGianTamDungKhongTinhVaoHanChoTai() {
        val clock = TtsLoadWatchdog(timeoutMs = 25_000)
        clock.restart(nowMs = 0, running = true)
        clock.setRunning(nowMs = 250, running = false)          // tạm dừng ngay khi mảnh vừa vào hàng đợi
        assertFalse(clock.isExpired(30_000), "Đang dừng thì không có gì 'treo'")
        clock.setRunning(nowMs = 30_000, running = true)        // bấm Phát sau 30 giây
        // Lỗi cũ: đồng hồ chạy suốt lúc dừng nên mảnh khoẻ bị coi là treo ngay nhịp quét đầu tiên
        assertFalse(clock.isExpired(30_250))
        assertFalse(clock.isExpired(54_000))
        assertTrue(clock.isExpired(55_251), "Chỉ 250ms + 25s đang nghe mới tính")
    }

    @Test
    fun manhMoiLenDauHangDoiDemLaiTuDau() {
        val clock = TtsLoadWatchdog(timeoutMs = 25_000)
        clock.restart(nowMs = 0, running = true)
        assertTrue(clock.isExpired(26_000))
        clock.restart(nowMs = 26_000, running = true)
        assertFalse(clock.isExpired(30_000))
    }

    @Test
    fun manhMoiLenDauKhiDangDungThiChuaDem() {
        val clock = TtsLoadWatchdog(timeoutMs = 1_000)
        clock.restart(nowMs = 0, running = false)
        assertFalse(clock.isExpired(1_000_000))
    }

    @Test
    fun batTatLienTiepKhongLamSaiThoiGian() {
        val clock = TtsLoadWatchdog(timeoutMs = 10_000)
        clock.restart(nowMs = 0, running = true)
        clock.setRunning(1_000, true)    // đã chạy rồi: không đặt lại mốc
        clock.setRunning(4_000, false)
        clock.setRunning(5_000, false)   // đã dừng rồi: không cộng thêm
        clock.setRunning(100_000, true)
        assertFalse(clock.isExpired(106_000), "Mới đếm 4s + 6s = 10s, chưa quá hạn")
        assertTrue(clock.isExpired(106_001))
    }

    // endregion
}
