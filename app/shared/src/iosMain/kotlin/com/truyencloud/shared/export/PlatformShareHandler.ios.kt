package com.truyencloud.shared.export

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.*
import platform.UIKit.*

actual object PlatformShareHandler {

    @OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
    actual fun shareTextFile(fileName: String, content: String, mimeType: String) {
        shareBinaryFile(fileName, content.encodeToByteArray(), mimeType)
    }

    @OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
    actual fun shareBinaryFile(fileName: String, bytes: ByteArray, mimeType: String) {
        if (bytes.isEmpty()) return
        try {
            val tempDir = NSTemporaryDirectory()
            val filePath = "$tempDir$fileName"
            val nsData = bytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            }
            nsData.writeToFile(filePath, atomically = true)

            val fileUrl = NSURL.fileURLWithPath(filePath)
            val activityVC = UIActivityViewController(
                activityItems = listOf(fileUrl),
                applicationActivities = null
            )

            val scene = UIApplication.sharedApplication.connectedScenes
                .filterIsInstance<UIWindowScene>()
                .firstOrNull()
            val window = scene?.windows?.filterIsInstance<UIWindow>()?.firstOrNull { it.isKeyWindow() }
                ?: scene?.windows?.filterIsInstance<UIWindow>()?.firstOrNull()

            val rootVC = window?.rootViewController
            var topVC = rootVC
            while (topVC?.presentedViewController != null) {
                topVC = topVC.presentedViewController
            }
            topVC?.presentViewController(activityVC, animated = true, completion = null)
        } catch (_: Exception) {}
    }
}
