package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont

/**
 * Một tình huống ứng dụng: nhãn loại tình huống, câu tiếng Anh (serif), nghĩa tiếng Việt và mẹo dùng.
 * [badgeColor] chỉ đặt khi cần nhấn riêng; mặc định nhãn dùng chữ phụ.
 */
@Composable
fun ScenarioCard(
    badge: String,
    badgeColor: Color = Color.Unspecified,
    title: String,
    english: String,
    meaning: String,
    tip: String,
    onSpeak: () -> Unit
) {
    val c = LocalCustomColors.current

    FlatCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = badge,
                    style = AppText.secondaryStrong,
                    color = badgeColor.takeOrElse { c.textSecondary },
                    modifier = Modifier.weight(1f)
                )
                QuietIconButton(TruyenCloudIcons.VolumeUp, "Nghe câu ví dụ", onSpeak)
            }

            if (title.isNotBlank()) {
                Text(text = title, style = AppText.bodyStrong, color = c.textPrimary)
            }
            Text(
                text = "“$english”",
                style = AppText.body.copy(fontFamily = ReaderFont),
                color = c.textPrimary
            )
            Text(text = meaning, style = AppText.secondary, color = c.textSecondary)
            if (tip.isNotBlank()) {
                Text(text = tip, style = AppText.captionRegular, color = c.textTertiary)
            }
        }
    }
}
