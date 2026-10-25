package com.truyencloud.shared.repo

import com.truyencloud.shared.api.ApiHttpException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Dịch trước chương kế tiếp: 401 chỉ tắt cho TOKEN bị từ chối (đăng nhập lại là bật lại, không cần tắt ứng dụng), còn 429/503
 * thì nghỉ một lúc thay vì dồn dập thêm vào xô "ai" dùng chung với bản dịch người đọc đang chờ.
 */
class TranslateAheadGateTest {
    private var token = "tokenA"
    private var now = 1_000_000L
    private val gate = TranslateAheadGate(currentToken = { token }, clock = { now })

    @Test
    fun guestsNeverTranslateAhead() {
        token = ""
        assertFalse(gate.canTranslate())
    }

    @Test
    fun loggedInUsersTranslateAhead() {
        assertTrue(gate.canTranslate())
    }

    @Test
    fun aRejectedTokenDisablesTranslateAheadUntilTheUserLogsInAgain() {
        gate.onAuthRejected("tokenA")
        assertFalse(gate.canTranslate(), "Cùng token bị từ chối thì không gửi nữa")

        // Hết phiên rồi đăng nhập lại: token mới, dịch trước phải chạy lại mà không cần khởi động lại ứng dụng.
        token = ""
        assertFalse(gate.canTranslate())
        token = "tokenB"
        assertTrue(gate.canTranslate())
    }

    @Test
    fun aLateRejectionOfAnOldTokenDoesNotDisableTheNewSession() {
        token = "tokenB" // người dùng đã đăng nhập lại trong lúc phản hồi 401 của phiên cũ còn đang bay
        gate.onAuthRejected("tokenA")
        assertTrue(gate.canTranslate())
    }

    @Test
    fun busyServerMakesTranslateAheadPauseForAWhile() {
        gate.onBusy()
        assertFalse(gate.canTranslate())
        now += TranslateAheadGate.COOL_DOWN_MS - 1
        assertFalse(gate.canTranslate())
        now += 1
        assertTrue(gate.canTranslate(), "Hết thời gian nghỉ thì thử lại")
    }

    @Test
    fun aClockMovedBackwardsDoesNotPauseForever() {
        gate.onBusy()
        now -= 10 * TranslateAheadGate.COOL_DOWN_MS
        assertTrue(gate.canTranslate())
    }

    @Test
    fun onlyRowSpecificRejectionsCountAsPermanent() {
        for (code in listOf(400, 409, 413, 422)) assertTrue(ApiErrors.isRowRejected(ApiHttpException(code, "x")), "$code")
        for (code in listOf(401, 404, 405, 408, 429, 500, 503)) assertFalse(ApiErrors.isRowRejected(ApiHttpException(code, "x")), "$code")
        assertFalse(ApiErrors.isRowRejected(Exception("mất mạng")))
    }

    @Test
    fun recognisesRateLimitAndOverloadFromStatusOrServerWording() {
        assertTrue(ApiErrors.isBusyOrRateLimited(ApiHttpException(429, "quá nhiều")))
        assertTrue(ApiErrors.isBusyOrRateLimited(ApiHttpException(503, "bận")))
        assertFalse(ApiErrors.isBusyOrRateLimited(ApiHttpException(404, "không có")))
        // TranslateApi chỉ ném Exception(detail) không kèm mã
        assertTrue(ApiErrors.isBusyOrRateLimited(Exception("Bạn thao tác quá nhanh. Vui lòng thử lại sau ít giây.")))
        assertTrue(ApiErrors.isBusyOrRateLimited(Exception("AI tạm thời chưa dịch được chương này. Vui lòng thử lại sau ít phút.")))
        assertTrue(ApiErrors.isBusyOrRateLimited(Exception("Máy chủ trả về mã lỗi 503")))
        assertFalse(ApiErrors.isBusyOrRateLimited(Exception("Không có nội dung gốc để dịch.")))
    }
}
