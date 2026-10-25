package com.truyencloud.compose.platform

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject

private var activeDelegate: BookPickerDelegate? = null

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberBookFilePicker(
    onFilePicked: (PickedBookFile) -> Unit
): () -> Unit = {
    val delegate = BookPickerDelegate { file ->
        activeDelegate = null
        onFilePicked(file)
    }
    activeDelegate = delegate

    val picker = UIDocumentPickerViewController(
        documentTypes = listOf("public.text", "org.idpf.epub-container"),
        inMode = UIDocumentPickerMode.UIDocumentPickerModeImport
    )
    picker.delegate = delegate
    topViewController()?.presentViewController(picker, animated = true, completion = null)
}

@OptIn(ExperimentalForeignApi::class)
private class BookPickerDelegate(
    private val onPicked: (PickedBookFile) -> Unit
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>
    ) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        val path = url.path ?: return
        val data = platform.Foundation.NSFileManager.defaultManager.contentsAtPath(path) ?: return
        val bytes = data.toByteArrayCompat()
        if (bytes.isEmpty()) return
        val name = url.lastPathComponent ?: "truyencloud_book.txt"
        val mime = if (name.endsWith(".epub", ignoreCase = true)) {
            "application/epub+zip"
        } else {
            "text/plain"
        }
        onPicked(PickedBookFile(name = name, bytes = bytes, mimeType = mime))
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        activeDelegate = null
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArrayCompat(): ByteArray {
    val size = length.toInt()
    if (size <= 0) return ByteArray(0)
    val result = ByteArray(size)
    result.usePinned { pinned ->
        platform.posix.memcpy(pinned.addressOf(0), this.bytes, this.length)
    }
    return result
}

private fun topViewController(): platform.UIKit.UIViewController? {
    val scene = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .firstOrNull()
    val window = scene?.windows
        ?.filterIsInstance<UIWindow>()
        ?.firstOrNull { it.isKeyWindow() }
        ?: scene?.windows?.filterIsInstance<UIWindow>()?.firstOrNull()
    var current = window?.rootViewController
    while (current?.presentedViewController != null) current = current.presentedViewController
    return current
}
