package com.truyencloud.shared.tts

import com.truyencloud.shared.library.AccountScopedPrefs
import com.truyencloud.shared.library.FakeHomePrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lưu chỗ nghe dở (đúng chữ) qua dừng / tắt ứng dụng: kho, tiết chế ghi, luật xoá, khôi phục và xoá theo tài khoản. */
class TtsResumeStoreTest {

    private val prefs = FakeHomePrefs()
    private val store = TtsResumeStore(prefs)
    private var now = 0L
    private val tracker = TtsResumeTracker(store, nowMs = { now })
    private val texts = listOf("Chương một: Khởi đầu.", "“Ngươi là ai?” hắn hỏi.", "Lâm Phong cười nhạt.")

    private fun point(chapter: Int, segment: Int = 1, offset: Int = 5, ms: Long = 900L) =
        TtsResumePolicy.pointFor("truyen-1", chapter, texts, TtsPlaybackPosition(segment, offset, ms, "my_duyen"))!!

    @Test
    fun luuRoiDocLaiDungChuVaMsCuaGiongAi() {
        store.save(point(7))
        val restored = TtsResumePolicy.restore(store.load("truyen-1", 7), texts)
        assertEquals(TtsPlaybackPosition(1, 5, 900L, "my_duyen"), restored)
        assertNull(store.load("truyen-1", 8))
        assertNull(store.load("truyen-khac", 7))
    }

    @Test
    fun chiGiuVaiChuongGanNhatMoiNhatLenDau() {
        for (ch in 1..7) store.save(point(ch))
        val kept = store.all().map { it.chapterNumber }
        assertEquals(listOf(7, 6, 5, 4, 3), kept)
        store.save(point(4, offset = 9))
        assertEquals(listOf(4, 7, 6, 5, 3), store.all().map { it.chapterNumber }, "lưu lại chương cũ thì nó lên đầu, không nhân đôi")
        assertEquals(9, store.load("truyen-1", 4)?.charOffset)
    }

    @Test
    fun dangPhatThiToiDaMoiHaiGiayGhiMotLanVaLuonLaViTriMoiNhat() {
        tracker.onPosition(point(3, offset = 1))
        assertEquals(1, prefs.writes, "lần đầu ghi ngay")
        for (i in 2..8) {
            now += 250
            tracker.onPosition(point(3, offset = i))
        }
        assertEquals(1, prefs.writes, "~4 lần/giây nhưng chưa đủ 2 giây")
        now += 300
        tracker.onPosition(point(3, offset = 9))
        assertEquals(2, prefs.writes)
        assertEquals(9, store.load("truyen-1", 3)?.charOffset)
    }

    @Test
    fun tamDungHoacVaoNenThiGhiNgayViTriConCho() {
        tracker.onPosition(point(3, offset = 1))
        now += 500
        tracker.onPosition(point(3, offset = 4))
        assertEquals(1, store.load("truyen-1", 3)?.charOffset)
        tracker.flush()
        assertEquals(4, store.load("truyen-1", 3)?.charOffset)
        val writes = prefs.writes
        tracker.flush()
        tracker.onPosition(point(3, offset = 4))
        tracker.flush()
        assertEquals(writes, prefs.writes, "vị trí không đổi thì không ghi lại")
    }

    @Test
    fun ngheXongHoacTuChonChoKhacThiXoaCaViTriChoGhi() {
        tracker.onPosition(point(3, offset = 1))
        now += 100
        tracker.onPosition(point(3, offset = 6))
        tracker.clear("truyen-1", 3)
        tracker.flush()
        assertNull(store.load("truyen-1", 3), "vị trí chờ ghi của chương vừa xoá không được ghi lại")
        store.save(point(4))
        tracker.clear("truyen-1", 3)
        assertNotNull(store.load("truyen-1", 4), "xoá một chương không đụng chương khác")
    }

    @Test
    fun doiTaiKhoanHoacXoaDuLieuThiMatCho() {
        store.save(point(3))
        store.save(point(4))
        AccountScopedPrefs.clear(prefs, listOf("user_a"))
        assertTrue(store.all().isEmpty())
        assertNull(prefs.data[TtsResumeStore.KEY])
    }

    @Test
    fun banDichDoiThiTimDungChuCuHoacBoQua() {
        val saved = point(3, segment = 1, offset = 8)
        // Thêm một đoạn ở đầu chương: chữ cũ nằm ở mảnh 2
        val shifted = listOf("Lời tựa.") + texts
        assertEquals(TtsPlaybackPosition(2, 8, 900L, "my_duyen"), TtsResumePolicy.restore(saved, shifted))
        // Dịch lại hẳn: không còn mảnh nào trùng chữ -> null (người gọi chọn đoạn như thường lệ)
        assertNull(TtsResumePolicy.restore(saved, listOf("Bản dịch khác hẳn.", "Hoàn toàn mới.")))
        assertNull(TtsResumePolicy.restore(null, texts))
    }

    @Test
    fun viTriVuotChuThiBiKep() {
        val p = TtsResumePolicy.pointFor("truyen-1", 1, texts, TtsPlaybackPosition(2, 9999))!!
        assertEquals(texts[2].length, p.charOffset)
        assertNull(TtsResumePolicy.pointFor("truyen-1", 1, texts, TtsPlaybackPosition(9, 0)))
        assertNull(TtsResumePolicy.pointFor("", 1, texts, TtsPlaybackPosition(1, 0)))
    }

    @Test
    fun khoHongThiKhongSap() {
        prefs.data[TtsResumeStore.KEY] = "{không phải json"
        assertTrue(store.all().isEmpty())
        prefs.failure = RuntimeException("hỏng")
        store.save(point(1))
        assertNull(store.load("truyen-1", 1))
    }
}
