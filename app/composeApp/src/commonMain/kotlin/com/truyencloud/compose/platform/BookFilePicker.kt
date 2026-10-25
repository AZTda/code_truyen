package com.truyencloud.compose.platform

import androidx.compose.runtime.Composable

data class PickedBookFile(
    val name: String,
    val bytes: ByteArray,
    val mimeType: String
)

/** Opens the native document picker and returns a supported book file. */
@Composable
expect fun rememberBookFilePicker(
    onFilePicked: (PickedBookFile) -> Unit
): () -> Unit
