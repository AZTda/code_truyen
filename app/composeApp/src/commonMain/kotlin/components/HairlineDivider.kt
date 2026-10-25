package com.truyencloud.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.LocalCustomColors

/** Đường kẻ mảnh 1dp ngăn hàng / vùng. [startInset] chừa lề trái (60dp để thẳng hàng với chữ sau icon). */
@Composable
fun HairlineDivider(
    modifier: Modifier = Modifier,
    startInset: Dp = 0.dp,
    color: Color = LocalCustomColors.current.hairline
) {
    Box(modifier.fillMaxWidth().padding(start = startInset).height(1.dp).background(color))
}
