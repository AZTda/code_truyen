package com.truyencloud.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

/** Một ô số liệu: [value] 20sp SemiBold (đã định dạng sẵn, vd. "12,5 giờ") trên [label] 12sp chữ phụ. */
class StatItem(val value: String, val label: String)

/**
 * Hàng số liệu gọn trong MỘT thẻ (2-4 ô chia đều, ngăn bằng đường kẻ dọc): dùng cho "Số liệu đọc" ở Cài đặt và đầu tab Thống kê.
 * Chỉ hiện số thật, không kèm cấp độ / điểm / chuỗi ngày kiểu trò chơi hoá.
 */
@Composable
fun StatRow(items: List<StatItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val colors = LocalCustomColors.current
    FlatCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 14.dp, horizontal = 4.dp)) {
        // Ô hẹp (78dp khi 4 ô trên màn 360dp): giới hạn phóng chữ để số và nhãn không vỡ dòng
        CappedFontScale(max = 1.2f) {
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        Column(Modifier.width(1.dp).fillMaxHeight().background(colors.hairline)) {}
                    }
                    Column(
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics(mergeDescendants = true) {},
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = item.value,
                            style = AppText.screenTitle.copy(fontSize = 20.sp, lineHeight = 26.sp),
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = item.label,
                            style = AppText.captionRegular,
                            color = colors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
