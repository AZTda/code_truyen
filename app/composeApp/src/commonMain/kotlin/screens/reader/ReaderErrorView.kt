package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.EmptyState
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons

/** Không tải được chương: trạng thái trống ở giữa màn hình, nút chính "Thử lại" và nút phụ "Mục lục". */
@Composable
fun ReaderErrorView(
    errorMessage: String?,
    onOpenToc: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            message = "Không thể tải chương",
            subMessage = errorMessage ?: "Vui lòng kiểm tra lại kết nối mạng hoặc thử lại.",
            icon = TruyenCloudIcons.Warning,
            actionButton = {
                Column(
                    modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PrimaryButton("Thử lại", onRetry, Modifier.fillMaxWidth(), leadingIcon = TruyenCloudIcons.Refresh)
                    TonalButton("Mục lục", onOpenToc, Modifier.fillMaxWidth())
                }
            }
        )
    }
}
