package com.truyencloud.shared.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal actual object PlatformDatabaseOps {
    actual fun fileBytes(path: String): Long = try {
        File(path).takeIf { it.exists() }?.length() ?: 0L
    } catch (_: Exception) {
        0L
    }

    actual suspend fun vacuumFallback(db: LocalCache): Boolean = withContext(Dispatchers.IO) {
        try {
            db.openHelper.writableDatabase.execSQL("VACUUM")
            true
        } catch (_: Exception) {
            false
        }
    }
}
