package com.truyencloud.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tạm dừng rồi nhảy đoạn / đổi cài đặt / nạp văn bản mới, rồi tiếp tục; đồng bộ trạng thái nền tảng khi tiếp tục tại chỗ.
 * Bộ phát giả mô hình đúng bộ phát thật: mảnh bị tạm dừng vẫn nằm trong bộ phát và `resume` phát tiếp CHÍNH nó.
 */
class BaseTtsSpeakerPausedTest {

    private val paras = listOf("a", "b", "c", "d", "e")

    // region Nhảy đoạn lúc tạm dừng

    @Test
    fun nhayDoanLucTamDungRoiTiepTucThiDocDoanMoiVaKhongTreoIm() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.pause()
        s.nextParagraph()
        s.nextParagraph()

        s.resume()

        assertEquals(listOf("play:0", "play:2"), s.plays(), "Phải đọc đoạn 2, không phát tiếp âm thanh cũ của đoạn 0")
        assertTrue(s.audible)
        assertEquals(2, s.loaded)
        // Lỗi cũ: đoạn 0 cũ phát hết thì bị bỏ qua (0 != 2) và phiên "đang phát" trong im lặng
        s.audioEnds()
        assertEquals(listOf("play:0", "play:2", "play:3"), s.plays())
        assertTrue(s.audible)
    }

    @Test
    fun lui1DoanLucTamDungRoiTiepTucThiDocDoanTruoc() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 3)
        s.pause()
        s.prevParagraph()
        s.resume()
        assertEquals(listOf("play:3", "play:2"), s.plays())
    }

    @Test
    fun docLaiSauKhiNhayDoanRoiLanTiepTucSauThiPhatTiepTaiCho() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.pause()
        s.nextParagraph()
        s.resume()            // đọc lại từ đầu đoạn 1, KHÔNG phát tiếp âm thanh cũ
        assertEquals(0, s.log.count { it == "resume" })
        s.pause()
        s.resume()            // lần này vị trí không đổi: phát tiếp đúng chỗ
        assertEquals(listOf("play:0", "play:1"), s.plays(), "Cờ 'âm thanh cũ' phải được xoá sau khi đọc lại")
        assertEquals(1, s.log.count { it == "resume" })
    }

    @Test
    fun luiOMotDoanDauKhiTamDungKhongLamHongAmThanhDangDung() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.pause()
        s.prevParagraph()     // vẫn ở đoạn 0
        s.resume()
        assertEquals(listOf("play:0"), s.plays(), "Vị trí không đổi thì phát tiếp đúng chỗ, không đọc lại")
        assertTrue("resume" in s.log)
    }

    @Test
    fun nhayDoanTrongLucNgatQuangRoiHetNgatQuangThiDocDoanMoi() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.interruptionBegan()
        s.nextParagraph()
        s.interruptionEnded(resume = true)
        assertEquals(listOf("play:0", "play:1"), s.plays())
        assertTrue(s.isPlaying.value)
    }

    @Test
    fun dangPhatThiNhayDoanDocNgayNhuCu() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.nextParagraph()
        s.prevParagraph()
        assertEquals(listOf("play:0", "play:1", "play:0"), s.plays())
    }

    // endregion

    // region Đổi cài đặt lúc tạm dừng

    @Test
    fun doiGiongLucTamDungRoiTiepTucThiDocLaiDoanBangGiongMoi() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 1)
        s.pause()
        s.setVoiceProfile("minh_quang")
        assertEquals(1, s.plays().size, "Đang dừng thì đổi giọng không tự phát")
        s.resume()
        assertEquals(listOf("play:1", "play:1"), s.plays())
    }

    @Test
    fun doiTocDoHoacCheDoOnlineLucTamDungCungDocLaiKhiTiepTuc() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.pause()
        s.setSpeed(1.5f)
        s.resume()
        s.pause()
        s.setOnlineMode(false)
        s.resume()
        assertEquals(listOf("play:0", "play:0", "play:0"), s.plays())
    }

    @Test
    fun doiCaiDatKhiChuaCoPhienKhongLamGiCa() {
        val s = FakeTtsSpeaker()
        s.setSpeed(1.5f)
        s.setVoiceProfile("minh_quang")
        s.resume()
        assertTrue(s.plays().isEmpty())
        assertFalse(s.isPlaying.value)
    }

    // endregion

    // region Tiếp tục tại chỗ phải báo trạng thái cho nền tảng

    @Test
    fun tiepTucTaiChoBaoTrangThaiDangPhat() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.pause()
        assertFalse(s.stateLog.last())

        s.resume()

        // Lỗi cũ: lần đồng bộ cuối cùng vẫn là 'không phát' -> thông báo hiện nút Play, tai nghe không dừng được,
        // wake lock của dịch vụ bị nhả, vuốt khỏi đa nhiệm thì tắt tiếng
        assertTrue(s.isPlaying.value)
        assertTrue(s.stateLog.last(), "Thông báo / MediaSession / Now Playing phải thấy 'đang phát'")
    }

    @Test
    fun hetCuocGoiTiepTucTaiChoBaoTrangThaiDangPhat() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 0)
        s.interruptionBegan()
        assertFalse(s.stateLog.last())
        s.interruptionEnded(resume = true)
        assertTrue(s.stateLog.last())
    }

    // endregion
}
