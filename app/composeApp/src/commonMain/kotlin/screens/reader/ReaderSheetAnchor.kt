package com.truyencloud.compose.screens.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.truyencloud.compose.theme.AppAnimations

/**
 * Đặt một bảng ở đáy màn hình bên trong [ReaderSheetBackdrop] và chặn cú bấm lọt xuống lớp nền (bấm vào chính bảng không được đóng
 * bảng). Mọi bảng AI / dấu trang / thuật ngữ / mục lục dùng chung chỗ này thay vì mỗi nơi chép lại một khối Box. Bảng trượt lên từ đáy
 * 250ms khi hiện (không nảy); đóng thì gỡ khỏi cây giao diện ngay như trước.
 */
@Composable
internal fun BoxScope.BottomSheetAnchor(content: @Composable () -> Unit) {
    // Bắt đầu ở trạng thái ẩn rồi chuyển sang hiện ngay khi vào cây giao diện: chạy hiệu ứng vào đúng một lần
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = visibleState,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(tween(AppAnimations.MEDIUM_MS, easing = LinearOutSlowInEasing)) { it },
        exit = ExitTransition.None
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
        ) {
            content()
        }
    }
}
