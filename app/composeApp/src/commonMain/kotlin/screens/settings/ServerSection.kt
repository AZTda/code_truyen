package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.InsetGroup
import com.truyencloud.compose.components.InsetIconTint
import com.truyencloud.compose.components.InsetRow
import com.truyencloud.compose.components.NoticeBanner
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.SecondaryButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.api.ServerUrlCheck
import com.truyencloud.shared.update.AppUpdateInstaller
import com.truyencloud.shared.update.UpdatePlatform

/**
 * "Nâng cao → Máy chủ": địa chỉ máy chủ TruyenCloud, kiểm tra kết nối (GET /v1/health), lưu và đặt lại mặc định.
 * Địa chỉ này quyết định MỌI lời gọi mạng, nên có kiểm tra định dạng và cảnh báo khi dùng HTTP.
 */
@Composable
fun ServerSection(
    server: ServerSettingsState,
    engineStatus: String,
    onInputChange: (String) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val validation = server.validation
    val invalid = validation as? ServerUrlCheck.Invalid
    val warning = (validation as? ServerUrlCheck.Valid)?.warning
    val canSave = server.isDirty && validation is ServerUrlCheck.Valid

    InsetGroup(modifier = modifier, header = "Máy chủ") {
        SettingsFormRow(title = "Địa chỉ máy chủ", icon = TruyenCloudIcons.Globe) {
            SettingsTextField(
                label = "",
                value = server.input,
                onValueChange = onInputChange,
                placeholder = "https://truyen.achatvn.com",
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                onImeAction = onTest,
                isError = invalid != null
            )
            Hint(invalid?.reason, colors.error)
            Hint(warning, colors.warning)
            if (validation == null || (invalid == null && warning == null)) {
                Hint(httpHint(), colors.textSecondary)
            }

            Spacer(modifier = Modifier.height(12.dp))
            // Cao bằng nhau kể cả khi nhãn "Kiểm tra kết nối" xuống hai dòng ở cỡ chữ lớn
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            ) {
                SecondaryButton(
                    text = if (server.isTesting) "Đang kiểm tra..." else "Kiểm tra kết nối",
                    onClick = { if (!server.isTesting) onTest() },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                PrimaryButton(
                    text = "Lưu",
                    onClick = onSave,
                    enabled = canSave,
                    modifier = Modifier.weight(0.6f).fillMaxHeight()
                )
            }

            server.testResult?.let { result ->
                Spacer(modifier = Modifier.height(12.dp))
                NoticeBanner(
                    text = result.message,
                    tone = if (result.ok) NoticeTone.Success else NoticeTone.Error
                )
            }
            server.notice?.let { notice ->
                Spacer(modifier = Modifier.height(12.dp))
                NoticeBanner(text = notice, tone = NoticeTone.Success)
            }
        }
        InsetRow(
            title = "Đang dùng",
            subtitle = if (engineStatus.isBlank()) server.savedUrl else "${server.savedUrl} · $engineStatus",
            icon = TruyenCloudIcons.Cloud,
            iconTint = InsetIconTint.Neutral
        )
        if (!server.isDefault) {
            InsetRow(
                title = "Đặt lại mặc định",
                icon = TruyenCloudIcons.Refresh,
                iconTint = InsetIconTint.Neutral,
                onClick = onReset
            )
        }
    }
}

@Composable
private fun Hint(text: String?, color: Color) {
    if (text.isNullOrBlank()) return
    Text(
        text = text,
        style = AppText.secondary,
        color = color,
        modifier = Modifier.padding(top = 6.dp)
    )
}

/** Nhắc nên dùng HTTPS và nói rõ giới hạn HTTP của nền tảng đang chạy. */
private fun httpHint(): String = when (AppUpdateInstaller.platform) {
    UpdatePlatform.ANDROID ->
        "Nên dùng HTTPS. Android chỉ cho phép HTTP tới localhost, 127.0.0.1 và 10.0.2.2 (máy ảo)."
    UpdatePlatform.IOS ->
        "Nên dùng HTTPS. HTTP chỉ dùng được trong mạng nội bộ (địa chỉ LAN, .local)."
}
