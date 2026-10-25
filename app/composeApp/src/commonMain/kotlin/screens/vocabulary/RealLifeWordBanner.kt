package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.IconTileShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.SavedWord

/** Dải chọn từ đang học (cuộn ngang, chạy sát mép màn hình). Cuộn sẵn tới từ đang chọn khi vào từ Sổ từ. */
@Composable
fun RealLifeWordPickerRibbon(
    allWords: List<SavedWord>,
    currentWord: SavedWord,
    onSelectWord: (SavedWord) -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(currentWord.id) {
        val index = allWords.indexOfFirst { it.id == currentWord.id }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Column {
        SectionLabel("Chọn từ đang học", Modifier.padding(horizontal = Spacing.pageH))
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = Spacing.pageH),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            items(allWords, key = { it.id }) { word ->
                FilterChip8(
                    label = word.word,
                    selected = word.id == currentWord.id,
                    onClick = { onSelectWord(word) }
                )
            }
        }
    }
}

/** Thẻ từ đang luyện: từ (serif), nghĩa, nút nghe và hình minh họa hoặc nút vẽ thẻ. */
@Composable
fun RealLifeActiveWordBanner(
    currentWord: SavedWord,
    generatingArtWords: Set<String>,
    onSpeak: (String) -> Unit,
    onGenerateArt: (SavedWord) -> Unit
) {
    val c = LocalCustomColors.current

    FlatCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentWord.word,
                        style = AppText.wordTitle.copy(fontSize = 24.sp, lineHeight = 30.sp),
                        color = c.textPrimary
                    )
                    if (currentWord.contextMeaningVi.isNotBlank()) {
                        Text(text = currentWord.contextMeaningVi, style = AppText.body, color = c.textSecondary)
                    }
                }
                QuietIconButton(TruyenCloudIcons.VolumeUp, "Phát âm từ ${currentWord.word}", { onSpeak(currentWord.word) })
            }

            if (currentWord.flashcardArtUrl.isNotBlank()) {
                Row(verticalAlignment = Alignment.Top) {
                    AsyncImage(
                        model = TruyenCloudApi.getImageProxyUrl(currentWord.flashcardArtUrl),
                        contentDescription = "Hình minh họa cho từ ${currentWord.word}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 56.dp, height = 80.dp).clip(IconTileShape)
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                        SectionLabel("Mẹo nhớ bằng hình")
                        Text(
                            text = currentWord.visualMnemonic.ifBlank { "Thẻ minh họa đã sẵn sàng." },
                            style = AppText.secondary,
                            color = c.textPrimary
                        )
                    }
                }
            } else {
                val isGenerating = generatingArtWords.contains(currentWord.word)
                TonalButton(
                    text = if (isGenerating) "Đang vẽ thẻ minh họa..." else "Vẽ thẻ minh họa",
                    onClick = { onGenerateArt(currentWord) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isGenerating,
                    leadingIcon = TruyenCloudIcons.Image
                )
            }
        }
    }
}
