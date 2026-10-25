package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.ChipShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.api.ChapterRecapResponse

/** Nội dung một bản tóm tắt THẬT (đã qua [com.truyencloud.shared.reader.RecapPolicy]): bối cảnh, diễn biến, nhân vật. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChapterRecapContent(
    recap: ChapterRecapResponse,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current

    Column(modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (recap.context.isNotBlank()) {
            FlatCard(modifier = Modifier.fillMaxWidth(), outlined = true) {
                Text(text = recap.context, style = AppText.body, color = colors.textPrimary)
            }
            Spacer(modifier = Modifier.height(Spacing.lg))
        }

        if (recap.bullets.isNotEmpty()) {
            ReaderSectionLabel("Diễn biến then chốt")
            Spacer(modifier = Modifier.height(4.dp))
            recap.bullets.forEach { bullet ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp, end = 12.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(colors.accent)
                    )
                    Text(text = bullet, style = AppText.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(Spacing.lg))
        }

        if (recap.characters.isNotEmpty()) {
            ReaderSectionLabel("Nhân vật hiện diện")
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                recap.characters.forEach { charName ->
                    Box(
                        modifier = Modifier
                            .clip(ChipShape)
                            .background(colors.card)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(text = charName, style = AppText.secondaryStrong, color = colors.textPrimary)
                    }
                }
            }
            Spacer(modifier = Modifier.height(Spacing.lg))
        }

        PrimaryButton(
            text = "Đã nắm rõ, bắt đầu đọc",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(Spacing.lg))
    }
}
