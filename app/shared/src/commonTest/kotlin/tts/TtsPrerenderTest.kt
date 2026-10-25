package com.truyencloud.shared.tts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Dựng trước giọng AI (`/v1/tts/prerender`): thân yêu cầu, đọc phản hồi, và luật "chờ rồi tự phát". */
class TtsPrerenderTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    /** Một chương trong phản hồi THẬT của máy chủ (khoá đúng như routes/tts_prerender.py + tts_prerender_plan.summarize). */
    private val serverResponse = """
        {"status":"ok","engine":{"name":"vieneu","model":"v3nano","state":"ready","model_loaded":true,"queue_length":4,"avg_rtf":0.66},
         "chapters":[{"chapter_number":12,"state":"rendering","total_segments":40,"ready_segments":14,"estimated_seconds_remaining":95,
           "lead_needed_segments":6,"segment_count":42,"device_segments":2,"device_segment_indices":[5,9],"skipped_segments":0,
           "start_segment":0,"ready_prefix_segments":2,"can_start":false,"audio_seconds_estimate":520},
          {"chapter_number":13,"state":"no_text","total_segments":0,"ready_segments":0,"estimated_seconds_remaining":0,
           "lead_needed_segments":0,"segment_count":0,"device_segments":0,"device_segment_indices":[],"skipped_segments":0,
           "start_segment":0,"ready_prefix_segments":0,"can_start":false}]}
    """.trimIndent()

    private fun readiness(state: String = "rendering", lead: Int = 6, prefix: Int = 2, canStart: Boolean? = false, count: Int = 42) =
        TtsReadiness("n1", 12, "my_duyen", state, 14, 40, lead, prefix, count, 95, canStart)

    @Test
    fun thanYeuCauDungKhoaCuaMayChu() {
        val body = json.encodeToJsonElement(
            TtsPrerenderRequest.serializer(),
            TtsPrerenderRequest(novel_id = "n1", chapter_number = 12, count = 3, voice = "my_duyen", lang = null, priority = 1, from_segment = 7)
        ).jsonObject
        assertEquals(setOf("novel_id", "chapter_number", "count", "voice", "priority", "from_segment"), body.keys)
        assertEquals("3", body["count"].toString())
    }

    @Test
    fun docPhanHoiThatCuaMayChu() {
        val response = json.decodeFromString(TtsPrerenderResponse.serializer(), serverResponse)
        assertEquals(2, response.chapters.size)
        val r = TtsReadiness.from("n1", "my_duyen", response.chapters[0])
        assertEquals(14, r.readySegments)
        assertEquals(6, r.leadNeeded)
        assertEquals(42, r.segmentCount)
        assertFalse(r.canStart)
        assertTrue(TtsReadiness.from("n1", "my_duyen", response.chapters[1]).canStart, "no_text: không có gì để chờ")
    }

    @Test
    fun phatDuocKhiMayChuBaoDuHoacChuongDaXongHoacKhongCoGiDeCho() {
        assertTrue(readiness(canStart = true).canStart)
        assertTrue(readiness(state = "ready", canStart = false).canStart)
        assertTrue(readiness(state = "unavailable").canStart, "giọng AI tắt: phát luôn, luồng phát báo lỗi rồi dùng giọng điện thoại")
        assertTrue(readiness(state = "no_text").canStart)
        assertFalse(readiness(state = "queued").canStart)
        // Máy chủ chưa có khoá can_start: đủ số mảnh đầu để nghe liền mạch
        assertFalse(readiness(canStart = null, lead = 6, prefix = 5).canStart)
        assertTrue(readiness(canStart = null, lead = 6, prefix = 6).canStart)
        assertTrue(readiness(canStart = null, lead = 0, prefix = 1).canStart)
        assertFalse(readiness(canStart = null, lead = 0, prefix = 0).canStart, "chưa có cả mảnh đầu")
    }

    @Test
    fun dongTrangThaiEmVaPhanTram() {
        assertEquals("Đang chuẩn bị giọng đọc… 33%", readiness(lead = 6, prefix = 2).waitingLabel)
        assertEquals(99, readiness(lead = 6, prefix = 9).startPercent)
        assertNull(readiness(lead = 1, prefix = 0).startPercent)
        assertEquals("Đang chuẩn bị giọng đọc… ~95s", readiness(lead = 1, prefix = 0).waitingLabel)
    }

    @Test
    fun chiChoKhiDungChuongDungGiongVaChuKhop() {
        val r = readiness()
        assertTrue(TtsStartGate.mustWait(true, r, "n1", 12, "my_duyen", 42))
        assertFalse(TtsStartGate.mustWait(false, r, "n1", 12, "my_duyen", 42), "giọng máy: không bao giờ chờ máy chủ")
        assertFalse(TtsStartGate.mustWait(true, null, "n1", 12, "my_duyen", 42), "chưa biết tiến độ")
        assertFalse(TtsStartGate.mustWait(true, r, "n1", 12, "duc_tri", 42), "tiến độ của giọng khác")
        assertFalse(TtsStartGate.mustWait(true, r, "n1", 13, "my_duyen", 42), "tiến độ của chương khác")
        assertFalse(TtsStartGate.mustWait(true, r, "n1", 12, "my_duyen", 30), "chữ của máy chủ khác chữ app (bản dịch riêng)")
        assertFalse(TtsStartGate.mustWait(true, readiness(canStart = true), "n1", 12, "my_duyen", 42))
    }

    @Test
    fun choTiepChoToiKhiDuThoiChoKhiHongLienTiepHoacQuaLau() {
        assertTrue(TtsStartGate.keepWaiting(readiness(), 0, 10_000L, 42))
        assertFalse(TtsStartGate.keepWaiting(readiness(canStart = true), 0, 10_000L, 42))
        assertTrue(TtsStartGate.keepWaiting(null, 2, 10_000L, 42), "một hai lần hỏi hỏng: hỏi lại")
        assertFalse(TtsStartGate.keepWaiting(null, TtsStartGate.MAX_POLL_FAILURES, 10_000L, 42))
        assertFalse(TtsStartGate.keepWaiting(readiness(), 0, TtsStartGate.MAX_WAIT_MS, 42), "chờ quá lâu: phát luôn bằng giọng AI")
        assertFalse(TtsStartGate.keepWaiting(readiness(count = 10), 0, 10_000L, 42))
    }

    @Test
    fun yeuCauMacDinhLaChuongDangDocUuTienCaoNhat() {
        val req = TtsPrerenderRequest(novel_id = "n", chapter_number = 1, voice = "my_duyen")
        assertEquals(1, req.priority)
        assertEquals(1, req.count)
        assertEquals(0, req.from_segment)
        assertTrue(json.encodeToJsonElement(TtsPrerenderRequest.serializer(), req) is JsonObject)
    }
}
