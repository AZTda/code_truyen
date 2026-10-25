package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.api.LoreExplainResponse
import com.truyencloud.shared.reader.AiErrors
import com.truyencloud.shared.reader.GlossaryTerms
import com.truyencloud.shared.reader.LorePolicy

/**
 * Tra cứu Hán Việt & điển tích. [context] là đoạn văn người đọc vừa chạm giữ: hiện ra để bôi đen tên cần tra và được gửi kèm
 * cho AI. [initialTerm] đổi (chọn từ một đoạn khác) thì ô nhập đổi theo.
 */
@Composable
fun LoreLookupSheet(
    initialTerm: String = "",
    loreData: LoreExplainResponse?,
    isLoading: Boolean,
    onLookupTerm: (String) -> Unit,
    onDismiss: () -> Unit,
    isSaved: Boolean = false,
    targetInput: String = "",
    onTargetChange: (String) -> Unit = {},
    isSaving: Boolean = false,
    saveMessage: String? = null,
    glossarySize: Int = 0,
    canSave: Boolean = false,
    onSave: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    context: String = "",
    error: String? = null,
    onRetry: () -> Unit = {},
    onOpenGlossary: (() -> Unit)? = null,
    onReadOriginal: (() -> Unit)? = null
) {
    var inputQuery by remember(initialTerm) { mutableStateOf(initialTerm) }

    ReaderSheet(
        onDismiss = onDismiss,
        title = "Tra cứu tên và điển tích",
        subtitle = if (glossarySize > 0) "Bảng thuật ngữ đang có $glossarySize mục" else null,
        modifier = modifier,
        withIme = true,
        headerActions = {
            if (onOpenGlossary != null) {
                QuietIconButton(TruyenCloudIcons.List, "Mở bảng thuật ngữ", onOpenGlossary)
            }
        }
    ) {
        // Đoạn văn + ô tra cứu đứng yên phía trên (chọn chữ bằng cách chạm giữ rồi kéo, không để cuộn ngoài tranh cử chỉ)
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ReaderSheetInset)) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            if (context.isNotBlank()) {
                LoreContextCard(context = context, onTermSelected = { inputQuery = it })
                if (onReadOriginal != null && !GlossaryTerms.looksChinese(context)) {
                    LoreOriginalNudge(onReadOriginal = onReadOriginal)
                }
                Spacer(modifier = Modifier.height(Spacing.sm))
            }
            LoreLookupSearchBar(query = inputQuery, onQueryChange = { inputQuery = it }, onLookup = onLookupTerm)
            Spacer(modifier = Modifier.height(Spacing.md))
        }

        Box(modifier = Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = ReaderSheetInset)) {
            when {
                isLoading -> AiLoadingPanel("AI đang giải mã Hán tự và điển tích…")
                error != null -> AiErrorPanel(
                    title = LorePolicy.MSG_FAILED,
                    detail = error,
                    onRetry = if (error == AiErrors.MSG_LOGIN_REQUIRED) null else onRetry
                )
                loreData != null -> Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    LoreEntityDetailCard(loreData = loreData)

                    // Chốt cách dịch vào bảng thuật ngữ của truyện
                    if (canSave) {
                        Spacer(modifier = Modifier.height(Spacing.xl))
                        LoreGlossarySection(
                            loreData = loreData,
                            targetInput = targetInput,
                            onTargetChange = onTargetChange,
                            isSaved = isSaved,
                            isSaving = isSaving,
                            onSave = onSave,
                            saveMessage = saveMessage,
                            glossarySize = glossarySize,
                            // Từ ĐÃ tra (không phải chữ đang gõ dở trong ô nhập): đó mới là thứ sẽ được lưu
                            term = initialTerm,
                            onReadOriginal = onReadOriginal,
                            onOpenGlossary = onOpenGlossary
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.lg))
                }
            }
        }
    }
}
