package com.truyencloud.shared.library

import com.truyencloud.shared.prefs.SettingsStore
import com.truyencloud.shared.prefs.createSettingsStore
import com.truyencloud.shared.repo.AccountSwitchPolicy

/** Khoá cài đặt riêng của màn hình chính (tủ sách + khám phá). */
object HomePrefKeys {
    const val LIBRARY_SORT = "home_library_sort"

    /** Tiền tố bản lưu bền của bảng Khám phá (xem [discoverCacheKey]). */
    const val DISCOVER_CACHE_PREFIX = "home_discover_cache_"

    /**
     * Khoá bản lưu của MỘT bảng Khám phá thuộc MỘT tài khoản. Phải theo tài khoản: bảng "Dành cho bạn" do máy chủ
     * cá nhân hoá theo tủ sách, nên bản lưu của tài khoản A không bao giờ được hiện cho tài khoản B (hay cho khách).
     * Dấu '|' không có trong [DiscoverSort.apiValue] nên cặp (tài khoản, bảng) ↔ khoá là 1-1 dù id có ký tự lạ.
     *
     * @param accountId id hồ sơ đang dùng ("guest" cho khách; rỗng coi như khách).
     */
    fun discoverCacheKey(sort: DiscoverSort, accountId: String): String =
        "$DISCOVER_CACHE_PREFIX${accountSlot(accountId)}|${sort.apiValue}"

    /** Mọi khoá bản lưu Khám phá của một tài khoản (đủ các bảng), để xoá sạch khi dữ liệu tài khoản đó bị xoá. */
    fun discoverCacheKeys(accountId: String): List<String> =
        DiscoverSort.entries.map { discoverCacheKey(it, accountId) }

    /**
     * Khoá CŨ (bản 1.1.x) không phân biệt tài khoản: `home_discover_cache_recent|popular`. Không còn được đọc; chỉ được dọn đi.
     */
    val LEGACY_DISCOVER_CACHE_KEYS: List<String> =
        listOf(DiscoverSort.RECENT, DiscoverSort.POPULAR).map { DISCOVER_CACHE_PREFIX + it.apiValue }

    /** Id dùng để phân chia dữ liệu: rỗng = khách. */
    fun accountSlot(accountId: String): String = accountId.trim().ifEmpty { AccountSwitchPolicy.GUEST_ID }
}

/** Kho khóa-giá trị tối thiểu cho màn hình chính; tách khỏi [SettingsStore] (expect class) để kiểm thử bằng bản giả. */
interface HomePrefsStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)

    /** Xoá hẳn một khoá. Mặc định ghi chuỗi rỗng (đọc ra null) cho bản giả không có thao tác xoá. */
    fun remove(key: String) = putString(key, "")
}

internal class SettingsHomePrefs(private val store: SettingsStore) : HomePrefsStore {
    override fun getString(key: String): String? = store.getString(key, "").ifEmpty { null }
    override fun putString(key: String, value: String) = store.putString(key, value)
    override fun remove(key: String) = store.remove(key)
}

/**
 * Kho cài đặt thật của nền tảng, hoặc null nếu chưa khởi tạo (Android cần SettingsStore.init(context)) —
 * khi đó màn hình vẫn chạy, chỉ là không nhớ lựa chọn giữa các lần mở.
 */
fun defaultHomePrefs(): HomePrefsStore? =
    runCatching { SettingsHomePrefs(createSettingsStore()) as HomePrefsStore }.getOrNull()
