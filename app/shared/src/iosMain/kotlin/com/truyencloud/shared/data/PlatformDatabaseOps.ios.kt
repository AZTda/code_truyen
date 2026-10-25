package com.truyencloud.shared.data

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber

@OptIn(ExperimentalForeignApi::class)
internal actual object PlatformDatabaseOps {
    actual fun fileBytes(path: String): Long = try {
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
        (attributes?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
    } catch (_: Exception) {
        0L
    }

    /** iOS chỉ có đường kết nối Room (NativeSQLiteDriver), không có openHelper. */
    actual suspend fun vacuumFallback(db: LocalCache): Boolean = false
}
