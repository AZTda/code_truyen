package com.truyencloud.compose.components.icons

import androidx.compose.ui.graphics.vector.ImageVector

/** Học ngôn ngữ: luyện nói (micro). */
interface LanguageStudyIcons {
    val Mic: ImageVector get() = LanguageHolder.mic
}

private object LanguageHolder {
    val mic by lazyIcon(
        "Mic",
        "M12 14.5a3 3 0 0 0 3-3V6a3 3 0 0 0-6 0v5.5a3 3 0 0 0 3 3Z", "M6 11.5a6 6 0 0 0 12 0", "M12 17.5V21 M9 21h6"
    )
}
