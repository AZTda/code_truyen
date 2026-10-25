package com.truyencloud.compose.screens.home

import com.truyencloud.compose.components.NavTab
import com.truyencloud.shared.api.FriendlyErrors
import com.truyencloud.shared.data.getCurrentEpochMillis
import com.truyencloud.shared.library.DiscoverCacheStore
import com.truyencloud.shared.library.DiscoverFeed
import com.truyencloud.shared.library.DiscoverFeeds
import com.truyencloud.shared.library.DiscoverResult
import com.truyencloud.shared.library.DiscoverSort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Tab Khám phá của MỘT tài khoản: "Dành cho bạn" (chỉ khi đã đăng nhập), "Mới cập nhật", "Đọc nhiều". Mỗi bảng được tải
 * khi cần (mở tab, đổi chip, kéo làm mới) và có bản lưu bền RIÊNG TỪNG TÀI KHOẢN ([DiscoverCacheStore]) nên mở tab là có
 * nội dung ngay, kể cả khi offline; lỗi mạng giữ nguyên bản cũ kèm câu báo thân thiện. Quy tắc chuyển trạng thái của từng
 * bảng nằm ở [DiscoverFeeds] (thuần, có kiểm thử).
 *
 * ViewModel sống suốt phiên ứng dụng, qua cả đăng xuất / đăng nhập tài khoản khác / xoá dữ liệu, nên mọi thứ đang giữ
 * thuộc về [account] và phải bỏ đi khi tài khoản đổi ([switchAccount]) hoặc dữ liệu bị xoá ([reset]). Hai việc đó tăng
 * [generation]: lượt tải đang chạy bị huỷ, và kết quả của lượt cũ (kể cả đã về) không bao giờ được áp dụng hay lưu.
 *
 * @param loader tải một bảng từ máy chủ (NovelRepository.discoverEntries); tách ra để kiểm thử không cần mạng.
 * @param onFeedsChanged gọi sau mỗi lần các bảng đổi: gợi ý tìm kiếm lấy bảng "Dành cho bạn" từ đây.
 */
internal class HomeDiscoverController(
    private val loader: suspend (DiscoverSort) -> DiscoverResult,
    private val cache: DiscoverCacheStore,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<HomeUiState>,
    initialAccount: HomeAccount,
    private val onFeedsChanged: () -> Unit = {},
    private val now: () -> Long = { getCurrentEpochMillis() }
) {
    /** Tài khoản mà mọi bảng đang giữ là của họ. */
    var account: HomeAccount = initialAccount
        private set

    private var generation = 0
    private val jobs = HashMap<DiscoverSort, Job>()

    init {
        // Hiện ngay bản lưu của lần trước (nếu có) trong khi chờ mạng
        val initial = restored(DiscoverSort.defaultFor(initialAccount.loggedIn))
        state.update { it.copy(discover = initial) }
    }

    /** Mở tab: chỉ gọi mạng nếu bảng đang xem chưa có dữ liệu hoặc đã cũ. */
    fun ensureLoaded() = load(state.value.discover.sort, force = false)

    fun selectSort(sort: DiscoverSort) {
        val d = state.value.discover
        if (sort == d.sort || sort !in d.sorts) return
        state.update { it.copy(discover = it.discover.copy(sort = sort)) }
        load(sort, force = false)
    }

    /** Kéo làm mới / bấm "Thử lại": tải lại bảng đang xem. */
    fun refresh() = load(state.value.discover.sort, force = true)

    /**
     * Tài khoản đổi (đăng nhập, đăng xuất, đổi tài khoản). Bỏ sạch những gì đang giữ của tài khoản cũ NGAY trong cùng một lần
     * cập nhật trạng thái — không có khung hình nào còn danh sách của người trước — rồi dựng lại từ bản lưu của tài khoản
     * mới và về bảng mặc định của trạng thái mới.
     * @return false nếu không có gì đổi.
     */
    fun switchAccount(next: HomeAccount): Boolean {
        if (next == account) return false
        account = next
        rebuild(DiscoverSort.defaultFor(next.loggedIn))
        return true
    }

    /** Dữ liệu trên máy vừa bị xoá (vẫn là tài khoản này): bỏ mọi thứ đang giữ và nạp lại từ bản lưu (đã được dọn). */
    fun reset() = rebuild(DiscoverSort.coerce(state.value.discover.sort, account.loggedIn))

    private fun rebuild(sort: DiscoverSort) {
        generation++
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        val rebuilt = restored(sort) // đọc cài đặt ngoài vòng CAS của update
        state.update { it.copy(discover = rebuilt) }
        onFeedsChanged()
        if (state.value.currentTab == NavTab.DISCOVER) ensureLoaded()
    }

    private fun restored(sort: DiscoverSort): DiscoverUiState {
        val sorts = DiscoverSort.available(account.loggedIn)
        val prev = state.value.discover
        return DiscoverUiState(
            sort = sort,
            sorts = sorts,
            feeds = sorts.associateWith { DiscoverFeeds.fromSnapshot(cache.load(it, account.id)) },
            overnightIds = prev.overnightIds,
            translatedCounts = prev.translatedCounts
        )
    }

    private fun load(sort: DiscoverSort, force: Boolean) {
        val who = account
        // Khách không có bảng cá nhân hoá: không bao giờ xin "Dành cho bạn" khi chưa đăng nhập
        if (sort !in DiscoverSort.available(who.loggedIn)) return
        if (!force) {
            if (jobs[sort]?.isActive == true) return
            if (isFresh(state.value.discover.feed(sort), sort)) return
        }
        jobs.remove(sort)?.cancel()
        val gen = generation
        patch(gen) { it.copy(feeds = it.feeds + (sort to DiscoverFeeds.begin(it.feed(sort))), loading = it.loading + sort) }
        // LAZY: lượt tải phải nằm trong [jobs] trước khi thân hàm chạy (bộ điều phối chạy ngay thì [finally] mới so sánh đúng)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val outcome: Result<DiscoverResult> = try {
                    Result.success(loader(sort))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                // Tài khoản đổi / dữ liệu bị xoá trong lúc chờ: bỏ kết quả, không áp dụng và không lưu
                if (gen == generation) {
                    outcome.fold({ succeed(gen, who, sort, it) }, { fail(gen, sort, it) })
                }
            } finally {
                // Vẫn là lượt mới nhất của bảng này thì dọn cờ "đang tải", kể cả khi kết thúc bất thường
                if (gen == generation && jobs[sort] === coroutineContext[Job]) {
                    jobs.remove(sort)
                    patch(gen) { it.copy(loading = it.loading - sort) }
                }
            }
        }
        jobs[sort] = job
        job.start()
    }

    /**
     * Còn tươi thì mở lại tab không cần gọi mạng. Riêng "Dành cho bạn" mới chỉ nhận được bảng chung (tủ sách của tài khoản
     * chưa lên máy chủ) thì không bao giờ tươi: mỗi lần mở tab thử lại để gợi ý riêng xuất hiện ngay khi có dữ liệu.
     */
    private fun isFresh(feed: DiscoverFeed, sort: DiscoverSort): Boolean =
        !(sort.isPersonal && !feed.personalized) && DiscoverFeeds.isFresh(listOf(feed), now())

    /** Thành công → thay thế hoàn toàn (kể cả khi rỗng) và lưu bền theo tài khoản đã gửi yêu cầu. */
    private fun succeed(gen: Int, who: HomeAccount, sort: DiscoverSort, result: DiscoverResult) {
        val t = now()
        // Bản lưu chỉ để mở nhanh lần sau: ghi hỏng thì bỏ qua, không được làm sập màn hình (lệnh này chạy trong coroutine)
        try { cache.save(sort, who.id, result, t) } catch (_: Exception) { }
        patch(gen) { it.copy(feeds = it.feeds + (sort to DiscoverFeeds.succeeded(result, t)), loading = it.loading - sort) }
        onFeedsChanged()
    }

    /** Thất bại → giữ dữ liệu cũ (của CHÍNH tài khoản này, vì đã dựng lại khi đổi tài khoản), ghi câu lỗi thân thiện. */
    private fun fail(gen: Int, sort: DiscoverSort, error: Throwable) {
        val message = FriendlyErrors.of(error)
        patch(gen) { it.copy(feeds = it.feeds + (sort to DiscoverFeeds.failed(it.feed(sort), message)), loading = it.loading - sort) }
    }

    private inline fun patch(gen: Int, change: (DiscoverUiState) -> DiscoverUiState) {
        if (gen != generation) return
        state.update { it.copy(discover = change(it.discover)) }
    }
}
