package com.truyencloud.shared.repo

import com.truyencloud.shared.data.ReviewLog
import com.truyencloud.shared.data.ReviewLogKey
import com.truyencloud.shared.data.SavedWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VocabularySyncMergeTest {
    private fun word(updated: Long, deleted: Long = 0L, id: String = "word_run") =
        SavedWord(id = id, word = id.removePrefix("word_"), updatedAt = updated, deletedAt = deleted)

    @Test
    fun banMoiHonThang() {
        assertTrue(VocabularySyncMerge.remoteWins(word(100), word(200)))
        assertFalse(VocabularySyncMerge.remoteWins(word(300), word(200)))
    }

    @Test
    fun boDiaMoThamBangMocXoaChuKhongPhaiUpdatedAt() {
        // máy kia xoá lúc 500, bản cục bộ sửa lúc 400 -> xoá thắng
        assertTrue(VocabularySyncMerge.remoteWins(word(400), word(updated = 100, deleted = 500)))
        // cục bộ sửa lúc 600 (sau khi máy kia xoá) -> giữ bản cục bộ (từ "sống lại" có chủ ý)
        assertFalse(VocabularySyncMerge.remoteWins(word(600), word(updated = 100, deleted = 500)))
    }

    @Test
    fun hoaMocThiBiaMoThang() {
        assertTrue(VocabularySyncMerge.remoteWins(word(500), word(updated = 100, deleted = 500)))
        assertFalse(VocabularySyncMerge.remoteWins(word(updated = 100, deleted = 500), word(500)))
        // hai bên cùng là bia mộ cùng mốc: không cần ghi đè
        assertFalse(VocabularySyncMerge.remoteWins(word(updated = 1, deleted = 500), word(updated = 1, deleted = 500)))
    }

    @Test
    fun tuChuaTungCoThiChiNhanBanSong() {
        assertTrue(VocabularySyncMerge.remoteWins(null, word(100)))
        assertFalse(VocabularySyncMerge.remoteWins(null, word(updated = 100, deleted = 200)))
    }

    @Test
    fun trungChuKhacIdGiuBanMoiHon() {
        assertTrue(VocabularySyncMerge.remoteBeatsClash(word(100, id = "word_a"), word(200, id = "x")))
        assertFalse(VocabularySyncMerge.remoteBeatsClash(word(300, id = "word_a"), word(200, id = "x")))
    }

    private fun log(id: Long, wordId: String, at: Long) = ReviewLog(id = id, wordId = wordId, reviewedAt = at, rating = 3)

    @Test
    fun logKeoVeKhuTrungTheoWordIdVaReviewedAtBoQuaIdCuaMayKhac() {
        val existing = setOf(ReviewLogKey("word_run", 1000L))
        val remote = listOf(
            log(id = 1, wordId = "word_run", at = 1000),   // đã có (id 1 của máy kia trùng nhau rất dễ)
            log(id = 1, wordId = "word_run", at = 2000),   // mới, nhưng id trùng với dòng trên -> KHÔNG được ghi đè theo id
            log(id = 2, wordId = "word_go", at = 2000),    // mới
            log(id = 3, wordId = "word_go", at = 2000),    // trùng ngay trong lô
            log(id = 4, wordId = "", at = 3000),           // thiếu khoá
            log(id = 5, wordId = "word_x", at = 0)         // thiếu khoá
        )
        val fresh = VocabularySyncMerge.newLogs(remote, existing)
        assertEquals(listOf("word_run" to 2000L, "word_go" to 2000L), fresh.map { it.wordId to it.reviewedAt })
        assertTrue(fresh.all { it.id == 0L && it.isSynced }, "id về 0 để Room tự cấp, và đánh dấu đã đồng bộ")
    }
}
