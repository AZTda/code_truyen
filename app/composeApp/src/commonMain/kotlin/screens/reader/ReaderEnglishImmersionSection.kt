package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.InsetGroup
import com.truyencloud.compose.components.InsetRow
import com.truyencloud.compose.components.SegmentedControl
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

private val ProgressiveRatios = listOf(0.25 to "25%", 0.50 to "50%", 0.75 to "75%", 1.00 to "100%")
private val EnglishLevels = listOf(
    Triple("b1", "B1", "B1: dễ hiểu, từ vựng phổ thông (Oxford 3000)"),
    Triple("b2", "B2", "B2: phổ thông, văn phong tiểu thuyết hiện đại"),
    Triple("c1", "C1", "C1: nâng cao, văn học giàu hình ảnh"),
    Triple("c2", "C2", "C2: uyên bác, văn phong cổ điển")
)

/**
 * Học tiếng Anh khi đọc: chọn ngôn ngữ đọc (Việt / Hòa trộn / Song ngữ / Anh — loại trừ nhau), tỉ lệ hòa trộn, trình độ CEFR và lối vào
 * sổ từ vựng. Mỗi lựa chọn gọi đúng hàm bật / tắt chế độ tương ứng như trước; chọn lại "Việt" là tắt chế độ đang bật.
 */
@Composable
internal fun ReaderEnglishImmersionSection(
    isProgressiveMode: Boolean,
    isBilingualMode: Boolean,
    isEnglishMode: Boolean,
    englishLevel: String,
    progressiveRatio: Double,
    dueWordsCount: Int,
    allWordsCount: Int,
    onToggleProgressive: () -> Unit,
    onToggleBilingual: () -> Unit,
    onToggleEnglish: () -> Unit,
    onSelectProgressiveRatio: (Double) -> Unit,
    onSelectEnglishLevel: (String) -> Unit,
    onOpenVocabDeck: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val modeIndex = when {
        isProgressiveMode -> 1
        isBilingualMode -> 2
        isEnglishMode -> 3
        else -> 0
    }
    val levelIndex = EnglishLevels.indexOfFirst { it.first == englishLevel.lowercase() }.coerceAtLeast(0)
    val ratioIndex = ProgressiveRatios.indices.minByOrNull { kotlin.math.abs(progressiveRatio - ProgressiveRatios[it].first) } ?: 0
    val modeHint = when (modeIndex) {
        1 -> "Tiếng Việt xen các từ tiếng Anh theo tỉ lệ bạn chọn."
        2 -> "Mỗi đoạn tiếng Anh kèm bản dịch tiếng Việt ngay bên dưới."
        3 -> "Đọc toàn bộ bằng tiếng Anh ở trình độ đã chọn."
        else -> "Đọc bản dịch tiếng Việt."
    }

    fun selectMode(index: Int) {
        when (index) {
            0 -> when {
                isProgressiveMode -> onToggleProgressive()
                isBilingualMode -> onToggleBilingual()
                isEnglishMode -> onToggleEnglish()
                else -> Unit
            }
            1 -> if (!isProgressiveMode) onToggleProgressive()
            2 -> if (!isBilingualMode) onToggleBilingual()
            else -> if (!isEnglishMode) onToggleEnglish()
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(
            modifier = Modifier.padding(horizontal = ReaderSheetInset),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            ReaderSettingBlock(label = "Ngôn ngữ đọc") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SegmentedControl(
                        options = listOf("Việt", "Hòa trộn", "Song ngữ", "Anh"),
                        selectedIndex = modeIndex,
                        onSelect = { selectMode(it) }
                    )
                    Text(text = modeHint, style = AppText.captionRegular, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            if (isProgressiveMode) {
                ReaderSettingBlock(label = "Tỉ lệ từ tiếng Anh") {
                    SegmentedControl(
                        options = ProgressiveRatios.map { it.second },
                        selectedIndex = ratioIndex,
                        onSelect = { onSelectProgressiveRatio(ProgressiveRatios[it].first) }
                    )
                }
            }
            ReaderSettingBlock(label = "Trình độ tiếng Anh") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SegmentedControl(
                        options = EnglishLevels.map { it.second },
                        selectedIndex = levelIndex,
                        onSelect = { onSelectEnglishLevel(EnglishLevels[it].first) }
                    )
                    Text(
                        text = EnglishLevels[levelIndex].third,
                        style = AppText.captionRegular,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }

        InsetGroup {
            InsetRow(
                title = "Sổ từ vựng",
                icon = TruyenCloudIcons.Bookmark,
                value = if (dueWordsCount > 0) "$dueWordsCount từ cần ôn" else "$allWordsCount từ đã lưu",
                showChevron = true,
                onClick = onOpenVocabDeck
            )
        }
    }
}
