package com.truyencloud.compose.screens.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.InsetGroup
import com.truyencloud.compose.components.InsetSwitchRow

/** Hai công tắc hành vi đọc: giữ màn hình sáng (mặc định bật) và lật trang bằng phím âm lượng (mặc định tắt). */
@Composable
internal fun ReaderBehaviorSection(
    keepScreenOn: Boolean,
    volumeKeyPaging: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onVolumeKeyPagingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    InsetGroup(modifier = modifier, header = "Khi đọc") {
        InsetSwitchRow(
            title = "Giữ màn hình sáng khi đọc",
            subtitle = "Màn hình không tự tắt lúc đang đọc truyện.",
            checked = keepScreenOn,
            onCheckedChange = onKeepScreenOnChange
        )
        InsetSwitchRow(
            title = "Lật trang bằng phím âm lượng",
            subtitle = "Phím giảm âm lượng là trang sau / cuộn xuống, phím tăng là trang trước / cuộn lên (chỉ Android).",
            checked = volumeKeyPaging,
            onCheckedChange = onVolumeKeyPagingChange
        )
    }
}
