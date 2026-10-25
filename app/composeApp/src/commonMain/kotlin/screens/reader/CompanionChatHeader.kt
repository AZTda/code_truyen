package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FilterChip8

/** Các vai trò trò chuyện. Giá trị là định danh gửi cho máy chủ và so khớp với `DEFAULT_COMPANION_NAME`: KHÔNG đổi chữ ở đây. */
private val CompanionPersonas = listOf("Trợ Lý Bách Khoa", "Nhân vật chính", "Sư phụ / Trưởng bối", "Kẻ quan sát")

/** Nhãn hiển thị (viết hoa chữ đầu câu) của một vai trò; giá trị gốc vẫn là định danh. */
private fun personaLabel(role: String): String = if (role == "Trợ Lý Bách Khoa") "Trợ lý bách khoa" else role

/** Chọn vai trò trò chuyện: trợ lý đọc sách hoặc nhập vai một nhân vật. Hàng chip cuộn ngang, mỗi chip có vùng chạm 48dp. */
@Composable
internal fun CompanionPersonaRow(selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = ReaderSheetInset),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompanionPersonas.forEach { role ->
            FilterChip8(label = personaLabel(role), selected = role == selected, onClick = { onSelect(role) })
        }
    }
}
