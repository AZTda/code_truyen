package com.truyencloud.compose.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import com.truyencloud.compose.theme.AppAnimations

/**
 * Chạm thường + nhấn giữ, có hiệu ứng lún nhẹ. Nhấn giữ rung nhẹ rồi gọi [onLongClick]; null thì chỉ
 * là chạm thường (giống `springPress`).
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressAndHold(
    targetScale: Float = 0.97f,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) targetScale else 1f,
        animationSpec = AppAnimations.defaultSpring,
        label = "pressAndHoldScale"
    )
    val haptic = LocalHapticFeedback.current
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            role = Role.Button,
            onLongClickLabel = onLongClickLabel,
            onLongClick = onLongClick?.let { action ->
                {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    action()
                }
            },
            onClick = onClick
        )
}
