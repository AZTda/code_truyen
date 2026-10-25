package com.truyencloud.compose.platform

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberBookFilePicker(
    onFilePicked: (PickedBookFile) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestCallback by rememberUpdatedState(onFilePicked)
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { pickedUri ->
            scope.launch {
                val picked = withContext(Dispatchers.IO) { readPickedFile(context, pickedUri) }
                if (picked != null) latestCallback(picked)
            }
        }
    }

    return remember(launcher) {
        { launcher.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream")) }
    }
}

private fun readPickedFile(context: Context, uri: Uri): PickedBookFile? {
    val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull() ?: return null
    if (bytes.isEmpty()) return null

    val name = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull().orEmpty().ifBlank { "truyencloud_book.txt" }
    val mime = context.contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
    return PickedBookFile(name = name, bytes = bytes, mimeType = mime)
}
