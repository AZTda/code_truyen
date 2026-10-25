package com.truyencloud.compose.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

private const val COLLAPSED_LINES = 5

/**
 * Phần giới thiệu truyện: chữ 15sp ngay trên nền trang (không khung), thu gọn còn 5 dòng và chỉ hiện "Xem thêm" khi
 * văn bản thật sự bị cắt. [tags] mặc định rỗng: trước đây trang tự gán năm thẻ thể loại cố định cho MỌI truyện nên đã bỏ;
 * chỉ truyền khi có thể loại thật.
 */
@Composable
fun NovelSynopsisSection(
    description: String,
    tags: List<String> = emptyList(),
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    var isExpanded by remember { mutableStateOf(false) }
    var isCut by remember(description) { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (description.isBlank()) {
            Text(
                text = "Truyện này chưa có phần giới thiệu.",
                style = AppText.body,
                color = colors.textSecondary
            )
        } else {
            Text(
                text = description,
                style = AppText.body,
                color = colors.textPrimary,
                maxLines = if (isExpanded) Int.MAX_VALUE else COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!isExpanded) isCut = it.hasVisualOverflow }
            )
            if (isCut || isExpanded) {
                // Nút chữ có đệm ngang 12dp: dịch sang trái để chữ thẳng hàng với đoạn giới thiệu
                TextActionButton(
                    text = if (isExpanded) "Thu gọn" else "Xem thêm",
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier.offset(x = (-12).dp)
                )
            }
        }

        if (tags.isNotEmpty()) {
            Text(
                text = tags.joinToString(" · "),
                style = AppText.captionRegular,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
