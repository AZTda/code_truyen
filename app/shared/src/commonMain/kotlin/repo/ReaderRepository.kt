package com.truyencloud.shared.repo

import com.truyencloud.shared.api.ProgressApi
import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.ChapterDao
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.NovelDao
import com.truyencloud.shared.data.ReadingSession
import com.truyencloud.shared.data.ReadingSessionDao
import com.truyencloud.shared.data.SyncKeys
import com.truyencloud.shared.data.SyncStateDao
import com.truyencloud.shared.data.getTodayDateString
import com.truyencloud.shared.data.getCurrentEpochMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ReaderRepository(
    private val novelDao: NovelDao,
    private val chapterDao: ChapterDao,
    private val readingSessionDao: ReadingSessionDao? = null,
    private val progressApi: ProgressApi? = null,
    private val syncScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Sổ ghi nhớ đồng bộ (bia mộ xoá khỏi tủ). Không truyền thì truyện đã xoá có thể sống lại khi kéo từ máy chủ. */
    private val syncStateDao: SyncStateDao? = null,
    /**
     * Việc đồng bộ thêm chạy sau mỗi lượt [syncAllProgressFromServer] thành công (mở ứng dụng, mở tủ sách,
     * vừa đăng nhập) — nơi gắn đồng bộ dấu trang: `{ bookmarkRepo.syncWithServer() }`. Lượt dấu trang đã giãn
     * cách ~30 giây và chạy ngay khi vừa đổi tài khoản, nên gọi dày đặc cũng không tốn.
     */
    private val onFullSync: (suspend () -> Unit)? = null
) {
    private val progressSync = ProgressSyncCoordinator(novelDao, chapterDao, progressApi, syncStateDao, syncScope)

    private fun currentTimestamp(): Long = getCurrentEpochMillis()

    /**
     * Lưu tiến độ đọc của chương đang mở.
     *
     * - Vị trí cuộn luôn được lưu; cờ "đã đọc" của chương CHỈ bật khi đã cuộn tới
     *   [READ_SCROLL_THRESHOLD] (bản cũ bật ở mọi lần lưu, kể cả khi vừa mở chương scroll = 0).
     * - Truyện chưa có trong tủ sách (chưa có hàng) thì lần lưu đầu tiên CHÈN hàng trước, nhờ đó
     *   đọc một truyện là tự thêm vào tủ ở kệ "đang đọc".
     * - Mọi thay đổi đánh dấu "chưa đẩy" rồi hẹn một lượt đẩy gộp; đẩy hụt thì thử lại khi ứng
     *   dụng ra nền / quay lại (xem [LifecycleSyncManager]).
     */
    suspend fun saveReadingProgress(novel: Novel?, novelId: String, chapterNumber: Int, scroll: Float) {
        val safeScroll = scroll.coerceIn(0f, 1f)
        val rows = chapterDao.updateReadingProgress(
            novelId, chapterNumber, safeScroll, markRead = safeScroll >= READ_SCROLL_THRESHOLD
        )
        if (rows == 0) {
            // Chưa có hàng chương (vd. đọc từ bản tải chưa kịp lưu): tạo hàng tối thiểu để giữ vị trí.
            chapterDao.insertChaptersIfAbsent(
                listOf(
                    Chapter(
                        id = "${novelId}_$chapterNumber",
                        novelId = novelId,
                        chapterNumber = chapterNumber,
                        title = "Chương $chapterNumber",
                        isRead = safeScroll >= READ_SCROLL_THRESHOLD,
                        scrollPosition = safeScroll
                    )
                )
            )
        }

        val now = currentTimestamp()
        var updated = novelDao.updateReadingProgress(novelId, chapterNumber, now)
        if (updated == 0 && novel != null) {
            if (chapterNumber <= 1 && safeScroll == 0f) {
                val restored = try { pullProgressFromServer(novelId, novel) } catch (_: Exception) { null }
                if (restored != null && restored > 1) return
            }
            val inserted = novelDao.insertIfAbsent(
                novel.copy(
                    id = novelId,
                    lastReadChapter = chapterNumber,
                    shelfName = "reading",
                    isFavorite = false,
                    lastReadAt = now,
                    addedAt = now,
                    syncUpdatedAt = now,
                    syncDirty = true
                )
            )
            // Một lần lưu khác vừa chèn hàng trước mình: cập nhật hàng đó thay vì bỏ mất lần đọc này.
            updated = if (inserted == -1L) novelDao.updateReadingProgress(novelId, chapterNumber, now) else 1
            // Đọc lại thì không còn là "đã xoá khỏi tủ".
            syncStateDao?.delete(SyncKeys.libraryRemoved(novelId))
        }

        if (updated > 0) progressSync.schedulePush(novelId)
    }

    /**
     * Giữ để tương thích: hẹn một lượt đẩy cho truyện. Giá trị đẩy lên luôn lấy từ Room lúc chạy
     * (đã có giờ sửa và vị trí cuộn chuẩn), không dùng các tham số truyền vào.
     */
    fun syncProgressToServer(
        novelId: String,
        currentChapter: Int = 0,
        scrollPosition: Float = 0f,
        shelfName: String = "reading",
        isFavorite: Boolean = false
    ) {
        progressSync.schedulePush(novelId)
    }

    /** Đẩy ngay mọi thay đổi tiến độ/kệ/yêu thích còn chờ. @return số truyện đã đẩy. */
    suspend fun flushDirtyProgress(): Int = progressSync.flushDirty()

    suspend fun recordReadingSession(minutes: Int, chaptersCompleted: Int = 0) {
        if (readingSessionDao == null || (minutes <= 0 && chaptersCompleted <= 0)) return
        try {
            val dateStr = getTodayDateString()
            val nowEpoch = currentTimestamp()

            val existing = readingSessionDao.getSessionByDate(dateStr)
            if (existing != null) {
                readingSessionDao.incrementProgress(dateStr, minutes, chaptersCompleted, nowEpoch)
            } else {
                readingSessionDao.upsertSession(
                    ReadingSession(
                        date = dateStr,
                        minutesRead = minutes,
                        chaptersRead = chaptersCompleted,
                        wordsRead = minutes * 250,
                        lastUpdatedAt = nowEpoch
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // -----------------------------------------------------------------------
    // Dong bo tien do: chieu DOC VE
    //
    // Chieu day len da chay tu lau (syncProgressToServer), nhung khong cho nao
    // goi GET /progress/{novelId}, nen may moi cai dat khong lay lai duoc cho
    // dang doc — dong bo chi co mot nua.
    // -----------------------------------------------------------------------

    /**
     * Lấy tiến độ của MỘT truyện trên máy chủ rồi hợp nhất vào dữ liệu máy (xem [ProgressConflict]):
     * so theo giờ sửa, rồi chương, rồi vị trí cuộn — bản nào mới hơn thì thắng, kể cả khi cùng chương.
     * Truyện có tiến độ trên máy chủ mà máy này chưa có thì được thêm vào tủ (dùng [fallbackNovel]
     * hoặc metadata máy chủ trả kèm).
     *
     * @return số chương đã khôi phục nếu khác chương cục bộ, hoặc null nếu không đổi gì đáng báo.
     */
    suspend fun pullProgressFromServer(novelId: String, fallbackNovel: Novel? = null): Int? =
        progressSync.pullOne(novelId, fallbackNovel)

    /**
     * Dong bo toan bo tien do doc: đẩy thay đổi còn dirty, rồi kéo cả tủ sách từ máy chủ — máy MỚI
     * dựng lại được tủ sách (kể cả kệ và yêu thích), truyện bị xoá ở máy khác cũng bị gỡ ở đây.
     * Thuong goi khi khoi dong ung dung, nguoi dung keo lam moi o Tu Sach,
     * hoac vua dang nhap thanh cong tren thiet bi moi.
     *
     * @return so luong truyen da duoc them/cap nhat/go theo may chu.
     */
    suspend fun syncAllProgressFromServer(): Int = syncAllProgress().changed

    /**
     * Như [syncAllProgressFromServer] nhưng trả cả kết quả đẩy / kéo: các lỗi vẫn bị nuốt (không làm sập nền) song nơi cần
     * báo thật — "Đồng bộ ngay" — đọc được nửa nào hỏng thay vì báo xanh khi tiến độ còn dirty.
     */
    suspend fun syncAllProgress(): ProgressSyncOutcome {
        val outcome = progressSync.syncAllDetailed()
        val hook = onFullSync
        if (hook != null && progressSync.canSync()) {
            try {
                hook()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Lỗi của việc đồng bộ thêm không được làm hỏng kết quả đồng bộ tiến độ.
            }
        }
        return outcome
    }

    /** Số thay đổi tiến độ chưa lên máy chủ (truyện dirty + truyện đã xoá khỏi tủ chưa báo) — để cảnh báo trước khi xoá dữ liệu. */
    suspend fun pendingProgressCount(): Int = progressSync.pendingCount()

    // -----------------------------------------------------------------------
    // So lieu doc THAT
    //
    // The thong ke trong man Cai dat truoc day hien hang so: 48.5 gio doc,
    // 158 chuong, chuoi 7 ngay, 12 tac pham — cung mot con so cho moi nguoi,
    // khong doi du da doc bao nhieu. Bang reading_sessions da co du lieu that
    // (ReaderViewModel goi recordReadingSession khi doc), chi la chua ai doc no.
    // -----------------------------------------------------------------------

    /** Chuoi ngay lien tiep — MOT dinh nghia chung cho moi man hinh (doc HOAC on the, xem ActivityStreak). */
    suspend fun computeReadingStreakDays(): Int {
        val dao = readingSessionDao ?: return 0
        return ActivityStreak.compute(reading = dao).days
    }

    suspend fun computeReadingStats(): ReadingStats {
        val dao = readingSessionDao ?: return ReadingStats()
        return try {
            ReadingStats(
                streakDays = computeReadingStreakDays(),
                totalMinutes = dao.totalMinutes(),
                totalChapters = dao.totalChapters(),
                totalWords = dao.totalWords(),
                novelCount = novelDao.countNovels()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            ReadingStats()
        }
    }

    companion object {
        /** Cuộn tới tỷ lệ này của chương thì tính là đã đọc (không cần cuộn chạm đáy). */
        const val READ_SCROLL_THRESHOLD = 0.85f
    }
}

/** So lieu doc tong hop de hien thi tren the thong ke. */
data class ReadingStats(
    val streakDays: Int = 0,
    val totalMinutes: Int = 0,
    val totalChapters: Int = 0,
    val totalWords: Int = 0,
    val novelCount: Int = 0
) {
    val totalHours: Float get() = totalMinutes / 60f
    /** "48.5" — mot chu so thap phan, khong phu thuoc String.format (khong co tren KMP). */
    val totalHoursLabel: String
        get() {
            val tenths = (totalMinutes * 10) / 60
            return "${tenths / 10}.${tenths % 10}"
        }
}
