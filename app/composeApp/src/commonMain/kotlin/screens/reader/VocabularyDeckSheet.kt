package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.EmptyState
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.ButtonShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.data.getCurrentEpochMillis
import com.truyencloud.shared.repo.toMemoryState
import com.truyencloud.shared.srs.FsrsConfig
import com.truyencloud.shared.srs.FsrsScheduler
import com.truyencloud.shared.srs.Rating
import com.truyencloud.shared.srs.ReviewGate
import com.truyencloud.shared.srs.StudyDay

/** Sổ từ vựng ôn nhanh ngay trong màn đọc: lật thẻ rồi tự chấm ba mức. Khoảng ôn lấy từ bộ lập lịch FSRS, không đặt cố định. */
@Composable
fun VocabularyDeckSheet(
    words: List<SavedWord>,
    allWords: List<SavedWord> = words,
    onReviewWord: (String, Int) -> Unit,
    onSpeakWord: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ReaderModalSheet(onDismiss = onDismiss, title = "Sổ từ vựng") {
        VocabularyDeckBody(words, allWords, onReviewWord, onSpeakWord)
    }
}

/** Phần thân cuộn được của sổ từ vựng: trạng thái hết thẻ, hoặc thẻ lật + ba nút chấm điểm. */
@Composable
internal fun ColumnScope.VocabularyDeckBody(
    words: List<SavedWord>,
    allWords: List<SavedWord> = words,
    onReviewWord: (String, Int) -> Unit,
    onSpeakWord: (String) -> Unit
) {
    val colors = LocalCustomColors.current
    var practiceAllMode by remember { mutableStateOf(words.isEmpty() && allWords.isNotEmpty()) }
    var sessionDeck by remember { mutableStateOf(if (words.isNotEmpty()) words else allWords) }
    var currentIndex by remember { mutableStateOf(0) }
    var isFlipped by remember { mutableStateOf(false) }

    if (sessionDeck.isEmpty() && currentIndex == 0) {
        val candidate = if (words.isNotEmpty()) words else allWords
        if (candidate.isNotEmpty()) {
            sessionDeck = candidate
            practiceAllMode = words.isEmpty()
        }
    }

    Column(
        modifier = Modifier
            .weight(1f, fill = false)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ReaderSheetInset)
    ) {
        if (sessionDeck.isEmpty() || currentIndex >= sessionDeck.size) {
            EmptyState(
                message = "Hoàn thành xuất sắc!",
                subMessage = "Hãy tiếp tục đọc và chạm vào từ vựng mới để lưu vào bộ nhớ dài hạn.",
                icon = TruyenCloudIcons.CheckCircle
            )
            if (allWords.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Spacing.md))
                TonalButton(
                    text = "Ôn thêm toàn bộ từ (${allWords.size})",
                    onClick = {
                        practiceAllMode = true
                        sessionDeck = allWords
                        currentIndex = 0
                        isFlipped = false
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            val snapshotWord = sessionDeck[currentIndex]
            val currentWord = allWords.firstOrNull { it.id == snapshotWord.id }
                ?: words.firstOrNull { it.id == snapshotWord.id }
                ?: snapshotWord

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val deckLabel = if (practiceAllMode) "Ôn toàn bộ" else "Đến hạn"
                Text(
                    text = "$deckLabel • Thẻ ${currentIndex + 1} / ${sessionDeck.size}",
                    style = AppText.secondary,
                    color = colors.textSecondary
                )
                Text(
                    text = "Trình độ ${currentWord.cefrLevel.uppercase()}",
                    style = AppText.secondaryStrong,
                    color = colors.onAccentSoft
                )
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            VocabularyFlipCard(
                currentWord = currentWord,
                isFlipped = isFlipped,
                onToggleFlip = { isFlipped = !isFlipped },
                onSpeakWord = onSpeakWord
            )

            Spacer(modifier = Modifier.height(Spacing.lg))

            // 3 nút chấm điểm. Nhãn khoảng cách lấy từ chính bộ lập lịch FSRS (không còn "1 / 2 / 4 ngày" cố định);
            // thẻ chưa đến hạn thì ghi rõ là không đổi lịch, vì kết quả chỉ được lưu như một lượt luyện tập.
            val now = remember(currentWord.id, currentWord.dueEpoch) { getCurrentEpochMillis() }
            val memory = currentWord.toMemoryState()
            val scheduled = ReviewGate.isDueForScheduling(memory, now, StudyDay.startOfToday(now))
            val labels = remember(currentWord.id, currentWord.dueEpoch, currentWord.stability) {
                FsrsScheduler.previewIntervals(memory, now, FsrsConfig(), currentWord.id)
                    .mapValues { (_, outcome) -> FsrsScheduler.formatInterval(outcome, now) }
            }
            fun sub(rating: Rating) = if (scheduled) labels[rating] ?: "—" else "không đổi lịch"

            // 4 nút chấm điểm FSRS: Quên (1), Khó (2), Tốt (3), Dễ (4) kèm khoảng cách ôn dự kiến
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("Quên", Rating.AGAIN, colors.error),
                    Triple("Khó", Rating.HARD, colors.warning),
                    Triple("Tốt", Rating.GOOD, colors.accent),
                    Triple("Dễ", Rating.EASY, colors.success)
                ).forEach { (label, rating, tone) ->
                    RatingTile(
                        label = label,
                        hint = sub(rating),
                        tone = tone,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onReviewWord(currentWord.id, rating.value)
                            isFlipped = false
                            currentIndex++
                        }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(Spacing.lg))
    }
}

@Composable
private fun RatingTile(label: String, hint: String, tone: Color, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalCustomColors.current
    Column(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(ButtonShape)
            .border(1.dp, colors.outline, ButtonShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = label, style = AppText.bodyStrong, color = tone, textAlign = TextAlign.Center, maxLines = 1)
        Text(text = hint, style = AppText.caption, color = colors.textSecondary, textAlign = TextAlign.Center, maxLines = 1)
    }
}
