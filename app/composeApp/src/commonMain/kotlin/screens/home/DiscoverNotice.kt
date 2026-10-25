package com.truyencloud.compose.screens.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.NoticeBanner
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

/** Dải báo của tab Khám phá khi đang hiện bản lưu / tuyển chọn có sẵn, kèm nút chữ "Thử lại". */
@Composable
fun DiscoverNotice(
    text: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.Info
) {
    NoticeBanner(text = text, modifier = modifier, tone = tone, actionLabel = "Thử lại", onAction = onRetry)
}

/** Dòng giải thích phụ, không có nút: vd. xin "Dành cho bạn" mà tủ sách chưa đủ để gợi ý riêng nên chỉ nhận bảng chung. */
@Composable
fun DiscoverHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = AppText.secondary,
        color = LocalCustomColors.current.textSecondary,
        modifier = modifier.fillMaxWidth()
    )
}
