package com.truyencloud.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

/**
 * Trạng thái trống: một biểu tượng viền 48dp màu chữ thứ ba, tiêu đề 17sp SemiBold, một câu 13sp phụ, nút bên dưới.
 * [actionButton] là khe cho nút: một [PrimaryButton] (+ [TonalButton] thứ hai) xếp trong Column.
 */
@Composable
fun EmptyState(
    message: String,
    subMessage: String = "",
    icon: ImageVector = TruyenCloudIcons.Bookshelf,
    actionButton: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(48.dp)
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        Text(
            text = message,
            style = AppText.sectionHeader,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }
        )

        if (subMessage.isNotBlank()) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = subMessage,
                style = AppText.secondary,
                color = colors.textSecondary,
                textAlign = TextAlign.Center
            )
        }

        if (actionButton != null) {
            Spacer(modifier = Modifier.height(Spacing.lg))
            actionButton()
        }
    }
}
