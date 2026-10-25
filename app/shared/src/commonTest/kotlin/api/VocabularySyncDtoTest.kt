package com.truyencloud.shared.api

import com.truyencloud.shared.data.ReviewLog
import com.truyencloud.shared.data.SavedWord
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Khớp hợp đồng C7 (đồng bộ từ vựng) và hợp đồng chấm phát âm bằng bản chép lời thật. */
class VocabularySyncDtoTest {
    // Cùng cấu hình với HttpClient của ứng dụng (TruyenCloudApi.httpClient)
    private val json = Json {
        ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true
        explicitNulls = false; encodeDefaults = true
    }

    @Test
    fun yeuCauDayMangDeletedAtVaUpdatedAt() {
        val tombstone = SavedWord(id = "word_run", word = "run", updatedAt = 500, deletedAt = 500)
        val live = SavedWord(id = "word_go", word = "go", updatedAt = 300)
        val body = json.encodeToString(
            SyncVocabularyRemoteRequest.serializer(),
            SyncVocabularyRemoteRequest(words = listOf(tombstone, live))
        )
        assertTrue(body.contains("\"deletedAt\":500"), body)
        assertTrue(body.contains("\"deletedAt\":0"), "Từ còn sống vẫn gửi deletedAt = 0: $body")
        assertTrue(body.contains("\"updatedAt\":300"))
        assertTrue(body.contains("\"review_logs\":[]"))
    }

    @Test
    fun phanHoiDayCoStaleVaMocMayChu() {
        val res = json.decodeFromString(
            SyncVocabularyRemoteResponse.serializer(),
            """{"status":"success","words_synced":3,"words_stale":1,"stale_word_ids":["word_a"],
               "logs_synced":10,"logs_skipped":2,"synced_at":"2026-09-30T10:00:00+00:00","server_time_ms":1790000000000}"""
        )
        assertEquals(listOf("word_a"), res.stale_word_ids)
        assertEquals(1, res.words_stale)
        assertEquals(1_790_000_000_000L, res.server_time_ms)
    }

    @Test
    fun phanHoiMayChuCuKhongCoTruongMoiVanDocDuoc() {
        val res = json.decodeFromString(
            SyncVocabularyRemoteResponse.serializer(),
            """{"status":"success","words_synced":3,"logs_synced":10,"synced_at":"2026-01-01T00:00:00"}"""
        )
        assertEquals(0L, res.server_time_ms)
        assertTrue(res.stale_word_ids.isEmpty())
    }

    @Test
    fun keoVeGomBiaMoVaLogCoIdCuaMayKhac() {
        val res = json.decodeFromString(
            VocabularySyncDataResponse.serializer(),
            """{"words":[
                 {"id":"word_run","word":"run","updatedAt":500,"deletedAt":500},
                 {"id":"word_go","word":"go","updatedAt":300}],
               "review_logs":[{"id":7,"wordId":"word_go","reviewedAt":1000,"rating":3}],
               "synced_at":"x","server_time_ms":42}"""
        )
        assertEquals(42L, res.server_time_ms)
        val words = res.words.map { json.decodeFromJsonElement(SavedWord.serializer(), it) }
        assertTrue(words[0].isDeleted)
        assertFalse(words[1].isDeleted)
        assertEquals(0, words[1].reps, "Trường thiếu lấy giá trị mặc định")
        val log = json.decodeFromJsonElement(ReviewLog.serializer(), res.review_logs.single())
        assertEquals(7L, log.id) // id của máy khác: VocabularySyncMerge bỏ nó và khử trùng theo (wordId, reviewedAt)
        assertFalse(log.isPractice)
        assertEquals(-1, log.stateBefore, "Log cũ không biết trạng thái thẻ")
    }

    @Test
    fun chamPhatAmKhongNgheDuocThiKhongCoDiem() {
        val fallback = json.decodeFromString(
            PronunciationEvaluateResponse.serializer(),
            """{"word":"ephemeral","score":null,"overall_score":null,"is_fallback":true,
               "message":"Chưa nghe được giọng nói","accuracy_level":"no_speech","phonemes":[],"common_mistakes":[]}"""
        )
        assertTrue(fallback.isFallback)
        assertNull(fallback.realScore, "Không nghe được => không có điểm, không hiện điểm 0")
        assertEquals("Chưa nghe được giọng nói", fallback.message)
    }

    @Test
    fun chamPhatAmCoDiemThatVaCanChinh() {
        val res = json.decodeFromString(
            PronunciationEvaluateResponse.serializer(),
            """{"word":"ephemeral","transcript":"ephemerel","heard":"ephemerel","score":88,"overall_score":88,
               "is_fallback":false,"alignment_unit":"letter","accuracy_level":"good",
               "phonemes":[{"symbol":"a","label":"nghe thành 'e'","heard":"e","score":70,"status":"acceptable","op":"sub"}],
               "reference_phonemes":[{"symbol":"ɪ","label":"i (ngắn)","difficulty":"easy"}],
               "feedback_vi":"Gần đúng","common_mistakes":["x"]}"""
        )
        assertEquals(88, res.realScore)
        assertEquals("letter", res.alignmentUnit)
        assertEquals("sub", res.phonemes.single().op)
        assertEquals("ɪ", res.referencePhonemes.single().symbol)
    }

    @Test
    fun maychuCuChiCoOverallScoreVanCoDiem() {
        val old = json.decodeFromString(
            PronunciationEvaluateResponse.serializer(),
            """{"word":"run","overall_score":77,"accuracy_level":"good","phonemes":[],"feedback_vi":"ok","common_mistakes":[]}"""
        )
        assertEquals(77, old.realScore)
    }

    @Test
    fun yeuCauChamGuiBanChepLoiThat() {
        val body = json.encodeToString(
            PronunciationEvaluateRequest.serializer(),
            PronunciationEvaluateRequest(word = "ephemeral", spokenText = "ephemerel")
        )
        assertTrue(body.contains("\"spoken_text\":\"ephemerel\""), body)
    }
}
