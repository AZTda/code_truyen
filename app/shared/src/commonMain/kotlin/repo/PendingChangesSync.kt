package com.truyencloud.shared.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Một kho dữ liệu có thể còn thay đổi chưa lên máy chủ (tiến độ đọc, dấu trang, từ vựng). */
interface PendingSyncSource {
    /** Số mục chưa đồng bộ — chỉ đọc cơ sở dữ liệu, không chạm mạng. */
    suspend fun pendingCount(): Int

    /** Cố đẩy hết lên máy chủ. Thất bại thì chỉ để các mục ở lại hàng đợi ([pendingCount] vẫn > 0). */
    suspend fun flush()
}

/** Mặt cắt mà phiên đăng nhập cần khi rời tài khoản (tách khỏi [PendingChangesSync] để kiểm thử mà không cần luồng nền thật). */
interface PendingChanges {
    /** Tổng số mục chưa đồng bộ — chỉ đọc cơ sở dữ liệu, không chạm mạng. */
    suspend fun pendingCount(): Int

    /** Cố đẩy hết (có giới hạn thời gian, không ném lỗi) rồi trả về số mục CÒN LẠI; 0 = đã lên máy chủ hết. */
    suspend fun flushBounded(): Int
}

/**
 * Đẩy nốt thay đổi chưa đồng bộ TRƯỚC khi rời tài khoản (đăng xuất, đổi tài khoản).
 *
 * Bản cũ xoá token rồi xoá dữ liệu mà không đẩy gì: tiến độ còn trong khoảng chờ 8 giây, dấu trang / từ vựng đang chờ
 * đẩy, bia mộ xoá khỏi tủ... biến mất vĩnh viễn, và sau khi token bị xoá cũng không còn đẩy được nữa.
 */
class PendingChangesSync(
    private val sources: List<PendingSyncSource>,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) : PendingChanges {
    /** Tổng số mục chưa đồng bộ ở mọi nguồn. */
    override suspend fun pendingCount(): Int = sources.sumOf { source ->
        try {
            source.pendingCount()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Cố đẩy hết, tối đa [timeoutMs] cho TẤT CẢ nguồn (chạy song song), rồi trả về số mục CÒN LẠI (0 = đã lên máy chủ hết).
     * Không bao giờ ném lỗi và không bao giờ treo quá [timeoutMs]: đăng xuất / đổi tài khoản vẫn phải làm được khi mất mạng
     * hay máy chủ chậm. Mỗi nguồn được thử riêng nên một nguồn hỏng không chặn các nguồn còn lại.
     */
    override suspend fun flushBounded(): Int {
        withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                sources.map { source ->
                    async {
                        try {
                            source.flush()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                }.awaitAll()
            }
        }
        return pendingCount()
    }

    companion object {
        /** "Khoảng 5 giây": đủ cho một lượt đẩy trên mạng bình thường, đủ ngắn để không ai thấy đăng xuất bị treo. */
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}

/** Nối ba kho thật của ứng dụng; kho nào null (kiểm thử, chưa khởi tạo) thì bỏ qua. */
fun pendingChangesOf(
    reader: ReaderRepository?,
    bookmarks: BookmarkRepository?,
    vocabulary: VocabularyRepository?
): PendingChanges = PendingChangesSync(
    listOfNotNull(
        reader?.let { r -> LambdaSource(count = { r.pendingProgressCount() }, push = { r.flushDirtyProgress() }) },
        bookmarks?.let { b -> LambdaSource(count = { b.pendingCount() }, push = { b.syncWithServer(force = true) }) },
        vocabulary?.let { v -> LambdaSource(count = { v.pendingSyncCount() }, push = { v.sync() }) }
    )
)

private class LambdaSource(
    private val count: suspend () -> Int,
    private val push: suspend () -> Unit
) : PendingSyncSource {
    override suspend fun pendingCount(): Int = count()
    override suspend fun flush() = push()
}

/** Thông báo cho người dùng khi dữ liệu chưa kịp đồng bộ phải bỏ lại. */
object AccountNotices {
    const val TITLE = "Một số thay đổi chưa được đồng bộ"

    fun lostOnLogout(count: Int): String =
        "Đã đăng xuất và xoá dữ liệu trên máy, nhưng $count thay đổi chưa kịp đồng bộ lên máy chủ " +
            "(không có mạng hoặc máy chủ đang bận) nên đã mất. Phần đã đồng bộ vẫn còn trong tài khoản của bạn."

    fun lostOnSwitch(count: Int): String =
        "Bạn vừa đăng nhập tài khoản khác nên dữ liệu của tài khoản trước đã bị xoá khỏi máy. " +
            "$count thay đổi của tài khoản đó chưa kịp đồng bộ lên máy chủ nên đã mất."
}
