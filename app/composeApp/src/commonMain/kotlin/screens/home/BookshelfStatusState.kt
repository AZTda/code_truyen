package com.truyencloud.compose.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.EmptyState
import com.truyencloud.compose.components.NoticeBanner
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.components.NovelCardSkeleton
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

/** Khung xương 3 cột x 2 hàng cùng kích thước với thẻ truyện thật. */
@Composable
fun BookshelfLoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        repeat(2) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                repeat(3) { NovelCardSkeleton() }
            }
        }
    }
}

@Composable
fun BookshelfErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    EmptyState(
        message = "Chưa tải được tủ sách",
        subMessage = message,
        icon = TruyenCloudIcons.CloudOff,
        actionButton = {
            TonalButton(
                text = "Thử lại",
                onClick = onRetry,
                leadingIcon = TruyenCloudIcons.Refresh,
                modifier = Modifier.widthIn(min = 160.dp)
            )
        },
        modifier = modifier
    )
}

/**
 * Dải thông báo ngắn (kết quả nạp tệp, chuyển kệ, xóa...) nổi trên lưới truyện. [tone] chọn biểu tượng và màu; chạm nút đóng
 * (khi có [onDismiss]) để tắt, không thì tự biến mất sau vài giây do ViewModel hẹn.
 */
@Composable
fun FeedbackBanner(
    text: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    tone: NoticeTone = NoticeTone.Info
) {
    val colors = LocalCustomColors.current
    // Dải nổi đè lên lưới truyện: lót nền đặc bên dưới vì màu tô nhạt của NoticeBanner có độ trong suốt
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(InputShape)
            .background(colors.cardHigh)
            .border(1.dp, colors.hairline, InputShape)
    ) {
        NoticeBanner(text = text, tone = tone, onDismiss = onDismiss)
    }
}
