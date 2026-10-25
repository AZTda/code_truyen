package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.reader.GlossaryTerms

/**
 * Thuật ngữ chỉ có tác dụng khi nguyên văn KHỚP chữ trong bản gốc (máy chủ thay chuỗi trên bản gốc tiếng Trung trước khi dịch).
 * Chọn tên trong bản gốc thì chắc chắn khớp; chọn trong bản dịch tiếng Việt thì chữ Hán là do máy đoán và có thể lệch.
 */
@Composable
internal fun LoreSourceNote(
    term: String,
    hanzi: String,
    modifier: Modifier = Modifier,
    onReadOriginal: (() -> Unit)? = null
) {
    val fromOriginal = GlossaryTerms.containsCjk(term)
    val text = if (fromOriginal) {
        "Nguyên văn “${term.trim()}” chọn từ bản gốc nên khớp chính xác khi dịch lại chương."
    } else {
        val guess = if (hanzi.isNotBlank()) " “$hanzi”" else ""
        "Bạn đang tra từ bản dịch tiếng Việt: chữ Hán$guess là do máy đoán và có thể không khớp bản gốc — khi đó thuật ngữ " +
            "sẽ không có tác dụng. Chọn tên ngay trong bản gốc để chắc chắn."
    }
    ReaderBannerCard(
        message = text,
        tone = if (fromOriginal) NoticeTone.Success else NoticeTone.Warning,
        actions = if (!fromOriginal && onReadOriginal != null) {
            listOf(BannerAction("Xem bản gốc để chọn tên", primary = true, onClick = onReadOriginal))
        } else {
            emptyList()
        },
        modifier = modifier
    )
}

/** Dòng nhắn nhỏ dùng chung cho bảng thuật ngữ (cùng nội dung, không kèm một từ cụ thể). */
@Composable
internal fun GlossarySourceHint(modifier: Modifier = Modifier) {
    Text(
        text = "Thuật ngữ chỉ có tác dụng khi nguyên văn khớp đúng chữ trong bản gốc tiếng Trung. Hãy ghim tên khi đang xem bản " +
            "gốc; tên ghim từ bản dịch tiếng Việt dùng chữ Hán do máy đoán nên có thể không khớp.",
        style = AppText.captionRegular,
        color = LocalCustomColors.current.textSecondary,
        modifier = modifier
    )
}

/**
 * Gợi ý hiện NGAY dưới đoạn văn khi đoạn đó là bản dịch tiếng Việt: chọn tên trong bản gốc (chữ Hán) thì thuật ngữ khớp chính xác,
 * còn chữ Hán máy đoán từ bản dịch có thể lệch. Nút chuyển thẳng sang bản gốc.
 */
@Composable
internal fun LoreOriginalNudge(onReadOriginal: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Mẹo: chọn tên trong bản gốc (chữ Hán) để thuật ngữ khớp chính xác.",
            style = AppText.captionRegular,
            color = LocalCustomColors.current.textSecondary,
            modifier = Modifier.weight(1f)
        )
        TextActionButton(text = "Xem bản gốc", onClick = onReadOriginal)
    }
}
