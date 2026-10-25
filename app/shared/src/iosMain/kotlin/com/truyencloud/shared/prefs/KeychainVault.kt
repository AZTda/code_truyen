package com.truyencloud.shared.prefs

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * Kho bí mật trên iOS: Keychain (kSecClassGenericPassword).
 *
 * `AfterFirstUnlockThisDeviceOnly`: đọc được khi máy đã mở khoá một lần (đủ cho đồng bộ nền),
 * và KHÔNG theo iCloud Keychain hay bản sao lưu sang máy khác.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object KeychainVault {
    private const val SERVICE = "com.truyencloud.app.secure"

    fun get(key: String): String = memScoped {
        val query = baseQuery(key)
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val found = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, found.ptr)
        CFRelease(query)
        if (status != errSecSuccess) return@memScoped ""
        val data = CFBridgingRelease(found.value) as? NSData ?: return@memScoped ""
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString().orEmpty()
    }

    /** Ghi đè nếu đã có. Trả false nếu Keychain từ chối. */
    fun put(key: String, value: String): Boolean {
        if (value.isEmpty()) {
            remove(key)
            return true
        }
        // KHÔNG dùng `value as NSString`: String của Kotlin không phải NSString, ép kiểu sẽ ném ClassCastException lúc chạy
        // (trình biên dịch đã cảnh báo "This cast can never succeed"). Tạo NSData thẳng từ các byte UTF-8.
        val raw = value.encodeToByteArray()
        val bytes = memScoped { NSData.create(bytes = allocArrayOf(raw), length = raw.size.toULong()) }
        remove(key)
        val attributes = baseQuery(key)
        val dataRef = CFBridgingRetain(bytes)
        CFDictionaryAddValue(attributes, kSecValueData, dataRef)
        CFDictionaryAddValue(attributes, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        val status = SecItemAdd(attributes, null)
        CFRelease(dataRef)
        CFRelease(attributes)
        return status == errSecSuccess
    }

    fun remove(key: String) {
        val query = baseQuery(key)
        SecItemDelete(query)
        CFRelease(query)
    }

    /** Chủ sở hữu phải CFRelease kết quả. */
    private fun baseQuery(key: String): CFMutableDictionaryRef? {
        val dict = CFDictionaryCreateMutable(
            null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr
        )
        val service = CFBridgingRetain(SERVICE)
        val account = CFBridgingRetain(key)
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dict, kSecAttrService, service)
        CFDictionaryAddValue(dict, kSecAttrAccount, account)
        // CFDictionary tự giữ (retain) giá trị khi thêm vào, nên trả lại phần mình đã retain
        CFRelease(service)
        CFRelease(account)
        return dict
    }
}
