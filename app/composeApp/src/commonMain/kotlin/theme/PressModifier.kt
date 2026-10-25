package com.truyencloud.compose.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

/**
 * Chạm kèm lún nhẹ (co về [targetScale] rồi trả lại, không nảy). Không có gợn sóng: dùng cho thẻ, bìa sách, nút
 * tròn. Hàng danh sách và nút có nền thì dùng `clickable` thường để có phản hồi nhấn của Material.
 */
fun Modifier.springPress(
    targetScale: Float = 0.97f,
    role: Role? = null,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) targetScale else 1f,
        animationSpec = AppAnimations.defaultSpring,
        label = "pressScale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            role = role,
            onClick = onClick
        )
}
