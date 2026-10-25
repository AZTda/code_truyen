package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.data.extraContexts

/**
 * Các câu khác gặp cùng một từ ("ran", "running" gộp vào thẻ "run"): thay vì tạo thẻ trùng,
 * câu mới được gắn vào thẻ cũ và hiện ở mặt sau.
 */
@Composable
internal fun FlashcardExtraContexts(word: SavedWord) {
    val extras = remember(word.extraContextsJson) { word.extraContexts().takeLast(3).reversed() }
    if (extras.isEmpty()) return
    val c = LocalCustomColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("Gặp thêm trong truyện")
        extras.forEach { ctx ->
            Text(
                text = "“${ctx.sentence}”" + if (ctx.form.isNotBlank() && !ctx.form.equals(word.word, true)) " (${ctx.form})" else "",
                style = AppText.secondary.copy(fontFamily = ReaderFont),
                color = c.textSecondary
            )
        }
    }
}
