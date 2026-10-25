package com.truyencloud.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.LocalCustomColors

/** Thanh nắm kéo 36x5dp ở đầu sheet. */
@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(36.dp)
            .height(5.dp)
            .clip(CircleShape)
            .background(LocalCustomColors.current.outline.copy(alpha = 0.6f))
    )
}
