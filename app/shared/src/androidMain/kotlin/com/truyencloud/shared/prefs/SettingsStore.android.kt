package com.truyencloud.shared.prefs

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/**
 * Bản cài đặt Android dùng SharedPreferences.
 *
 * SharedPreferences ghi bất đồng bộ qua `apply()` nhưng đọc từ bộ nhớ đệm trong
 * tiến trình, nên đọc ngay sau khi ghi vẫn ra giá trị mới.
 *
 * Token đăng nhập KHÔNG nằm ở đây mà ở [AndroidSecretVault] (mã hoá bằng Keystore).
 */
actual class SettingsStore internal constructor(
    private val prefs: SharedPreferences,
    private val secrets: AndroidSecretVault
) {

    // Mã đăng nhập luôn đi qua kho mã hoá. Mã cũ (ví dụ tiến trình nền kiểm tra chương mới) vẫn đọc nó bằng
    // getString(AUTH_TOKEN) — chuyển hướng ở đây để chúng không đọc ra chuỗi rỗng và không ghi bản rõ trở lại.
    actual fun getString(key: String, default: String): String {
        if (key == PrefKeys.AUTH_TOKEN) return secrets.get(key).ifEmpty { default }
        return try { prefs.getString(key, default) ?: default } catch (_: Exception) { default }
    }

    actual fun getInt(key: String, default: Int): Int =
        try { prefs.getInt(key, default) } catch (_: Exception) { default }

    actual fun getFloat(key: String, default: Float): Float =
        try { prefs.getFloat(key, default) } catch (_: Exception) { default }

    actual fun getBoolean(key: String, default: Boolean): Boolean =
        try { prefs.getBoolean(key, default) } catch (_: Exception) { default }

    actual fun getLong(key: String, default: Long): Long =
        try { prefs.getLong(key, default) } catch (_: Exception) { default }

    actual fun putString(key: String, value: String) {
        if (key == PrefKeys.AUTH_TOKEN) { secrets.put(key, value); return }
        prefs.edit().putString(key, value).apply()
    }
    actual fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    actual fun putFloat(key: String, value: Float) { prefs.edit().putFloat(key, value).apply() }
    actual fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    actual fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }

    actual fun remove(key: String) {
        if (key == PrefKeys.AUTH_TOKEN) secrets.remove(key)
        prefs.edit().remove(key).apply()
    }

    actual fun clear() {
        prefs.edit().clear().apply()
        secrets.clear()
    }

    actual fun getSecret(key: String): String = secrets.get(key)
    actual fun putSecret(key: String, value: String) { secrets.put(key, value) }
    actual fun removeSecret(key: String) { secrets.remove(key) }

    /**
     * Bản cũ lưu token dạng rõ trong `truyencloud_settings`, và allowBackup=true nên nó còn theo
     * bản sao lưu đám mây. Chuyển sang kho mã hoá rồi XOÁ bản rõ; nếu Keystore hỏng thì vẫn xoá
     * (người dùng chỉ phải đăng nhập lại, còn token rõ thì không được phép nằm lại).
     */
    internal fun migrateLegacySecrets() {
        val legacy = prefs.getString(PrefKeys.AUTH_TOKEN, null)
        if (legacy.isNullOrBlank()) return
        if (secrets.get(PrefKeys.AUTH_TOKEN).isEmpty()) secrets.put(PrefKeys.AUTH_TOKEN, legacy)
        prefs.edit().remove(PrefKeys.AUTH_TOKEN).commit()
    }

    companion object {
        private const val PREFS_NAME = "truyencloud_settings"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var appContext: Context? = null

        /** Gọi một lần trong Activity trước khi dùng createSettingsStore(). */
        fun init(context: Context) {
            appContext = context.applicationContext
        }

        internal fun create(): SettingsStore {
            val ctx = appContext
                ?: error("SettingsStore chưa được khởi tạo. Gọi SettingsStore.init(context) trước.")
            return SettingsStore(
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                AndroidSecretVault(ctx)
            ).also { it.migrateLegacySecrets() }
        }
    }
}

actual fun createSettingsStore(): SettingsStore = SettingsStore.create()
