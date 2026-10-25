package com.truyencloud.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.Spacing

/** Khung xương của [NovelCard] (cùng kích thước bìa 96x136) khi đang chờ dữ liệu. */
@Composable
fun NovelCardSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.width(Spacing.coverWidth)) {
        ShimmerBox(width = Spacing.coverWidth, height = Spacing.coverHeight)
        Spacer(modifier = Modifier.height(10.dp))
        ShimmerBox(width = Spacing.coverWidth, height = 14.dp)
        Spacer(modifier = Modifier.height(6.dp))
        ShimmerBox(width = 56.dp, height = 12.dp)
    }
}
