package com.truyencloud.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TtsStartPolicyTest {

    private val n = 40

    @Test
    fun tuChuyenChuongLuonDocTuDauDuChuongKeDaCoViTriLuu() {
        // Lỗi cũ: chương kế bắt đầu đọc từ giữa chừng
        assertEquals(0, TtsStartPolicy.afterChapterLoad(advancing = true, restoreScroll = 0.7f, paragraphCount = n))
        assertEquals(0, TtsStartPolicy.afterChapterLoad(advancing = true, restoreScroll = 0f, paragraphCount = n))
    }

    @Test
    fun chonTuMucLucDocTuViTriDaLuu() {
        val p = TtsStartPolicy.afterChapterLoad(advancing = false, restoreScroll = TtsProgressMath.scrollForParagraph(12, n), paragraphCount = n)
        assertEquals(12, p)
    }

    @Test
    fun chuongMoiChuaDocDocTuDau() {
        assertEquals(0, TtsStartPolicy.afterChapterLoad(advancing = false, restoreScroll = 0f, paragraphCount = n))
    }

    @Test
    fun bamNgheTrongPhienDangMoThiTiepTucDoanDangDo() {
        assertEquals(
            7,
            TtsStartPolicy.manualStart(sameSession = true, sessionParagraph = 7, restoreScroll = 0.9f, visibleScroll = 0.5f, paragraphCount = n)
        )
    }

    @Test
    fun bamNgheDocTuDoanDangNhinThay() {
        val visible = TtsProgressMath.scrollForParagraph(20, n)
        assertEquals(
            20,
            TtsStartPolicy.manualStart(sameSession = false, sessionParagraph = 0, restoreScroll = 0f, visibleScroll = visible, paragraphCount = n)
        )
    }

    @Test
    fun viTriCanKhoiPhucUuTienHonViTriCuonCu() {
        val restore = TtsProgressMath.scrollForParagraph(30, n)
        val staleVisible = TtsProgressMath.scrollForParagraph(2, n)
        assertEquals(
            30,
            TtsStartPolicy.manualStart(sameSession = false, sessionParagraph = 0, restoreScroll = restore, visibleScroll = staleVisible, paragraphCount = n)
        )
    }

    @Test
    fun chuaBietViTriCuonThiDungDoanDangDo() {
        assertEquals(
            5,
            TtsStartPolicy.manualStart(sameSession = false, sessionParagraph = 5, restoreScroll = 0f, visibleScroll = -1f, paragraphCount = n)
        )
    }

    @Test
    fun ketQuaLuonNamTrongKhoangDoan() {
        assertEquals(n - 1, TtsStartPolicy.manualStart(true, 999, 0f, -1f, n))
        assertEquals(0, TtsStartPolicy.manualStart(true, 5, 0f, -1f, 0))
    }

    @Test
    fun nhanBietChuongCuoi() {
        assertTrue(TtsStartPolicy.isLastChapter(current = 29, knownLast = 29))
        assertTrue(TtsStartPolicy.isLastChapter(current = 30, knownLast = 29))
        assertFalse(TtsStartPolicy.isLastChapter(current = 10, knownLast = 29))
        assertFalse(TtsStartPolicy.isLastChapter(current = 10, knownLast = 0), "Chưa biết số chương thì cứ thử sang chương kế")
    }

    // region Chương kế tải xong có tự phát không

    @Test
    fun tuChuyenChuongDangPhatThiTiepTucPhat() {
        assertTrue(TtsStartPolicy.autoPlayAfterLoad(advancing = true, speakerPlaying = true))
    }

    @Test
    fun nguoiDungTamDungLucChuyenChuongThiChuongKeKhongTuPhat() {
        // Lỗi cũ: chương kế tải xong gọi speak() bất kể người dùng vừa bấm Tạm dừng / hẹn giờ ngủ vừa dừng
        assertFalse(TtsStartPolicy.autoPlayAfterLoad(advancing = true, speakerPlaying = false))
    }

    @Test
    fun bamPhatTrongLucChoChuongKeThiPhatNgayKhiTaiXong() {
        assertTrue(TtsStartPolicy.autoPlayAfterLoad(advancing = true, speakerPlaying = false, playRequested = true))
    }

    @Test
    fun chonTuMucLucHayMoTuChiTietTruyenLuonPhat() {
        assertTrue(TtsStartPolicy.autoPlayAfterLoad(advancing = false, speakerPlaying = false))
        assertTrue(TtsStartPolicy.autoPlayAfterLoad(advancing = false, speakerPlaying = true))
    }

    // endregion
}
