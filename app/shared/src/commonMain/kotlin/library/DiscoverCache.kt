package com.truyencloud.shared.library

import com.truyencloud.shared.data.Novel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Bản chụp một bảng Khám phá tốt gần nhất, lưu bền để mở lại khi mất mạng.
 * [reasons] (id truyện → lý do gợi ý) và [personalized] được thêm sau: bản lưu cũ không có chúng vẫn đọc được.
 */
@Serializable
data class DiscoverSnapshot(
    val savedAt: Long = 0L,
    val novels: List<Novel> = emptyList(),
    val reasons: Map<String, String> = emptyMap(),
    val personalized: Boolean = false
) {
    /** [novels] ghép với [reasons]; không có lý do thì là "". */
    val entries: List<DiscoverEntry> get() = novels.map { DiscoverEntry(it, reasons[it.id].orEmpty()) }
}

object DiscoverCache {
    /** Đủ cho hai bảng xếp hạng mà vẫn nhỏ (vài chục KB) để lưu vào cài đặt. */
    const val MAX_ENTRIES = 60
    private const val MAX_DESCRIPTION = 160

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = false
    }

    fun encode(novels: List<Novel>, savedAt: Long): String = encodeEntries(novels.map { DiscoverEntry(it) }, savedAt)

    /** Như [encode] nhưng giữ lý do gợi ý và cờ cá nhân hoá. Bảng không có lý do cho ra đúng chuỗi như trước. */
    fun encodeEntries(entries: List<DiscoverEntry>, savedAt: Long, personalized: Boolean = false): String {
        val kept = entries.take(MAX_ENTRIES)
        // Bỏ phần mô tả dài: trang chi tiết tự tải lại đầy đủ từ máy chủ
        val slim = kept.map { it.novel.copy(description = it.novel.description.take(MAX_DESCRIPTION)) }
        return json.encodeToString(
            DiscoverSnapshot.serializer(),
            DiscoverSnapshot(savedAt, slim, kept.reasonsById(), personalized)
        )
    }

    /** null khi chưa có, hỏng, hoặc rỗng — bản lưu hỏng không bao giờ được làm sập màn hình. */
    fun decode(raw: String?): DiscoverSnapshot? {
        if (raw.isNullOrBlank()) return null
        val snap = try {
            json.decodeFromString(DiscoverSnapshot.serializer(), raw)
        } catch (_: Exception) {
            return null
        }
        val valid = snap.novels.filter { it.id.isNotBlank() && it.title.isNotBlank() }
        if (valid.isEmpty()) return null
        val ids = valid.mapTo(HashSet()) { it.id }
        return snap.copy(
            novels = valid,
            reasons = snap.reasons.filter { (id, reason) -> id in ids && reason.isNotBlank() }
        )
    }
}

/**
 * Bản lưu bền các bảng Khám phá, RIÊNG TỪNG TÀI KHOẢN (xem [HomePrefKeys.discoverCacheKey]). HomeViewModel sống suốt phiên
 * ứng dụng, qua cả đăng xuất / đăng nhập tài khoản khác / xoá dữ liệu; vì vậy mọi lần đọc ghi đều truyền id tài khoản
 * hiện tại thay vì giữ một bản lưu chung — "Dành cho bạn" của người này không bao giờ lọt sang người kia.
 *
 * [prefs] null (nền tảng chưa khởi tạo kho cài đặt) thì không nhớ gì giữa các lần mở. Mọi lỗi của kho bị nuốt: bản lưu
 * chỉ để mở nhanh, không bao giờ được làm sập màn hình.
 */
class DiscoverCacheStore(private val prefs: HomePrefsStore?) {

    init {
        // Bản lưu 1.1.x không phân biệt tài khoản và không còn được đọc: dọn đi để khỏi nằm lại vô thời hạn
        clearLegacy()
    }

    fun load(sort: DiscoverSort, accountId: String): DiscoverSnapshot? =
        DiscoverCache.decode(read(HomePrefKeys.discoverCacheKey(sort, accountId)))

    /** Danh sách rỗng xoá bản lưu thay vì ghi: lần sau không hiện lại danh sách đã lỗi thời. */
    fun save(sort: DiscoverSort, accountId: String, result: DiscoverResult, savedAt: Long) {
        val key = HomePrefKeys.discoverCacheKey(sort, accountId)
        if (result.entries.isEmpty()) delete(key)
        else write(key, DiscoverCache.encodeEntries(result.entries, savedAt, result.personalized))
    }

    fun clear(sort: DiscoverSort, accountId: String) = delete(HomePrefKeys.discoverCacheKey(sort, accountId))

    /** Xoá MỌI bản lưu (đủ các bảng) của một tài khoản — gọi khi dữ liệu của tài khoản đó bị xoá khỏi máy. */
    fun clearAccount(accountId: String) = HomePrefKeys.discoverCacheKeys(accountId).forEach(::delete)

    /** Xoá bản lưu kiểu cũ (không theo tài khoản). */
    fun clearLegacy() = HomePrefKeys.LEGACY_DISCOVER_CACHE_KEYS.forEach(::delete)

    private fun read(key: String): String? = try { prefs?.getString(key) } catch (_: Exception) { null }
    private fun write(key: String, value: String) { try { prefs?.putString(key, value) } catch (_: Exception) { } }
    private fun delete(key: String) { try { prefs?.remove(key) } catch (_: Exception) { } }
}
