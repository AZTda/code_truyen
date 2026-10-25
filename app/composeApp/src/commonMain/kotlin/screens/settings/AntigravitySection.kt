package com.truyencloud.compose.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.InsetGroup
import com.truyencloud.compose.components.InsetIconTint
import com.truyencloud.compose.components.InsetRow
import com.truyencloud.compose.components.SegmentedControl
import com.truyencloud.compose.components.icons.TruyenCloudIcons

/** Mức suy luận: (giá trị gửi máy chủ, nhãn). */
private val EFFORT_OPTIONS = listOf("high" to "Cao", "medium" to "Vừa", "low" to "Thấp")

/** Thời gian chờ tối đa cho một chương: (giây, nhãn). */
private val TIMEOUT_OPTIONS = listOf(300 to "5 phút", 600 to "10 phút", 900 to "15 phút")

private const val STORAGE_NOTE =
    "Dữ liệu truyện và chương dịch được lưu tập trung trên máy chủ; chương đã dịch mở ngay mà không cần gọi lại AI."

/**
 * "Nâng cao → Động cơ dịch": trạng thái Antigravity CLI (agy), model đang dùng, mức suy luận và thời gian chờ mỗi chương.
 * Đổi mức suy luận / thời gian chờ áp dụng cho CẢ máy chủ nên chỉ quản trị viên được (403 với người khác).
 */
@Composable
fun AntigravitySection(
    activeModel: String,
    activeEffort: String,
    timeoutSeconds: Int,
    cliVersion: String,
    isAgyAuthenticated: Boolean,
    onSelectEffort: (String) -> Unit,
    onSelectTimeout: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isAdmin: Boolean = true
) {
    // Giá trị máy chủ đang giữ mà chưa có trong danh sách thì thêm vào để ô chọn không tô nhầm lựa chọn khác
    val effortOptions = if (EFFORT_OPTIONS.any { it.first.equals(activeEffort, ignoreCase = true) }) EFFORT_OPTIONS
    else EFFORT_OPTIONS + (activeEffort to activeEffort.replaceFirstChar { it.uppercase() })
    val timeoutOptions = if (TIMEOUT_OPTIONS.any { it.first == timeoutSeconds }) TIMEOUT_OPTIONS
    else (TIMEOUT_OPTIONS + (timeoutSeconds to "${timeoutSeconds / 60} phút")).sortedBy { it.first }

    InsetGroup(
        modifier = modifier,
        header = "Động cơ dịch",
        footer = if (isAdmin) STORAGE_NOTE else "$ADMIN_ONLY_TEXT $STORAGE_NOTE"
    ) {
        InsetRow(
            title = "Antigravity CLI (agy)",
            subtitle = if (isAgyAuthenticated) "Tài khoản liên kết đang hoạt động" else "Chưa xác nhận được tài khoản liên kết",
            value = "v$cliVersion",
            icon = TruyenCloudIcons.Hub,
            iconTint = if (isAgyAuthenticated) InsetIconTint.Success else InsetIconTint.Neutral
        )
        InsetRow(
            title = "Model đang dùng",
            subtitle = activeModel,
            icon = TruyenCloudIcons.Layers,
            iconTint = InsetIconTint.Neutral
        )
        SettingsFormRow(
            title = "Mức độ suy luận",
            icon = TruyenCloudIcons.Target,
            iconTint = InsetIconTint.Neutral,
            enabled = isAdmin
        ) {
            OnCardTrack {
                SegmentedControl(
                    options = effortOptions.map { it.second },
                    selectedIndex = effortOptions.indexOfFirst { it.first.equals(activeEffort, ignoreCase = true) }.coerceAtLeast(0),
                    onSelect = { onSelectEffort(effortOptions[it].first) },
                    enabled = isAdmin
                )
            }
        }
        SettingsFormRow(
            title = "Thời gian chờ tối đa cho 1 chương",
            icon = TruyenCloudIcons.Timer,
            iconTint = InsetIconTint.Neutral,
            enabled = isAdmin
        ) {
            OnCardTrack {
                SegmentedControl(
                    options = timeoutOptions.map { it.second },
                    selectedIndex = timeoutOptions.indexOfFirst { it.first == timeoutSeconds }.coerceAtLeast(0),
                    onSelect = { onSelectTimeout(timeoutOptions[it].first) },
                    enabled = isAdmin
                )
            }
        }
    }
}
