package com.truyencloud.shared.prefs

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Kho bí mật cho token đăng nhập: AES-256-GCM, khoá nằm trong Android Keystore (không bao giờ lộ ra ngoài).
 *
 * Ciphertext ghi vào SharedPreferences tên ĐÚNG [FILE_NAME]: res/xml/backup_rules.xml và
 * data_extraction_rules.xml loại tệp `truyencloud_secure_prefs.xml` khỏi sao lưu, vì khoá Keystore
 * không đi theo bản sao lưu — khôi phục sang máy khác chỉ sinh ra dữ liệu không giải mã được.
 *
 * Tên khoá được đưa vào AAD nên không thể tráo ciphertext của khoá này sang khoá khác.
 */
internal class AndroidSecretVault(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun get(key: String): String {
        val stored = prefs.getString(key, null) ?: return ""
        val parts = TokenEnvelope.decode(stored)
        if (parts == null) {
            // Không phải bao thư hợp lệ (hỏng / phiên bản lạ): bỏ đi thay vì giữ rác
            prefs.edit().remove(key).apply()
            return ""
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, parts.iv))
            cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(parts.cipherText), Charsets.UTF_8)
        } catch (_: Exception) {
            // Khoá Keystore mất / đổi, hoặc dữ liệu bị sửa: coi như chưa đăng nhập và dọn sạch
            prefs.edit().remove(key).apply()
            ""
        }
    }

    /** Trả true nếu đã ghi được bản mã hoá. Không bao giờ hạ cấp xuống lưu dạng rõ. */
    fun put(key: String, value: String): Boolean {
        if (value.isEmpty()) {
            remove(key)
            return true
        }
        return try {
            writeEncrypted(key, value)
        } catch (_: Exception) {
            // Một số máy hỏng khoá sau khi nâng cấp hệ điều hành: sinh lại khoá một lần rồi thử lại
            try {
                deleteKey()
                writeEncrypted(key, value)
            } catch (_: Exception) {
                false
            }
        }
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun writeEncrypted(key: String, value: String): Boolean {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
        val cipherText = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        // commit() thay vì apply(): ghi xong mới báo thành công để bước dọn bản rõ phía sau an toàn
        return prefs.edit().putString(key, TokenEnvelope.encode(cipher.iv, cipherText)).commit()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey {
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun deleteKey() {
        try {
            keyStore().deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val FILE_NAME = "truyencloud_secure_prefs"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "truyencloud_secret_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
