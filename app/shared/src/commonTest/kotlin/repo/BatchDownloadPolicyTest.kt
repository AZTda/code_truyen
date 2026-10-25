package com.truyencloud.shared.repo

import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.Novel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatchDownloadPolicyTest {
    @Test
    fun pollingBacksOffExponentiallyAndCaps() {
        assertEquals(2_000L, BatchPollPolicy.nextDelayMs(0))
        assertEquals(4_000L, BatchPollPolicy.nextDelayMs(1))
        assertEquals(8_000L, BatchPollPolicy.nextDelayMs(2))
        assertEquals(16_000L, BatchPollPolicy.nextDelayMs(3))
        assertEquals(30_000L, BatchPollPolicy.nextDelayMs(4))
        assertEquals(30_000L, BatchPollPolicy.nextDelayMs(50))
    }

    @Test
    fun pollingGivesUpAfterTooManyConsecutiveFailures() {
        assertFalse(BatchPollPolicy.shouldGiveUp(BatchPollPolicy.MAX_CONSECUTIVE_FAILURES - 1))
        assertTrue(BatchPollPolicy.shouldGiveUp(BatchPollPolicy.MAX_CONSECUTIVE_FAILURES))
    }

    @Test
    fun errorClassifierRecognisesSessionExpiryAndMissingChapters() {
        assertTrue(ApiErrors.isAuthError(Exception("Yêu cầu đăng nhập. Token không hợp lệ hoặc đã hết hạn.")))
        assertTrue(ApiErrors.isAuthError(Exception("Máy chủ trả về mã lỗi 401")))
        assertFalse(ApiErrors.isAuthError(Exception("Unable to resolve host")))
        assertTrue(ApiErrors.isNotFound(IllegalStateException("API error (404): Không tìm thấy chương số 31 trong mục lục.")))
        assertTrue(ApiErrors.isNotFound(Exception("Không tìm thấy tác vụ hoặc bạn không có quyền xem tác vụ này.")))
        assertFalse(ApiErrors.isNotFound(Exception("timeout")))
    }

    private fun novel(last: Int) = Novel(id = "n1", title = "T", lastReadChapter = last)
    private fun chapters(vararg numbers: Int) =
        numbers.map { Chapter(id = "n1_$it", novelId = "n1", chapterNumber = it, title = "C$it") }

    @Test
    fun readTargetOpensFirstChapterForUnreadNovel() {
        assertEquals(1, ReadTarget.resolve(novel(0), chapters(1, 2, 3))?.chapterNumber)
        assertNull(ReadTarget.resolve(novel(0), emptyList()))
    }

    @Test
    fun readTargetOpensLastReadChapter() {
        assertEquals(2, ReadTarget.resolve(novel(2), chapters(1, 2, 3))?.chapterNumber)
    }

    @Test
    fun readTargetDoesNotFallBackToChapterOneWhenTocIsMissingTheLastReadChapter() {
        // Máy mới vừa kéo tiến độ về (chương 57) nhưng mục lục chưa có hàng 57: phải mở 57, không phải chương 1.
        val target = ReadTarget.resolve(novel(57), chapters(1, 2, 3))
        assertNotNull(target)
        assertEquals(57, target.chapterNumber)
        assertEquals("n1_57", target.id)
        assertEquals(57, ReadTarget.resolve(novel(57), emptyList())?.chapterNumber)
    }
}
