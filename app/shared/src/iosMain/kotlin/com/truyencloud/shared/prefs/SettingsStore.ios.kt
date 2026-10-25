package com.truyencloud.shared.prefs

import platform.Foundation.NSUserDefaults

/**
 * Bản cài đặt iOS dùng NSUserDefaults.
 *
 * NSUserDefaults không phân biệt Int/Float/Long nên các hàm đọc phải kiểm tra
 * khoá có tồn tại hay không trước, nếu không nó trả 0 thay vì giá trị mặc định.
 *
 * Token đăng nhập KHÔNG nằm ở đây mà ở Keychain ([KeychainVault]).
 */
actual class SettingsStore(private val defaults: NSUserDefaults) {

    private fun has(key: String): Boolean = defaults.objectForKey(key) != null

    // Mã đăng nhập luôn đi qua Keychain: mã đọc bằng getString(AUTH_TOKEN) được chuyển hướng, không đọc ra rỗng
    actual fun getString(key: String, default: String): String {
        if (key == PrefKeys.AUTH_TOKEN) return KeychainVault.get(key).ifEmpty { default }
        return defaults.stringForKey(key) ?: default
    }

    actual fun getInt(key: String, default: Int): Int =
        if (has(key)) defaults.integerForKey(key).toInt() else default

    actual fun getFloat(key: String, default: Float): Float =
        if (has(key)) defaults.floatForKey(key) else default

    actual fun getBoolean(key: String, default: Boolean): Boolean =
        if (has(key)) defaults.boolForKey(key) else default

    actual fun getLong(key: String, default: Long): Long =
        if (has(key)) defaults.integerForKey(key) else default

    actual fun putString(key: String, value: String) {
        if (key == PrefKeys.AUTH_TOKEN) { KeychainVault.put(key, value); return }
        defaults.setObject(value, key)
    }
    actual fun putInt(key: String, value: Int) { defaults.setInteger(value.toLong(), key) }
    actual fun putFloat(key: String, value: Float) { defaults.setFloat(value, key) }
    actual fun putBoolean(key: String, value: Boolean) { defaults.setBool(value, key) }
    actual fun putLong(key: String, value: Long) { defaults.setInteger(value, key) }

    actual fun remove(key: String) {
        if (key == PrefKeys.AUTH_TOKEN) KeychainVault.remove(key)
        defaults.removeObjectForKey(key)
    }

    actual fun clear() {
        val domain = platform.Foundation.NSBundle.mainBundle.bundleIdentifier
        if (domain != null) defaults.removePersistentDomainForName(domain)
        KeychainVault.remove(PrefKeys.AUTH_TOKEN)
    }

    actual fun getSecret(key: String): String = KeychainVault.get(key)
    actual fun putSecret(key: String, value: String) { KeychainVault.put(key, value) }
    actual fun removeSecret(key: String) { KeychainVault.remove(key) }

    /**
     * Bản cũ lưu token dạng rõ trong NSUserDefaults (nằm trong bản sao lưu iTunes/iCloud).
     * Chuyển sang Keychain rồi xoá bản rõ — xoá cả khi Keychain từ chối, để token rõ không nằm lại.
     */
    internal fun migrateLegacySecrets() {
        val legacy = defaults.stringForKey(PrefKeys.AUTH_TOKEN)
        if (legacy.isNullOrBlank()) return
        if (KeychainVault.get(PrefKeys.AUTH_TOKEN).isEmpty()) KeychainVault.put(PrefKeys.AUTH_TOKEN, legacy)
        defaults.removeObjectForKey(PrefKeys.AUTH_TOKEN)
    }
}

actual fun createSettingsStore(): SettingsStore =
    SettingsStore(NSUserDefaults.standardUserDefaults).also { it.migrateLegacySecrets() }
