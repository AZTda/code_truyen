package com.truyencloud.compose.screens.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.truyencloud.compose.components.CappedFontScale
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.SmallTitleTopBar
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppAnimations
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Thanh trên của màn đọc: nền ĐẶC màu surface (kéo dài xuống sau thanh trạng thái) + đường kẻ mảnh ở đáy, nên chữ phía sau không
 * lộ qua. Chevron quay lại, tên chương (serif), rồi dấu trang, mục lục và menu "Thêm" (tìm trong chương, đổi chế độ lật trang /
 * cuộn dọc, đổi ngôn ngữ đọc) — gom các thao tác ít dùng vào menu để mọi nút đạt vùng chạm 48dp trên màn 360dp.
 */
@Composable
fun ReaderTopBar(
    title: String,
    visible: Boolean,
    isPaginatedMode: Boolean = false,
    isEnglishMode: Boolean = false,
    isBilingualMode: Boolean = false,
    isProgressiveMode: Boolean = false,
    englishLevel: String = "c1",
    onCycleLanguageMode: () -> Unit = {},
    onBack: () -> Unit,
    onOpenToc: () -> Unit = {},
    onOpenBookmarks: () -> Unit = {},
    onOpenFind: () -> Unit = {},
    bookmarkCount: Int = 0,
    onTogglePaginatedMode: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val enterTween = tween<Float>(AppAnimations.SHORT_MS, easing = LinearOutSlowInEasing)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(enterTween) + slideInVertically(tween(AppAnimations.SHORT_MS, easing = LinearOutSlowInEasing)) { -it / 2 },
        exit = fadeOut(enterTween) + slideOutVertically(tween(AppAnimations.SHORT_MS, easing = LinearOutSlowInEasing)) { -it / 2 },
        modifier = modifier
    ) {
        // Thanh trên là chrome cố định 52dp: giới hạn phóng chữ để tên chương không bị cắt còn vài chữ ở máy hẹp
        CappedFontScale(max = 1.15f) {
            SmallTitleTopBar(
                title = title,
                onBack = onBack,
                // Chặn cú chạm lọt xuống chữ phía sau (thanh đặc phải "chắn" cả chạm lẫn nhìn)
                modifier = Modifier.pointerInput(Unit) { detectTapGestures { } },
                containerColor = colors.surface,
                serifTitle = true,
                actions = {
                    QuietIconButton(
                        icon = if (bookmarkCount > 0) TruyenCloudIcons.BookmarkFilled else TruyenCloudIcons.BookmarkBorder,
                        contentDescription = if (bookmarkCount > 0) "Dấu trang và ghi chú ($bookmarkCount)" else "Dấu trang và ghi chú",
                        onClick = onOpenBookmarks,
                        tint = if (bookmarkCount > 0) colors.accent else colors.textSecondary
                    )
                    QuietIconButton(
                        icon = TruyenCloudIcons.List,
                        contentDescription = "Mục lục chương",
                        onClick = onOpenToc
                    )
                    ReaderTopBarMenu(
                        isPaginatedMode = isPaginatedMode,
                        isEnglishMode = isEnglishMode,
                        isBilingualMode = isBilingualMode,
                        isProgressiveMode = isProgressiveMode,
                        englishLevel = englishLevel,
                        onOpenFind = onOpenFind,
                        onTogglePaginatedMode = onTogglePaginatedMode,
                        onCycleLanguageMode = onCycleLanguageMode
                    )
                }
            )
        }
    }
}
