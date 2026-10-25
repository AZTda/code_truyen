package com.truyencloud.compose.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

// Các phần phụ của lớp tìm kiếm: thẻ nhận diện liên kết, đang tìm và lỗi (gợi ý nằm ở SearchSuggestionsView).

/** Dán liên kết truyện vào ô tìm kiếm thì hiện thẻ này: chạm để tải thông tin tác phẩm từ liên kết. */
@Composable
internal fun UrlActionCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalCustomColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.pageH, vertical = Spacing.xs)
            .clip(InputShape)
            .background(colors.accentSoft)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(TruyenCloudIcons.Globe, contentDescription = null, tint = colors.onAccentSoft, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Đã nhận ra liên kết truyện", style = AppText.bodyStrong, color = colors.textPrimary)
            Text(
                text = "Chạm để tải thông tin tác phẩm",
                style = AppText.secondary,
                color = colors.textSecondary
            )
        }
        Icon(TruyenCloudIcons.ChevronRight, contentDescription = null, tint = colors.onAccentSoft, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun SearchingIndicator(isUrl: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalCustomColors.current
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = colors.accent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(
                text = if (isUrl) "Đang lấy thông tin từ liên kết…" else "Đang tìm trên kho và web…",
                style = AppText.secondary,
                color = colors.textSecondary
            )
        }
    }
}

@Composable
internal fun SearchErrorView(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalCustomColors.current
    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(TruyenCloudIcons.Warning, contentDescription = null, tint = colors.error, modifier = Modifier.size(32.dp))
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(text = message, style = AppText.body, color = colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(Spacing.lg))
        TonalButton(text = "Thử lại", onClick = onRetry, leadingIcon = TruyenCloudIcons.Refresh)
    }
}
