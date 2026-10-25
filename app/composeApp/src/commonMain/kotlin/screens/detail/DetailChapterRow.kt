package com.truyencloud.compose.screens.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.HairlineDivider
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.ChapterFlags

/**
 * Một dòng trong mục lục (cao >= 48dp, số chương 13sp, tiêu đề 15sp đậm hơn khi chưa đọc, đường kẻ mảnh phía dưới) kèm hai
 * nhãn chữ về nguồn bản dịch. Trạng thái lấy từ [ChapterFlags] (không đọc nội dung từ danh sách nhẹ):
 * - sẵn sàng offline: biểu tượng mây xanh; bản dịch tạm của Google: mây + nhãn "Bản tạm".
 * - chỉ có văn bản gốc: nhãn "Chỉ bản gốc" — mất mạng sẽ hiện nguyên tiếng Trung.
 * - đã đọc: dấu tích (trạng thái không chỉ dựa vào màu chữ).
 */
@Composable
fun DetailChapterRow(
    chapterNumber: Int,
    title: String,
    isRead: Boolean,
    flags: ChapterFlags?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isServerTranslated: Boolean = false
) {
    val colors = LocalCustomColors.current

    Column(modifier = modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = Spacing.pageH, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = chapterNumber.toString(),
                style = AppText.secondaryStrong,
                color = if (isRead) colors.textTertiary else colors.textSecondary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(44.dp)
            )

            Text(
                text = title,
                style = AppText.body.copy(fontWeight = if (isRead) FontWeight.Normal else FontWeight.Medium),
                color = if (isRead) colors.textSecondary else colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            when {
                flags?.isOfflineReady == true && flags.isProvisional -> {
                    Text(text = "Bản tạm", style = AppText.caption, color = colors.warning, maxLines = 1)
                    Icon(
                        imageVector = TruyenCloudIcons.CloudDone,
                        contentDescription = "Bản dịch tạm, đã có trên máy",
                        tint = colors.warning,
                        modifier = Modifier.size(18.dp)
                    )
                }
                flags?.isOfflineReady == true -> Icon(
                    imageVector = TruyenCloudIcons.CloudDone,
                    contentDescription = "Đã tải offline",
                    tint = colors.success,
                    modifier = Modifier.size(18.dp)
                )
                isServerTranslated -> {
                    Text(text = "AI đã dịch", style = AppText.caption, color = colors.accent, maxLines = 1)
                    Icon(
                        imageVector = TruyenCloudIcons.Zap,
                        contentDescription = "Máy chủ đã dịch sẵn",
                        tint = colors.accent,
                        modifier = Modifier.size(16.dp)
                    )
                }
                flags?.isOriginalOnly == true -> Text(
                    text = "Chỉ bản gốc",
                    style = AppText.captionRegular,
                    color = colors.textTertiary,
                    maxLines = 1
                )
            }

            if (isRead) {
                Icon(
                    imageVector = TruyenCloudIcons.Check,
                    contentDescription = "Đã đọc",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        HairlineDivider(startInset = Spacing.pageH)
    }
}
