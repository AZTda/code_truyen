package com.truyencloud.compose.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.InsetIconTile
import com.truyencloud.compose.components.InsetIconTint
import com.truyencloud.compose.components.IosSheet
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

/**
 * Hướng dẫn chào mừng ở lần chạy đầu (AppNav mở qua HomeViewModel.openWelcomeGuide). Là tờ trượt từ dưới lên kiểu iOS phủ
 * lên màn hình — bản cũ chỉ là một cột đặt dưới thanh điều hướng nên làm co cả nội dung Home lại.
 */
@Composable
fun WelcomeGuideSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    IosSheet(onDismissRequest = onDismiss) {
        WelcomeGuideContent(onStart = onDismiss, modifier = modifier.weight(1f, fill = false))
    }
}

/** Nội dung tờ chào mừng: tiêu đề, ba tính năng chính và nút "Bắt đầu". Tự cuộn khi màn thấp hoặc chữ hệ thống lớn. */
@Composable
internal fun WelcomeGuideContent(onStart: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalCustomColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.pageH)
            .padding(bottom = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Chào mừng đến với TruyenCloud",
            style = AppText.screenTitle,
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = "Đọc truyện chữ và học ngoại ngữ trong cùng một ứng dụng.",
            style = AppText.body,
            color = colors.textSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(Spacing.xl))
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            GuideFeatureRow(
                icon = TruyenCloudIcons.MenuBook,
                title = "Đọc truyện song ngữ",
                description = "Lật trang hoặc cuộn liên tục. Chạm vào từ tiếng Anh để tra nghĩa, xen từ vựng tiếng Anh vào bản dịch và tải trước chương để đọc offline."
            )
            GuideFeatureRow(
                icon = TruyenCloudIcons.Headphones,
                title = "Nghe truyện và âm thanh nền",
                description = "Nhiều giọng đọc AI, tô sáng câu đang đọc và hòa âm thanh nền thư giãn như mưa rơi, lò sưởi, sóng biển."
            )
            GuideFeatureRow(
                icon = TruyenCloudIcons.Chat,
                title = "Trò chuyện và ôn từ vựng",
                description = "Trò chuyện cùng nhân vật trong truyện, làm bài tập tình huống và ôn từ đã lưu bằng thẻ ghi nhớ giãn cách."
            )
        }

        Spacer(modifier = Modifier.height(Spacing.xl))
        PrimaryButton(text = "Bắt đầu", onClick = onStart, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun GuideFeatureRow(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        InsetIconTile(icon = icon, tint = InsetIconTint.Accent)
        Spacer(modifier = Modifier.width(Spacing.lg))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = AppText.bodyStrong, color = colors.textPrimary)
            Text(text = description, style = AppText.secondary, color = colors.textSecondary, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
