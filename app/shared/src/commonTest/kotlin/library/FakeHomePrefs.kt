package com.truyencloud.shared.library

/**
 * Kho cài đặt giả trong bộ nhớ cho [HomePrefsStore] ([com.truyencloud.shared.prefs.SettingsStore] là expect class nên không giả được).
 * [writes] đếm số lần ghi để kiểm thử "đọc sai chủ thì không ghi lại".
 */
class FakeHomePrefs(initial: Map<String, String> = emptyMap()) : HomePrefsStore {
    val data = LinkedHashMap<String, String>(initial)
    var writes = 0
        private set

    /** Nếu đặt, mọi thao tác ném lỗi này (kho hỏng) — màn hình không được sập. */
    var failure: RuntimeException? = null

    override fun getString(key: String): String? {
        failure?.let { throw it }
        return data[key]?.ifEmpty { null }
    }

    override fun putString(key: String, value: String) {
        failure?.let { throw it }
        writes++
        data[key] = value
    }

    override fun remove(key: String) {
        failure?.let { throw it }
        data.remove(key)
    }
}
