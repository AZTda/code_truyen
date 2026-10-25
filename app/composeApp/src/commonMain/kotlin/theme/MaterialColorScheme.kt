package com.truyencloud.compose.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * Ánh xạ [AppPalette] sang `ColorScheme` của Material để các thành phần Material chưa thay (Switch, TextField, Slider,
 * AlertDialog...) cũng ra đúng màu Nordic. Container của Material cần màu đặc nên [AppPalette.accentSoft] được trải
 * lên nền trước khi dùng.
 */
internal fun AppPalette.toMaterialColorScheme(): ColorScheme {
    val primaryContainer = accentSoft.compositeOver(card)
    val errorContainer = errorSoft.compositeOver(card)
    return if (isDark) {
        darkColorScheme(
            primary = accent, onPrimary = onAccent,
            primaryContainer = primaryContainer, onPrimaryContainer = onAccentSoft,
            inversePrimary = accentStrong,
            secondary = accent, onSecondary = onAccent,
            secondaryContainer = primaryContainer, onSecondaryContainer = onAccentSoft,
            tertiary = accent, onTertiary = onAccent,
            tertiaryContainer = primaryContainer, onTertiaryContainer = onAccentSoft,
            background = bg, onBackground = textPrimary,
            surface = surface, onSurface = textPrimary,
            surfaceVariant = cardHigh, onSurfaceVariant = textSecondary,
            surfaceTint = Color.Transparent,
            inverseSurface = textPrimary, inverseOnSurface = bg,
            error = error, onError = onAccent,
            errorContainer = errorContainer, onErrorContainer = error,
            outline = outline, outlineVariant = hairline,
            scrim = Color.Black,
            surfaceBright = cardHigh, surfaceDim = bg,
            surfaceContainerLowest = bg, surfaceContainerLow = surface, surfaceContainer = card,
            surfaceContainerHigh = cardHigh, surfaceContainerHighest = cardHigh
        )
    } else {
        lightColorScheme(
            primary = accent, onPrimary = onAccent,
            primaryContainer = primaryContainer, onPrimaryContainer = onAccentSoft,
            inversePrimary = accentStrong,
            secondary = accent, onSecondary = onAccent,
            secondaryContainer = primaryContainer, onSecondaryContainer = onAccentSoft,
            tertiary = accent, onTertiary = onAccent,
            tertiaryContainer = primaryContainer, onTertiaryContainer = onAccentSoft,
            background = bg, onBackground = textPrimary,
            surface = surface, onSurface = textPrimary,
            surfaceVariant = cardHigh, onSurfaceVariant = textSecondary,
            surfaceTint = Color.Transparent,
            inverseSurface = textPrimary, inverseOnSurface = bg,
            error = error, onError = onAccent,
            errorContainer = errorContainer, onErrorContainer = error,
            outline = outline, outlineVariant = hairline,
            scrim = Color.Black,
            surfaceBright = card, surfaceDim = cardHigh,
            surfaceContainerLowest = card, surfaceContainerLow = surface, surfaceContainer = card,
            surfaceContainerHigh = cardHigh, surfaceContainerHighest = cardHigh
        )
    }
}
