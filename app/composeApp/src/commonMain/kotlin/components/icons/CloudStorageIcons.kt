package com.truyencloud.compose.components.icons

import androidx.compose.ui.graphics.vector.ImageVector

/** Đám mây, tải xuống / lên, dọn dữ liệu. */
interface CloudStorageIcons {
    val CloudDone: ImageVector get() = CloudHolder.cloudDone
    val CloudDownload: ImageVector get() = CloudHolder.cloudDownload
    val FileDownload: ImageVector get() = CloudHolder.fileDownload
    val CloudOff: ImageVector get() = CloudHolder.cloudOff
    val FileUpload: ImageVector get() = CloudHolder.fileUpload
    val CleaningServices: ImageVector get() = CloudHolder.cleaningServices
}

private object CloudHolder {
    val cloudDone by lazyIcon("CloudDone", CLOUD_PATH, "M9 13.5l2.2 2.2 4.3-4.2")
    val cloudDownload by lazyIcon(
        "CloudDownload",
        "M4.4 15.27A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.44 8.28", "M12 13v8", "m8 17 4 4 4-4"
    )
    val fileDownload by lazyIcon("FileDownload", TRAY_DOWN)
    val cloudOff by lazyIcon(
        "CloudOff",
        "m2 2 20 20", "M5.78 5.78A7 7 0 0 0 9 19h8.5a4.5 4.5 0 0 0 1.31-.19",
        "M21.53 16.5A4.5 4.5 0 0 0 17.5 10h-1.79A7 7 0 0 0 10 5.07"
    )
    val fileUpload by lazyIcon("FileUpload", TRAY_UP)
    val cleaningServices by lazyIcon(
        "CleaningServices",
        "M21 21H8a2 2 0 0 1-1.42-.59l-4-4a2 2 0 0 1 0-2.82l10-10a2 2 0 0 1 2.83 0l6 6a2 2 0 0 1 0 2.82L12.83 21",
        "m5.08 11.09 8.83 8.83"
    )
}
