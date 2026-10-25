package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.truyencloud.compose.components.IosSheet
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

/**
 * Chỉnh sửa hồ sơ (tên hiển thị, châm ngôn, địa chỉ ảnh đại diện). Tờ trượt nâng lên trên bàn phím; ảnh bìa hồ sơ không còn
 * nên không có ô nhập hình nền (ViewModel vẫn giữ nguyên giá trị cũ khi lưu).
 */
@Composable
fun EditProfileSheet(
    isOpen: Boolean,
    displayName: String,
    bio: String,
    avatarUrl: String,
    onDisplayNameChange: (String) -> Unit,
    onBioChange: (String) -> Unit,
    onAvatarUrlChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!isOpen) return
    val fieldColor = LocalCustomColors.current.cardHigh

    IosSheet(onDismissRequest = onDismiss, title = "Chỉnh sửa hồ sơ") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.pageH)
        ) {
            SettingsTextField(
                label = "Tên hiển thị",
                value = displayName,
                onValueChange = onDisplayNameChange,
                containerColor = fieldColor
            )
            Spacer(modifier = Modifier.height(Spacing.md))
            SettingsTextField(
                label = "Châm ngôn",
                value = bio,
                onValueChange = onBioChange,
                containerColor = fieldColor
            )
            Spacer(modifier = Modifier.height(Spacing.md))
            SettingsTextField(
                label = "Địa chỉ ảnh đại diện",
                value = avatarUrl,
                onValueChange = onAvatarUrlChange,
                placeholder = "https://...",
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                onImeAction = onSave,
                containerColor = fieldColor
            )
            Spacer(modifier = Modifier.height(Spacing.xl))
            PrimaryButton(text = "Lưu hồ sơ", onClick = onSave, modifier = Modifier.fillMaxWidth())
            TextActionButton(text = "Hủy", onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}
