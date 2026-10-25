package com.truyencloud.compose.components.icons

import androidx.compose.ui.graphics.vector.ImageVector

/** Đồng bộ đám mây và xuất thẻ Anki. */
interface AdvancedReaderIcons {
    val CloudSync: ImageVector get() = AdvancedHolder.cloudSync
    val CloudSyncDone: ImageVector get() = AdvancedHolder.cloudSyncDone
    val AnkiExport: ImageVector get() = AdvancedHolder.ankiExport
}

private object AdvancedHolder {
    val cloudSync by lazyIcon("CloudSync", CLOUD_PATH, "M10 16v-5 M8 12.5l2-2 2 2", "M14 11v5 M12 14.5l2 2 2-2")
    val cloudSyncDone by lazyIcon("CloudSyncDone", CLOUD_PATH, "M9 13.5l2.2 2.2 4.3-4.2")
    val ankiExport by lazyIcon(
        "AnkiExport",
        "M4 6.5A1.5 1.5 0 0 1 5.5 5h9A1.5 1.5 0 0 1 16 6.5v11a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 4 17.5Z",
        "M10 12h11 M18 9l3 3-3 3"
    )
}
