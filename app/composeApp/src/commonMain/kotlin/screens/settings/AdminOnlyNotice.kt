package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

/** Câu giải thích mặc định khi một nhóm điều khiển bị khoá vì không phải quản trị viên. */
internal const val ADMIN_ONLY_TEXT =
    "Chỉ quản trị viên mới đổi được mục này — cấu hình dùng chung cho mọi người dùng trên máy chủ."

/**
 * Giải thích ngắn vì sao một nhóm điều khiển bị khoá: cấu hình dịch là DÙNG CHUNG cho mọi người dùng trên máy chủ,
 * nên máy chủ chỉ cho quản trị viên ghi (403 với người dùng thường). Dùng trong tờ trượt; trong nhóm danh sách thì
 * truyền cùng câu vào `footer` của InsetGroup.
 */
@Composable
fun AdminOnlyNotice(modifier: Modifier = Modifier, text: String = ADMIN_ONLY_TEXT) {
    val colors = LocalCustomColors.current
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = TruyenCloudIcons.Lock,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.padding(top = 2.dp).size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, style = AppText.secondary, color = colors.textSecondary)
    }
}
