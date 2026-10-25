package com.truyencloud.shared.repo

import com.truyencloud.shared.api.ApiHttpException
import com.truyencloud.shared.api.FriendlyErrors
import com.truyencloud.shared.api.UserFacingException
import com.truyencloud.shared.data.runNow
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/** "Đồng bộ ngay" chỉ được báo xanh khi mọi thứ thật sự lên máy chủ — các kho nuốt lỗi nên phải tin kết quả trả về. */
class SyncNowTest {

    @Test
    fun everyStepRunsEvenWhenAnEarlierOneFailsAndTheFirstFailureIsThrown() = runNow {
        val ran = mutableListOf<String>()
        val first = IllegalStateException("tiến độ hỏng")
        val failure = assertFailsWith<IllegalStateException> {
            SyncNow.runAll(
                { ran += "progress"; throw first },
                { ran += "bookmarks" },
                { ran += "vocabulary"; throw IllegalArgumentException("từ vựng hỏng") }
            )
        }
        assertSame(first, failure, "Ném lỗi ĐẦU TIÊN")
        assertEquals(listOf("progress", "bookmarks", "vocabulary"), ran, "Bước hỏng không chặn bước sau")
    }

    @Test
    fun noFailureMeansTheCallReturnsNormally() = runNow {
        var count = 0
        SyncNow.runAll({ count++ }, { count++ })
        assertEquals(2, count)
    }

    @Test
    fun cancellationIsNeverSwallowed() {
        runNow {
            assertFailsWith<CancellationException> { SyncNow.runAll({ throw CancellationException("huỷ") }, { }) }
        }
    }

    @Test
    fun progressThatFailedToPushOrStillHasDirtyRowsIsNotSuccess() {
        val pushFailed = ProgressSyncOutcome(changed = 3, pushFailed = true, error = null)
        val ex = assertFailsWith<UserFacingException> { SyncNow.requireProgressComplete(pushFailed, pendingAfter = 0) }
        assertEquals(SyncNow.MSG_PROGRESS_INCOMPLETE, ex.message)

        val stillDirty = ProgressSyncOutcome(changed = 0, pushFailed = false, error = null)
        assertFailsWith<UserFacingException> { SyncNow.requireProgressComplete(stillDirty, pendingAfter = 2) }
    }

    @Test
    fun aFailedPullThrowsTheRealErrorSoItCanBeMadeFriendly() {
        val http = ApiHttpException(503, "máy chủ bận")
        val thrown = assertFailsWith<ApiHttpException> {
            SyncNow.requireProgressComplete(ProgressSyncOutcome(0, pushFailed = false, error = http), pendingAfter = 0)
        }
        assertSame(http, thrown)
    }

    @Test
    fun cleanProgressPasses() {
        SyncNow.requireProgressComplete(ProgressSyncOutcome(5, pushFailed = false, error = null), pendingAfter = 0)
    }

    @Test
    fun vocabularyFailuresAreSurfacedAndOtherResultsPass() {
        val failure = assertFailsWith<UserFacingException> {
            SyncNow.requireVocabularySynced(VocabularySyncResult.Failure("Lỗi máy chủ (500): hỏng"))
        }
        assertIs<UserFacingException>(failure)
        assertEquals("Đồng bộ từ vựng thất bại: Lỗi máy chủ (500): hỏng", failure.message)

        val expired = assertFailsWith<UserFacingException> {
            SyncNow.requireVocabularySynced(VocabularySyncResult.Failure("hết hạn", sessionExpired = true))
        }
        assertEquals(FriendlyErrors.MSG_SESSION_EXPIRED, expired.message)

        SyncNow.requireVocabularySynced(null) // không có kho từ vựng
        SyncNow.requireVocabularySynced(VocabularySyncResult.Success(1, 2, 3, 4))
    }
}
