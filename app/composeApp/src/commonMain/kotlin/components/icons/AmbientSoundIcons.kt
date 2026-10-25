package com.truyencloud.compose.components.icons

import androidx.compose.ui.graphics.vector.ImageVector

/** Âm thanh nền khi nghe đọc. */
interface AmbientSoundIcons {
    val AmbientRain: ImageVector get() = AmbientHolder.ambientRain
    val AmbientFire: ImageVector get() = AmbientHolder.ambientFire
    val AmbientWave: ImageVector get() = AmbientHolder.ambientWave
    val AmbientCafe: ImageVector get() = AmbientHolder.ambientCafe
}

private object AmbientHolder {
    val ambientRain by lazyIcon(
        "AmbientRain",
        "M4 14.9A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.24",
        "M8 19v1 M8 14v1 M16 19v1 M16 14v1 M12 21v1 M12 16v1"
    )
    val ambientFire by lazyIcon("AmbientFire", FLAME)
    val ambientWave by lazyIcon(
        "AmbientWave",
        "M2 6c.6.5 1.2 1 2.5 1C7 7 7 5 9.5 5c2.6 0 2.4 2 5 2 2.5 0 2.5-2 5-2 1.3 0 1.9.5 2.5 1",
        "M2 12c.6.5 1.2 1 2.5 1 2.5 0 2.5-2 5-2 2.6 0 2.4 2 5 2 2.5 0 2.5-2 5-2 1.3 0 1.9.5 2.5 1",
        "M2 18c.6.5 1.2 1 2.5 1 2.5 0 2.5-2 5-2 2.6 0 2.4 2 5 2 2.5 0 2.5-2 5-2 1.3 0 1.9.5 2.5 1"
    )
    val ambientCafe by lazyIcon(
        "AmbientCafe",
        "M10 2v2 M14 2v2 M6 2v2",
        "M16 8a1 1 0 0 1 1 1v8a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4V9a1 1 0 0 1 1-1h14a4 4 0 1 1 0 8h-1"
    )
}
