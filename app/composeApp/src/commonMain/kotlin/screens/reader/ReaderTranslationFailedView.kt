package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.EmptyState
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.reader.TranslationFailureKind

/**
 * Thẻ giữa màn hình khi chương CHƯA có bản dịch và việc dịch thất bại / bị huỷ: nói rõ lý do và để người đọc tự chọn
 * thử lại hay đọc văn bản gốc — không bao giờ tự rơi về tiếng Trung. Nút chính là việc làm được ngay: "Thử lại", hoặc
 * "Đọc bản gốc" khi khách chưa đăng nhập (dịch AI cần tài khoản).
 */
@Composable
internal fun ReaderTranslationFailedView(
    message: String,
    kind: TranslationFailureKind?,
    canReadOriginal: Boolean,
    onRetry: () -> Unit,
    onReadOriginal: () -> Unit,
    onOpenToc: () -> Unit,
    modifier: Modifier = Modifier
) {
    val loginRequired = kind.isGuestBlocked()
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            message = "Chương này chưa có bản dịch",
            subMessage = message,
            icon = TruyenCloudIcons.Warning,
            actionButton = {
                Column(
                    modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!loginRequired) {
                        PrimaryButton("Thử lại", onRetry, Modifier.fillMaxWidth(), leadingIcon = TruyenCloudIcons.Refresh)
                    }
                    if (canReadOriginal) {
                        if (loginRequired) {
                            PrimaryButton("Đọc bản gốc", onReadOriginal, Modifier.fillMaxWidth())
                        } else {
                            TonalButton("Đọc bản gốc", onReadOriginal, Modifier.fillMaxWidth())
                        }
                    }
                    TextActionButton("Mục lục", onOpenToc, Modifier.fillMaxWidth())
                }
            }
        )
    }
}

/** Màn chờ khi mở chương lần đầu (chưa có chữ nào): vòng quay + "Đang tải chương N…". */
@Composable
internal fun ReaderChapterSpinner(target: Int?, modifier: Modifier = Modifier) {
    val colors = LocalCustomColors.current
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = colors.accent, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (target != null) "Đang tải chương $target…" else "Đang tải chương…",
                style = AppText.secondary,
                color = colors.textSecondary
            )
        }
    }
}
