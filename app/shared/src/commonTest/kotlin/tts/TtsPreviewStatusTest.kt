package com.truyencloud.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TtsPreviewStatusTest {

    @Test
    fun luotNgheThuHongKhongBaoDaXong() {
        // Lỗi cũ: máy chủ lỗi -> isPlaying true rồi false -> màn hình báo 'Đã nghe thử xong.' dù chẳng có tiếng nào
        assertNotEquals(TtsPreviewStatus.FINISHED, TtsPreviewStatus.endMessage(TtsPreviewStatus.FAILED))
        assertEquals(TtsPreviewStatus.FAILED, TtsPreviewStatus.endMessage(TtsPreviewStatus.FAILED))
    }

    @Test
    fun luotNgheThuChuyenSangGiongMayVanGiuGhiChuKhiKetThuc() {
        assertEquals(TtsPreviewStatus.ONLINE_FAILED, TtsPreviewStatus.endMessage(TtsPreviewStatus.ONLINE_FAILED))
    }

    @Test
    fun luotNgheThuBinhThuongBaoDaXong() {
        assertEquals("Đã nghe thử xong.", TtsPreviewStatus.endMessage(null))
    }
}
