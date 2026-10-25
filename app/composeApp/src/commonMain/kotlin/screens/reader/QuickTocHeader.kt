package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Hai hàng công cụ phía trên danh sách mục lục: (1) tìm theo tên chương + đổi thứ tự cũ nhất / mới nhất, (2) nhảy tới số chương
 * + nút "Đi" (bàn phím số, phím Đi cũng nhảy) và dòng báo lỗi nhập sai. Mọi trạng thái nằm ở [QuickTocSheet].
 */
@Composable
internal fun QuickTocToolbar(
    query: String,
    onQueryChange: (String) -> Unit,
    descending: Boolean,
    onToggleOrder: () -> Unit,
    jumpText: String,
    onJumpTextChange: (String) -> Unit,
    jumpPlaceholder: String,
    jumpError: String?,
    onSubmitJump: () -> Unit,
    onlyTranslated: Boolean = false,
    translatedCount: Int = 0,
    onToggleTranslated: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = ReaderSheetInset),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ReaderInputField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "Tìm theo tên chương",
                modifier = Modifier.weight(1f),
                leadingIcon = TruyenCloudIcons.Search,
                clearable = true
            )
            if (translatedCount > 0 || onlyTranslated) {
                FilterChip8(
                    label = "Đã dịch AI",
                    selected = onlyTranslated,
                    onClick = onToggleTranslated,
                    leadingIcon = TruyenCloudIcons.Zap,
                    trailingText = translatedCount.takeIf { it > 0 }?.toString()
                )
            }
            FilterChip8(
                label = if (descending) "Mới nhất" else "Cũ nhất",
                selected = false,
                onClick = onToggleOrder,
                leadingIcon = TruyenCloudIcons.Sort
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ReaderInputField(
                value = jumpText,
                onValueChange = onJumpTextChange,
                placeholder = jumpPlaceholder,
                modifier = Modifier.weight(1f),
                isError = jumpError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSubmitJump() })
            )
            TonalButton(text = "Đi", onClick = onSubmitJump)
        }
        if (jumpError != null) {
            Text(text = jumpError, style = AppText.captionRegular, color = colors.error)
        }
    }
}
