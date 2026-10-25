package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.CardShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont
import com.truyencloud.shared.practice.PracticeItem
import com.truyencloud.shared.practice.PracticeMode
import com.truyencloud.shared.practice.PracticeResult

/**
 * Khung kết quả sau khi chấm một câu: nền xanh = đúng, vàng = gần đúng, đỏ = sai. Màu chỉ ở nền và biểu tượng; lời nhận xét
 * dùng màu chữ chính để đạt tương phản trên nền nhạt ở giao diện sáng.
 */
@Composable
internal fun PracticeResultCard(
    result: PracticeResult,
    item: PracticeItem,
    onSpeak: (String) -> Unit
) {
    val c = LocalCustomColors.current
    val (tone, fill) = when {
        result.isCorrect && !result.isNearMiss -> c.success to c.successSoft
        result.isCorrect -> c.warning to c.warningSoft
        else -> c.error to c.errorSoft
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(fill)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (result.isCorrect) TruyenCloudIcons.CheckCircle else TruyenCloudIcons.Close,
                contentDescription = null,
                tint = tone,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(text = result.feedback, style = AppText.bodyStrong, color = c.textPrimary)
        }
        val showAnswer = item.mode != PracticeMode.PRONUNCIATION &&
            (item.mode == PracticeMode.LISTENING || result.isNearMiss || !result.isCorrect)
        if (showAnswer) {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Nghe đáp án") { onSpeak(result.expected) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = TruyenCloudIcons.VolumeUp,
                    contentDescription = null,
                    tint = c.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = result.expected,
                    style = AppText.body.copy(fontFamily = ReaderFont),
                    color = c.textPrimary
                )
            }
        }
    }
}
