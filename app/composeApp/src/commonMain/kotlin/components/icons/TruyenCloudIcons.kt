package com.truyencloud.compose.components.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Bộ icon nét viền bo tròn của TruyenCloud (lưới 24x24, nét 1,75), dựng bằng ImageVector thuần Compose Multiplatform.
 * Mỗi nhóm nằm trong một tệp riêng (xem các interface bên dưới); icon mới đặt vào nhóm gần nghĩa nhất.
 */
object TruyenCloudIcons :
    NavigationIcons,
    ChromeIcons,
    ReaderIcons,
    EditorActionIcons,
    AudioIcons,
    AmbientSoundIcons,
    CloudStorageIcons,
    AuthAccountIcons,
    VocabLanguageIcons,
    ReaderGestureIcons,
    ReadingImmersionIcons,
    PlatformIcons,
    AnalyticsIcons,
    DownloadStorageIcons,
    LanguageStudyIcons,
    AdvancedReaderIcons

internal fun buildIcon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector {
    return ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply(block).build()
}
