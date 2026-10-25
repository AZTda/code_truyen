package com.truyencloud.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Quy đổi ms âm thanh <-> chữ trong mảnh (giọng AI chỉ có tổng độ dài), tìm ĐẦU TỪ để đọc tiếp giữa mảnh, và điểm bắt đầu
 * khi nghe tiếp bằng cùng giọng / giọng khác / giọng máy.
 */
class TtsPositionMappingTest {

    private val vi = "Lâm Phong chậm rãi ngẩng đầu, ánh mắt lạnh lẽo. Hắn nói: “Ai dám?”"

    @Test
    fun msVaChuQuyDoiDonDieuVaDungBien() {
        val duration = 6_000L
        var last = -1
        for (ms in 0L..duration step 100L) {
            val c = TtsTextTiming.charOffsetAt(vi, ms, duration)
            assertTrue(c >= last, "phải tăng dần theo ms ($ms)")
            last = c
        }
        assertEquals(0, TtsTextTiming.charOffsetAt(vi, 0L, duration))
        assertEquals(vi.length, TtsTextTiming.charOffsetAt(vi, duration, duration))
        assertEquals(0, TtsTextTiming.charOffsetAt(vi, 500L, 0L), "chưa biết độ dài thì về đầu mảnh")
        assertEquals(0L, TtsTextTiming.audioMsAt(vi, 0, duration))
        assertEquals(duration, TtsTextTiming.audioMsAt(vi, vi.length, duration))
    }

    @Test
    fun quyDiQuyVeRoiVaoCungTu() {
        val duration = 7_200L
        val word = vi.indexOf("ánh")
        val ms = TtsTextTiming.audioMsAt(vi, word, duration)
        val back = TtsTextTiming.charOffsetAt(vi, ms + 1, duration)
        assertEquals(word, TtsWordBoundary.wordStart(vi, back))
    }

    @Test
    fun dauCauLaChoNgungNenTonThoiGianHonChuCai() {
        val withPause = TtsTextTiming.audioMsAt("Một. Hai", 5, 1_000L)
        val noPause = TtsTextTiming.audioMsAt("Một  Hai", 5, 1_000L)
        assertTrue(withPause > noPause, "dấu chấm là một quãng ngừng ($withPause > $noPause)")
    }

    @Test
    fun mp3CbrSuyRaDoDaiKhiThieuHeader() {
        assertEquals(24L, TtsTextTiming.mp3DurationMs(192L))
        assertEquals(18_000L, TtsTextTiming.mp3DurationMs(192L * 750))
        assertEquals(0L, TtsTextTiming.mp3DurationMs(0L))
    }

    @Test
    fun dauTuTiengVietNfcVaNfd() {
        val nfc = "Ánh trăng soi"
        assertEquals(4, TtsWordBoundary.wordStart(nfc, 7))
        // NFD: "A" + dấu sắc tổ hợp; chỉ số rơi vào dấu tổ hợp không được tách khỏi chữ gốc
        val nfd = "Xin Ánh sáng"
        assertEquals(4, TtsWordBoundary.wordStart(nfd, 5))
        assertEquals(4, TtsWordBoundary.wordStart(nfd, 6))
        val (start, rest) = TtsWordBoundary.sliceFromWord(nfd, 10)
        assertEquals("sáng", rest)
        assertEquals(9, start)
    }

    @Test
    fun dauNhayVaDauCauThiSangTuKeTiep() {
        val text = "“Ngươi là ai?” hắn hỏi."
        assertEquals(1, TtsWordBoundary.wordStart(text, 0), "đứng ở dấu ngoặc mở thì từ kế là “Ngươi”")
        assertEquals(text.indexOf("hắn"), TtsWordBoundary.wordStart(text, text.indexOf('?')))
        assertEquals(text.length, TtsWordBoundary.wordStart(text, text.length - 1), "sau từ cuối: hết chữ")
        assertEquals(text.indexOf("hỏi"), TtsWordBoundary.lastWordStart(text))
        assertEquals(0, TtsWordBoundary.wordStart("don't stop", 3), "dấu nháy giữa từ thuộc từ đó")
    }

    @Test
    fun chuHanMoiChuLaMotTuVaKhongCatCapSurrogate() {
        val zh = "天下第一。"
        assertEquals(2, TtsWordBoundary.wordStart(zh, 2))
        val rare = "甲𠀀乙"
        assertEquals(1, TtsWordBoundary.wordStart(rare, 2), "nửa sau của cặp surrogate về đầu cặp")
        val emoji = "Cười 😀 rồi đi"
        val inEmoji = emoji.indexOf("😀") + 1
        assertEquals(emoji.indexOf("rồi"), TtsWordBoundary.wordStart(emoji, inEmoji), "emoji không phải từ: sang từ kế")
        val slice = TtsWordBoundary.sliceFromWord(emoji, inEmoji).second
        assertTrue(slice.first().isLetter())
    }

    @Test
    fun cungGiongAiThiTuaDungMsLui300MsKhacGiongThiTheoChu() {
        val pos = TtsPlaybackPosition(segmentIndex = 2, charOffset = vi.indexOf("ánh") + 2, audioMs = 4_000L, audioVoiceKey = "my_duyen")
        val same = TtsSegmentStart.from(pos, vi, "my_duyen")
        assertEquals(4_000L - TtsPlaybackPosition.RESUME_BACKOFF_MS, same.audioMs)
        assertEquals(vi.indexOf("ánh"), same.charOffset)
        val other = TtsSegmentStart.from(pos, vi, "duc_tri")
        assertEquals(-1L, other.audioMs, "tệp của giọng khác: không dùng ms cũ")
        assertEquals(vi.indexOf("ánh"), other.charOffset)
        val device = TtsSegmentStart.from(pos, vi, null)
        assertEquals(-1L, device.audioMs)
        assertEquals(vi.indexOf("ánh"), device.charOffset)
    }

    @Test
    fun dauManhHoacSauTuCuoiThiKhongDocLaiCaManh() {
        assertTrue(TtsSegmentStart.from(TtsPlaybackPosition.startOf(3), vi, "my_duyen").isFromBeginning)
        val end = TtsSegmentStart.from(TtsPlaybackPosition(0, vi.length), vi, null)
        assertEquals(vi.indexOf("dám"), end.charOffset, "dừng sau từ cuối: đọc lại từ cuối")
        val early = TtsSegmentStart.from(TtsPlaybackPosition(0, 0, 200L, "my_duyen"), vi, "my_duyen")
        assertTrue(early.isFromBeginning, "lùi 300 ms từ 200 ms là về đầu tệp")
    }
}
