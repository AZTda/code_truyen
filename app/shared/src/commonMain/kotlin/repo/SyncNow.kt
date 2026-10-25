package com.truyencloud.shared.repo

import com.truyencloud.shared.api.FriendlyErrors
import com.truyencloud.shared.api.UserFacingException
import kotlinx.coroutines.CancellationException

/**
 * Luật của "Đồng bộ ngay" trong Cài đặt: chạy đủ mọi bước và chỉ được báo xanh khi mọi thứ thật sự lên máy chủ.
 *
 * Các kho nuốt lỗi đẩy để không làm sập nền, nên chỉ tin kết quả trả về: tiến độ đẩy hụt hay còn dirty, từ vựng thất bại
 * đều là thất bại — bản cũ gọi `SyncStatus.reportSuccess()` vô điều kiện nên dòng "Đồng bộ đám mây" xanh dù còn dữ liệu chưa lên.
 */
object SyncNow {
    const val MSG_PROGRESS_INCOMPLETE = "Chưa đẩy hết thay đổi tiến độ đọc lên máy chủ. Sẽ thử lại sau."

    /**
     * Chạy lần lượt MỌI bước (một bước hỏng không chặn bước sau), rồi ném lỗi ĐẦU TIÊN nếu có. Không ném = tất cả đã xong.
     */
    suspend fun runAll(vararg steps: suspend () -> Unit) {
        var failure: Throwable? = null
        for (step in steps) {
            try {
                step()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failure == null) failure = e
            }
        }
        failure?.let { throw it }
    }

    /** Tiến độ: lỗi kéo thì ném chính lỗi đó; đẩy hụt hoặc còn [pendingAfter] mục dirty thì ném thông báo thân thiện. */
    fun requireProgressComplete(outcome: ProgressSyncOutcome, pendingAfter: Int) {
        outcome.error?.let { throw it }
        if (outcome.pushFailed || pendingAfter > 0) throw UserFacingException(MSG_PROGRESS_INCOMPLETE)
    }

    /** Từ vựng: [VocabularySyncResult.Failure] thì ném; null (không có kho) hoặc thành công thì thôi. */
    fun requireVocabularySynced(result: VocabularySyncResult?) {
        if (result is VocabularySyncResult.Failure) {
            throw UserFacingException(
                if (result.sessionExpired) FriendlyErrors.MSG_SESSION_EXPIRED
                else "Đồng bộ từ vựng thất bại: ${result.message}"
            )
        }
    }
}
