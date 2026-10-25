package com.truyencloud.shared.update

import platform.Foundation.NSBundle

actual object AppUpdateInstaller {
    actual val platform: UpdatePlatform = UpdatePlatform.IOS

    /** CFBundleVersion — trước đây luôn trả 1 bất kể bản cài thật. */
    actual fun getCurrentVersionCode(): Int =
        UpdatePolicy.parseBundleVersion(NSBundle.mainBundle.infoDictionary?.get("CFBundleVersion") as? String)

    actual fun getCurrentVersionName(): String {
        return NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String ?: "1.0.0"
    }

    /** iOS không cài APK: không tải, không mở bất kỳ URL nào. */
    actual suspend fun downloadAndInstallApk(
        request: ApkDownloadRequest,
        onProgress: (Float) -> Unit,
        onError: (String) -> Unit
    ) {
        onError("iOS không hỗ trợ cập nhật trực tiếp. Hãy cập nhật qua TestFlight hoặc App Store.")
    }
}
