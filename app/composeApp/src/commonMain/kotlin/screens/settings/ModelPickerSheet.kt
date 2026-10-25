package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.InsetIconTint
import com.truyencloud.compose.components.InsetRow
import com.truyencloud.compose.components.IosSheet
import com.truyencloud.compose.components.SearchField
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.api.ModelItem

/**
 * Chọn model dịch (Antigravity CLI). Tờ trượt có ô tìm và danh sách; dòng đang dùng có dấu tích màu nhấn.
 * Người dùng thường chỉ xem danh mục — chọn model là cấu hình dùng chung nên chỉ quản trị viên được đổi.
 */
@Composable
fun ModelPickerSheet(
    isOpen: Boolean,
    models: List<ModelItem>,
    activeModelId: String,
    onSelectModel: (String) -> Unit,
    onDismiss: () -> Unit,
    canSelect: Boolean = true
) {
    if (!isOpen) return

    val colors = LocalCustomColors.current
    var searchQuery by remember { mutableStateOf("") }
    val filteredModels = remember(models, searchQuery) {
        if (searchQuery.isBlank()) models
        else models.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.id.contains(searchQuery, ignoreCase = true) ||
                it.provider.contains(searchQuery, ignoreCase = true)
        }
    }

    IosSheet(onDismissRequest = onDismiss, title = "Chọn model dịch") {
        Column(modifier = Modifier.fillMaxWidth().imePadding()) {
            Text(
                text = "Chạy qua Antigravity CLI, không phát sinh phí API",
                style = AppText.secondary,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            )
            if (!canSelect) {
                AdminOnlyNotice(text = "Bạn chỉ xem được danh sách. Model dịch là cấu hình dùng chung nên chỉ quản trị viên được đổi.")
            }
            OnCardField {
                SearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "Tìm model (Gemini, Claude, Flash...)",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(filteredModels) { model ->
                    val isSelected = model.id == activeModelId
                    InsetRow(
                        title = model.name,
                        subtitle = "${model.provider} · ${model.id} · Suy luận: ${model.reasoning}",
                        iconTint = InsetIconTint.Neutral,
                        onClick = if (canSelect) ({ onSelectModel(model.id) }) else null,
                        modifier = Modifier.semantics { selected = isSelected },
                        trailing = {
                            if (isSelected) {
                                Icon(
                                    imageVector = TruyenCloudIcons.Check,
                                    contentDescription = "Đang dùng",
                                    tint = colors.accent,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}
