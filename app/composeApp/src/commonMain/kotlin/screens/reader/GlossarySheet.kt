package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.HairlineDivider
import com.truyencloud.compose.components.IosConfirmDialog
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.reader.GlossaryEntry
import com.truyencloud.shared.reader.GlossaryMerge
import com.truyencloud.shared.reader.GlossaryOrigin

/**
 * Bảng thuật ngữ của truyện: thuật ngữ trên máy (sửa / ghim / xoá) gộp với bảng máy chủ đang áp (nhãn "tự động"). Nút
 * [onRetranslate] dịch lại chương đang đọc để bảng này có hiệu lực ngay.
 */
@Composable
fun GlossarySheet(
    state: GlossaryUiState,
    canRetranslate: Boolean,
    onRetranslate: () -> Unit,
    onSaveTarget: (GlossaryEntry, String) -> Unit,
    onTogglePin: (GlossaryEntry) -> Unit,
    onDelete: (GlossaryEntry) -> Unit,
    onAdd: (String, String) -> Unit,
    onClearAll: () -> Unit,
    onOpenLookup: () -> Unit,
    onDismissMessage: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    var query by remember { mutableStateOf("") }
    var editingKey by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val shown = remember(state.entries, query) { GlossaryMerge.filter(state.entries, query) }
    val mine = state.entries.count { it.origin == GlossaryOrigin.LOCAL }

    ReaderSheet(
        onDismiss = onDismiss,
        title = "Bảng thuật ngữ",
        subtitle = "$mine của bạn · ${state.entries.size - mine} từ máy chủ",
        modifier = modifier.fillMaxHeight(0.86f),
        withIme = true,
        headerActions = {
            if (state.isLoading) {
                CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
        }
    ) {
        // Phần đầu (gợi ý, nút thêm / tra cứu, form, ô tìm, thông báo) cuộn cùng danh sách để màn hẹp / chữ lớn vẫn còn chỗ cho các mục
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = ReaderSheetInset, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "intro") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    GlossarySourceHint()
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        TonalButton(
                            text = if (showAdd) "Ẩn form thêm" else "Thêm thuật ngữ",
                            onClick = { showAdd = !showAdd },
                            modifier = Modifier.weight(1f)
                        )
                        TonalButton(text = "Tra cứu thêm", onClick = onOpenLookup, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (showAdd) {
                item(key = "add") { GlossaryAddForm(onAdd = onAdd, onCancel = { showAdd = false }) }
            }
            if (state.entries.size > 6) {
                item(key = "search") { GlossarySearchField(query = query, onQueryChange = { query = it }) }
            }
            if (state.serverNotice != null || state.message != null) {
                item(key = "status") { GlossaryStatusLines(state = state, onDismissMessage = onDismissMessage) }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = if (state.entries.isEmpty()) {
                            "Chưa có thuật ngữ nào. Chạm giữ một đoạn văn rồi chọn “Giải thích hoặc ghim tên”, hoặc bấm “Thêm thuật ngữ”."
                        } else {
                            "Không có thuật ngữ nào khớp “$query”."
                        },
                        style = AppText.secondary,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(Spacing.lg)
                    )
                }
            } else {
                items(shown, key = { it.key }) { entry ->
                    GlossaryEntryRow(
                        entry = entry,
                        isEditing = editingKey == entry.key,
                        onStartEdit = { editingKey = entry.key },
                        onCancelEdit = { editingKey = null },
                        onSave = { target ->
                            onSaveTarget(entry, target)
                            editingKey = null
                        },
                        onTogglePin = { onTogglePin(entry) },
                        onDelete = { onDelete(entry) }
                    )
                }
            }
        }

        // Chân bảng: việc chính (dịch lại để bảng có hiệu lực) và việc nguy hiểm (xoá hết) tách riêng
        HairlineDivider()
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ReaderSheetInset, vertical = Spacing.sm)) {
            PrimaryButton(
                text = "Dịch lại chương này với bảng thuật ngữ",
                onClick = onRetranslate,
                modifier = Modifier.fillMaxWidth()
            )
            if (!canRetranslate) {
                Text(
                    text = "Cần đăng nhập để dịch lại bằng AI.",
                    style = AppText.captionRegular,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (mine > 0) {
                TextActionButton(
                    text = "Xóa tất cả thuật ngữ của bạn",
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth(),
                    destructive = true
                )
            }
        }
    }

    if (confirmClear) {
        IosConfirmDialog(
            title = "Xóa tất cả thuật ngữ của bạn?",
            message = "Xóa mọi thuật ngữ riêng của truyện này. Thuật ngữ dùng chung cho mọi truyện được giữ nguyên.",
            confirmLabel = "Xóa tất cả",
            destructive = true,
            onConfirm = {
                confirmClear = false
                onClearAll()
            },
            onDismiss = { confirmClear = false }
        )
    }
}
