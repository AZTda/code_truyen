package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.ProgressBar3
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.ChipShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.api.AppVersionResponse

/** So sánh phiên bản đang chạy với bản mới, dung lượng gói và nội dung thay đổi. */
@Composable
internal fun UpdateVersionAndChangelog(
    updateInfo: AppVersionResponse,
    currentVersionName: String,
    currentVersionCode: Int
) {
    val colors = LocalCustomColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Bản hiện tại trên máy", style = AppText.captionRegular, color = colors.textSecondary)
                Text(text = "v$currentVersionName ($currentVersionCode)", style = AppText.secondaryStrong, color = colors.textPrimary)
            }
            if (updateInfo.formattedFileSize.isNotBlank()) {
                Text(
                    text = updateInfo.formattedFileSize,
                    style = AppText.caption,
                    color = colors.success,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(ChipShape)
                        .background(colors.successSoft)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(text = "Những điểm mới", style = AppText.secondaryStrong, color = colors.textPrimary)
        Spacer(modifier = Modifier.height(6.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .clip(ChipShape)
                .background(colors.card)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text(
                text = updateInfo.changelog.ifBlank { "- Cập nhật hiệu năng và sửa lỗi hệ thống." },
                style = AppText.secondary,
                color = colors.textPrimary
            )
        }
    }
}

/** Thanh tiến độ tải gói cập nhật. */
@Composable
internal fun UpdateDownloadProgress(downloadProgress: Float) {
    val colors = LocalCustomColors.current
    val percent = (downloadProgress.coerceIn(0f, 1f) * 100).toInt()
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "Đang tải gói cập nhật...",
                style = AppText.secondary,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f)
            )
            Text(text = "$percent%", style = AppText.secondaryStrong, color = colors.accent)
        }
        Spacer(modifier = Modifier.height(8.dp))
        ProgressBar3(progress = downloadProgress, height = 4.dp, minVisible = 0.02f)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Sau khi tải xong, màn hình cài đặt Android sẽ tự động hiện lên.",
            style = AppText.captionRegular,
            color = colors.textSecondary
        )
    }
}
