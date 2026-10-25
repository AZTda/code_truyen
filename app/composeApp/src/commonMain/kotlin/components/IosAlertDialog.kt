package com.truyencloud.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.DialogShape
import com.truyencloud.compose.theme.LocalCustomColors

/** Vai trò của nút trong hộp thoại: quyết định màu / độ đậm chữ. */
enum class IosAlertRole {
    /** Hành động chính: màu nhấn, SemiBold. */
    Primary,

    /** Hành động thường và Hủy: màu nhấn, thường. */
    Default,

    /** Nguy hiểm không hoàn tác (Xóa, Đăng xuất): màu lỗi. */
    Destructive
}

@Immutable
class IosAlertAction(
    val label: String,
    val role: IosAlertRole = IosAlertRole.Default,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * Hộp thoại xác nhận kiểu iOS: căn giữa, bo 18, tiêu đề 17sp SemiBold giữa, nội dung 13sp phụ, các nút xếp dọc
 * toàn chiều rộng ngăn bằng đường kẻ. Dùng cho Đăng xuất, Xóa, Xóa dữ liệu, Có bản cập nhật.
 *
 * [actions] theo thứ tự hiển thị từ trên xuống; đặt nút Hủy ở cuối. [content] là phần phụ giữa nội dung và nút
 * (vd. một hàng checkbox) — phần chữ + [content] tự cuộn khi cỡ chữ lớn hoặc màn thấp.
 */
@Composable
fun IosAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    actions: List<IosAlertAction>,
    modifier: Modifier = Modifier,
    message: String? = null,
    dismissOnOutsideTap: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = dismissOnOutsideTap,
            usePlatformDefaultWidth = false
        )
    ) {
        IosAlertCard(title, actions, modifier, message, content)
    }
}

/** Thân hộp thoại (đệm quanh + thẻ + nút); tách riêng để xem trước mà không cần cửa sổ Dialog. */
@Composable
internal fun IosAlertCard(
    title: String,
    actions: List<IosAlertAction>,
    modifier: Modifier = Modifier,
    message: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    val colors = LocalCustomColors.current
    // Đệm quanh hộp thoại: giữ khoảng cách với mép màn hình và chừa chỗ cho thanh hệ thống
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp)) {
        Column(
            modifier = modifier
                .align(Alignment.Center)
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .clip(DialogShape)
                .background(colors.cardHigh)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = AppText.sectionHeader,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() }
                )
                if (message != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = message,
                        style = AppText.secondary,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
                if (content != null) {
                    Spacer(Modifier.height(12.dp))
                    content()
                }
            }
            actions.forEach { action ->
                HairlineDivider()
                AlertActionRow(action)
            }
        }
    }
}

@Composable
private fun AlertActionRow(action: IosAlertAction) {
    val colors = LocalCustomColors.current
    val color = when {
        !action.enabled -> colors.textTertiary
        action.role == IosAlertRole.Destructive -> colors.error
        else -> colors.accent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = action.label,
            style = AppText.sectionHeader.copy(
                fontWeight = if (action.role == IosAlertRole.Primary) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Hộp thoại hai lựa chọn thường gặp: nút xác nhận phía trên, Hủy phía dưới. [destructive] đổi nút xác nhận sang màu lỗi. */
@Composable
fun IosConfirmDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    dismissLabel: String = "Hủy",
    destructive: Boolean = false
) {
    IosAlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        message = message,
        modifier = modifier,
        actions = listOf(
            IosAlertAction(confirmLabel, if (destructive) IosAlertRole.Destructive else IosAlertRole.Primary, onClick = onConfirm),
            IosAlertAction(dismissLabel, IosAlertRole.Default, onClick = onDismiss)
        )
    )
}
