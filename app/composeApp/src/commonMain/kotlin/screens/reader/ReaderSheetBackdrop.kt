package com.truyencloud.compose.screens.reader

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import com.truyencloud.compose.navigation.PlatformBackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.truyencloud.compose.theme.AppAnimations
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Lớp mờ phía sau tờ trượt; bấm ra ngoài để đóng. Màu lấy từ token scrim của chủ đề (đen 60% nền tối / 40% nền sáng) và hiện dần
 * 200ms; [dim] < 1 làm nhạt lớp mờ khi người đọc cần thấy chữ phía sau (vd. thanh tìm trong chương).
 */
@Composable
fun ReaderSheetBackdrop(
    onDismiss: () -> Unit,
    dim: Float = 1f,
    content: @Composable BoxScope.() -> Unit
) {
    // Nút Back của hệ thống đóng tờ trượt trước, không thoát luôn màn đọc (bộ xử lý của AppNav nằm ở cây cha nên ưu tiên thấp hơn)
    PlatformBackHandler(onBack = onDismiss)
    val scrim = LocalCustomColors.current.scrim
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val fade by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(AppAnimations.SHORT_MS, easing = LinearOutSlowInEasing),
        label = "sheetScrim"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrim.copy(alpha = (scrim.alpha * dim * fade).coerceIn(0f, 1f)))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        content = content
    )
}
