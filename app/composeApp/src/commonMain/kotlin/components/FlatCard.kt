package com.truyencloud.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.CardShape
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Thẻ phẳng: màu thẻ, bo 16, không bóng, không viền màu. [outlined] thêm đường kẻ mảnh khi bước màu so với nền quá nhỏ
 * (vd. thẻ lồng trong thẻ). [onClick] biến cả thẻ thành vùng bấm.
 */
@Composable
fun FlatCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    containerColor: Color = Color.Unspecified,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    outlined: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = LocalCustomColors.current
    Box(
        modifier = modifier
            .clip(shape)
            .background(containerColor.takeOrElse { colors.card })
            .then(if (outlined) Modifier.border(1.dp, colors.hairline, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content
    )
}
