package com.truyencloud.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.api.TruyenCloudApi

/** Logo ứng dụng bo 20% cạnh, nền thẻ phẳng và đường kẻ mảnh. */
@Composable
fun TruyenCloudLogo(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    // Logo nằm trên máy chủ đang dùng (Cài đặt → Nâng cao → Máy chủ), không cố định theo một tên miền
    logoUrl: String = "${TruyenCloudApi.baseUrl.trimEnd('/')}/static/app_logo.png"
) {
    val colors = LocalCustomColors.current
    val logoShape = RoundedCornerShape(size * 0.2f)

    Box(
        modifier = modifier
            .size(size)
            .clip(logoShape)
            .background(colors.card)
            .border(1.dp, colors.hairline, logoShape),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = logoUrl,
            contentDescription = "Logo TruyenCloud",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}
