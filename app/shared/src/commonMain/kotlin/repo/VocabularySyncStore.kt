package com.truyencloud.shared.repo

import com.truyencloud.shared.data.SyncKeys
import com.truyencloud.shared.data.VocabularySyncQueries
import com.truyencloud.shared.prefs.PrefKeys
import com.truyencloud.shared.prefs.SettingsStore
import com.truyencloud.shared.prefs.createSettingsStore

/**
 * Mốc kéo cũ của một tài khoản nằm ở SharedPreferences (bản trước khi chuyển vào Room). Đọc rồi XOÁ khoá — chỉ được
 * dùng một lần; 0 = không có.
 */
fun interface LegacyCursorSource {
    fun take(accountKey: String): Long
}

/** Mốc cũ `vocab_sync_since_<userId>` trong kho cài đặt; khoá theo id hồ sơ lẫn theo khoá tài khoản của token. */
internal class PrefsLegacyCursors(private val prefs: SettingsStore) : LegacyCursorSource {
    override fun take(accountKey: String): Long {
        val profileId = prefs.getString(PrefKeys.USER_ID, "").ifBlank { "anon" }
        val keys = setOf("$PREFIX$accountKey", "$PREFIX$profileId")
        val value = keys.maxOf { prefs.getLong(it, 0L) }
        keys.forEach { prefs.remove(it) }
        return value
    }

    private companion object {
        const val PREFIX = "vocab_sync_since_"
    }
}

/**
 * Lưu mốc `since` của lần đồng bộ từ vựng gần nhất (= `server_time_ms` máy chủ trả về), RIÊNG cho từng tài khoản.
 *
 * Mốc nằm trong bảng `sync_state` của Room, cùng cơ sở dữ liệu với từ vựng (xem [VocabularySyncQueries]): xoá dữ liệu
 * trên máy / đổi tài khoản cũng xoá mốc, nên lần đồng bộ sau kéo lại từ đầu. Bản cũ cất mốc ở SharedPreferences — thứ mà
 * việc xoá dữ liệu không đụng tới, nên máy vừa xoá hỏi máy chủ "có gì mới sau mốc X" và không bao giờ nhận lại từ vựng cũ.
 *
 * @param legacy chỉ để di trú mốc cũ MỘT lần (null = không có gì để di trú).
 */
open class VocabularySyncStore(
    private val queries: VocabularySyncQueries?,
    private val legacy: LegacyCursorSource? = null
) {
    /** Dựng từ kho cài đặt thật: giữ mốc cũ để di trú một lần. */
    constructor(queries: VocabularySyncQueries?, legacyPrefs: SettingsStore?) :
        this(queries, legacyPrefs?.let(::PrefsLegacyCursors))

    /**
     * Mốc kéo của [accountKey] (xem [SyncAccount.keyFor]); 0 = kéo từ đầu.
     *
     * Mốc cũ ở SharedPreferences chỉ được nhận (một lần) khi trên máy còn dữ liệu CŨ HƠN nó, tức là dữ liệu đó thật sự đã được
     * đồng bộ tới mốc. Máy đã bị xoá thì mốc nói dối, và máy xoá rồi lưu lại vài từ thì toàn bộ dòng đều MỚI hơn mốc — cả hai
     * đều phải kéo lại từ đầu (đồng thời chữa cho người đã dính lỗi cũ).
     */
    open suspend fun cursor(accountKey: String): Long {
        val dao = queries ?: return 0L
        dao.getSyncCursor(SyncKeys.vocabCursor(accountKey))?.let { return it }
        val old = legacy?.take(accountKey) ?: return 0L
        if (old <= 0L || dao.countWordsUpdatedUpTo(old) == 0) return 0L
        dao.putSyncCursor(SyncKeys.vocabCursor(accountKey), old)
        return old
    }

    open suspend fun saveCursor(accountKey: String, serverTimeMs: Long) {
        if (serverTimeMs > 0L) queries?.putSyncCursor(SyncKeys.vocabCursor(accountKey), serverTimeMs)
    }
}

/** Kho cài đặt mặc định; null nếu nền tảng chưa khởi tạo (ví dụ chạy kiểm thử) — mọi chỗ dùng đều chịu được null. */
internal fun defaultSettingsStore(): SettingsStore? = runCatching { createSettingsStore() }.getOrNull()
