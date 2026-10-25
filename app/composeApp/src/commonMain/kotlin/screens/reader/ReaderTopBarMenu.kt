package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Hành động của vòng đổi ngôn ngữ đọc kế tiếp (khớp thứ tự của `cycleLanguageMode`: Việt -> Anh -> song ngữ -> hoà trộn -> Việt).
 * Ghi rõ việc sẽ xảy ra thay vì tên chế độ hiện tại để người đọc biết bấm vào sẽ được gì.
 */
internal fun readerNextLanguageActionLabel(
    isEnglishMode: Boolean,
    isBilingualMode: Boolean,
    isProgressiveMode: Boolean,
    englishLevel: String
): String = when {
    !isProgressiveMode && !isBilingualMode && !isEnglishMode -> "Đọc bản tiếng Anh (${englishLevel.uppercase()})"
    isEnglishMode -> "Đọc song ngữ Anh - Việt"
    isBilingualMode -> "Đọc hòa trộn Việt - Anh"
    else -> "Quay lại đọc tiếng Việt"
}

/** Menu "Thêm" của thanh trên: tìm trong chương, đổi chế độ lật trang / cuộn dọc, đổi ngôn ngữ đọc. */
@Composable
internal fun ReaderTopBarMenu(
    isPaginatedMode: Boolean,
    isEnglishMode: Boolean,
    isBilingualMode: Boolean,
    isProgressiveMode: Boolean,
    englishLevel: String,
    onOpenFind: () -> Unit,
    onTogglePaginatedMode: () -> Unit,
    onCycleLanguageMode: () -> Unit,
    initiallyOpen: Boolean = false
) {
    val colors = LocalCustomColors.current
    var open by remember { mutableStateOf(initiallyOpen) }

    @Composable
    fun Item(label: String, icon: ImageVector, onClick: () -> Unit) {
        DropdownMenuItem(
            text = { Text(text = label, style = AppText.body, color = colors.textPrimary) },
            leadingIcon = { Icon(icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp)) },
            onClick = {
                open = false
                onClick()
            }
        )
    }

    Box {
        QuietIconButton(
            icon = TruyenCloudIcons.MoreVertical,
            contentDescription = "Thêm tùy chọn đọc",
            onClick = { open = true }
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = colors.cardHigh,
            tonalElevation = 0.dp
        ) {
            Item("Tìm trong chương", TruyenCloudIcons.Search, onOpenFind)
            Item(
                label = if (isPaginatedMode) "Chuyển sang cuộn dọc" else "Chuyển sang lật trang",
                icon = if (isPaginatedMode) TruyenCloudIcons.PageModeScroll else TruyenCloudIcons.PageModeCurl,
                onClick = onTogglePaginatedMode
            )
            Item(
                label = readerNextLanguageActionLabel(isEnglishMode, isBilingualMode, isProgressiveMode, englishLevel),
                icon = TruyenCloudIcons.Translate,
                onClick = onCycleLanguageMode
            )
        }
    }
}
