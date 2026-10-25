package com.truyencloud.shared.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

actual object PlatformShareHandler {
    private var appContext: Context? = null

    /** Gọi một lần trong Application.onCreate hoặc Activity.onCreate. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    actual fun shareTextFile(fileName: String, content: String, mimeType: String) {
        shareBinaryFile(fileName, content.toByteArray(Charsets.UTF_8), mimeType)
    }

    actual fun shareBinaryFile(fileName: String, bytes: ByteArray, mimeType: String) {
        val ctx = appContext ?: return
        try {
            val cacheDir = File(ctx.cacheDir, "exports")
            cacheDir.mkdirs()
            val file = File(cacheDir, fileName)
            file.writeBytes(bytes)

            val uri = FileProvider.getUriForFile(
                ctx,
                "${ctx.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "Chia sẻ tệp TruyenCloud")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(chooser)
        } catch (_: Exception) {}
    }
}
