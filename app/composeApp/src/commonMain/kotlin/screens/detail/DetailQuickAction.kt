package com.truyencloud.compose.screens.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.springPress

/**
 * Hành động phụ của trang truyện: biểu tượng 24dp trên một nhãn 12sp, không nền, chia đều chiều ngang hàng (vùng chạm cao
 * tối thiểu 64dp). [active] = đang bật (trong tủ, đã yêu thích): biểu tượng và nhãn đổi sang màu nhấn. [state] là mô tả
 * trạng thái cho trình đọc màn hình ("Kệ Đang đọc").
 */
@Composable
internal fun RowScope.DetailQuickAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    state: String? = null
) {
    val colors = LocalCustomColors.current
    val tint = if (active) colors.accent else colors.textSecondary
    Column(
        modifier = modifier
            .weight(1f)
            .heightIn(min = 64.dp)
            .springPress(role = Role.Button, onClick = onClick)
            .semantics { if (state != null) stateDescription = state }
            .padding(horizontal = 2.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = AppText.caption,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
