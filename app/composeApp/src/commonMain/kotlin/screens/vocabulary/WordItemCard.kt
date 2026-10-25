package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.SavedWord

/**
 * Một dòng của Sổ từ: từ (serif 18), mức CEFR dạng chữ nhỏ, phiên âm, nghĩa và tên truyện. Chạm cả dòng để mở từ
 * ([WordDetailSheet]); nghe, vẽ thẻ minh họa và luyện ứng dụng nằm trong đó thay vì một nút lớn trên mỗi dòng.
 */
@Composable
internal fun WordRow(
    word: SavedWord,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalCustomColors.current
    val ipa = ipaLine(word)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.pageH, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = word.word,
                    style = AppText.wordTitle,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                Text(text = cefrLabel(word.cefrLevel), style = AppText.caption, color = c.textSecondary, maxLines = 1)
            }
            if (ipa.isNotBlank()) {
                Text(text = ipa, style = AppText.secondary, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (word.contextMeaningVi.isNotBlank()) {
                Text(
                    text = word.contextMeaningVi,
                    style = AppText.body,
                    color = c.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (word.bookTitle.isNotBlank()) {
                Text(
                    text = word.bookTitle,
                    style = AppText.captionRegular,
                    color = c.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        Icon(
            imageVector = TruyenCloudIcons.ChevronRight,
            contentDescription = null,
            tint = c.textTertiary,
            modifier = Modifier.size(20.dp)
        )
    }
}
