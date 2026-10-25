package com.truyencloud.compose.screens.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.InsetGroup
import com.truyencloud.compose.components.InsetRow
import com.truyencloud.compose.components.icons.TruyenCloudIcons

/** Công cụ AI của chương đang đọc: trò chuyện, tóm tắt, tra cứu tên, trắc nghiệm, dịch lại. Mỗi dòng mở bảng tương ứng. */
@Composable
internal fun ReaderAiIntelligenceSection(
    onOpenCompanionChat: () -> Unit = {},
    onOpenRecap: () -> Unit,
    onOpenLoreLookup: () -> Unit,
    onOpenGlossary: () -> Unit = {},
    onOpenComprehension: () -> Unit,
    onRetranslate: () -> Unit,
    modifier: Modifier = Modifier
) {
    InsetGroup(modifier = modifier, header = "Trợ lý AI") {
        InsetRow(
            title = "Trò chuyện với trợ lý AI",
            icon = TruyenCloudIcons.Chat,
            showChevron = true,
            onClick = onOpenCompanionChat
        )
        InsetRow(
            title = "Tóm tắt trước khi đọc",
            icon = TruyenCloudIcons.List,
            showChevron = true,
            onClick = onOpenRecap
        )
        InsetRow(
            title = "Tra cứu tên và điển tích",
            icon = TruyenCloudIcons.Search,
            showChevron = true,
            onClick = onOpenLoreLookup
        )
        InsetRow(
            title = "Bảng thuật ngữ",
            icon = TruyenCloudIcons.MenuBook,
            showChevron = true,
            onClick = onOpenGlossary
        )
        InsetRow(
            title = "Trắc nghiệm đọc hiểu tiếng Anh",
            icon = TruyenCloudIcons.School,
            showChevron = true,
            onClick = onOpenComprehension
        )
        InsetRow(
            title = "Dịch lại chương này bằng AI",
            icon = TruyenCloudIcons.Translate,
            showChevron = true,
            onClick = onRetranslate
        )
    }
}
