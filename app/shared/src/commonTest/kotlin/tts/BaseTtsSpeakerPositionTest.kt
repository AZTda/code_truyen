package com.truyencloud.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Bấm dừng rồi mở lại thì chạy đúng tại chỗ chữ đó, không phải đầu đoạn": máy trạng thái giữ vị trí (mảnh + chữ + ms) mà bộ
 * phát báo về và luôn đọc lại / nghe tiếp từ ĐÓ, chỉ về đầu mảnh khi đổi sang mảnh khác.
 */
class BaseTtsSpeakerPositionTest {

    private val paras = listOf("Câu một của chương.", "Lâm Phong chậm rãi ngẩng đầu, ánh mắt lạnh lẽo.", "Câu ba.")

    @Test
    fun boPhatBaoViTriThiTrangThaiCongKhaiCoDungChuVaMs() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 1)
        s.progress(1, 12, 1_500L, "my_duyen")
        assertEquals(TtsPlaybackPosition(1, 12, 1_500L, "my_duyen"), s.playbackPosition.value)
        s.progress(0, 3, 100L, "my_duyen")
        assertEquals(12, s.playbackPosition.value.charOffset, "báo của mảnh cũ bị bỏ qua")
        s.progress(1, 9_999)
        assertEquals(paras[1].length, s.playbackPosition.value.charOffset, "kẹp trong chữ của mảnh")
        assertEquals(-1L, s.playbackPosition.value.audioMs, "giọng máy không có ms")
    }

    @Test
    fun bộPhatBiThuHoiThiDocLaiTuDungViTriKhongVeDauManh() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 1)
        s.progress(1, 20, 2_000L, "my_duyen")
        s.pause()
        s.resumable = false // hệ thống đã thu hồi bộ phát
        s.resume()
        assertEquals(listOf("play:1", "play:1"), s.plays())
        assertEquals(TtsPlaybackPosition(1, 20, 2_000L, "my_duyen"), s.starts.last())
    }

    @Test
    fun nghePhienMoiTuChuDaLuu() {
        val s = FakeTtsSpeaker()
        val saved = TtsPlaybackPosition(1, 26, 3_100L, "my_duyen")
        s.speakFrom(paras, saved, autoPlay = true)
        assertEquals(saved, s.starts.single())
        assertEquals(1, s.currentParagraphIndex.value)
    }

    @Test
    fun napTruocRoiBamPhatThiDocTuChuDaLuu() {
        val s = FakeTtsSpeaker()
        val saved = TtsPlaybackPosition(2, 4)
        s.speakFrom(paras, saved, autoPlay = false)
        assertTrue(s.plays().isEmpty(), "chỉ nạp chữ, chưa phát")
        s.resume()
        assertEquals(saved, s.starts.single())
    }

    @Test
    fun sangManhKeHoacNhayDoanThiVeDauManh() {
        val s = FakeTtsSpeaker()
        s.speakFrom(paras, TtsPlaybackPosition(1, 20), autoPlay = true)
        s.finish(1)
        assertEquals(TtsPlaybackPosition.startOf(2), s.starts.last())
        s.prevParagraph()
        assertEquals(TtsPlaybackPosition.startOf(1), s.starts.last())
    }

    @Test
    fun doiTocDoGiongAiThiApNgayKhongDocLai() {
        val s = FakeTtsSpeaker()
        s.speedInPlace = true
        s.speak(paras, 1)
        s.progress(1, 10, 1_200L, "my_duyen")
        s.setSpeed(1.5f)
        assertEquals(listOf("play:1"), s.plays(), "trình phát đổi tốc độ trên âm thanh đang nghe, máy chủ không dựng lại")
        assertEquals(1_200L, s.playbackPosition.value.audioMs)
    }

    @Test
    fun doiTocDoGiongMayThiDocLaiTuChuDangDoc() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 1)
        s.progress(1, 18)
        s.setSpeed(1.25f)
        assertEquals(listOf("play:1", "play:1"), s.plays())
        assertEquals(TtsPlaybackPosition(1, 18), s.starts.last())
    }

    @Test
    fun doiGiongGiuaManhThiDocLaiTuViTriChuChoGiongMoi() {
        val s = FakeTtsSpeaker()
        s.speak(paras, 1)
        s.progress(1, 30, 3_000L, "my_duyen")
        s.setVoiceProfile("duc_tri")
        val from = s.starts.last()
        assertEquals(30, from.charOffset)
        // Tệp của giọng mới khác tệp cũ: bộ phát dùng vị trí chữ (về đầu từ), không dùng ms của giọng cũ
        val start = TtsSegmentStart.from(from, paras[1], "duc_tri")
        assertEquals(-1L, start.audioMs)
        assertEquals(TtsWordBoundary.wordStart(paras[1], 30), start.charOffset)
    }
}
