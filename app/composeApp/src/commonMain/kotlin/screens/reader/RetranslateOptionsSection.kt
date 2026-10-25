package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.truyencloud.compose.components.SegmentedControl
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

// Nhãn ngắn ("Việt" / "Anh") để ba ô vẫn đọc trọn ở màn 320dp hoặc chữ phóng 1.3 lần; ngữ cảnh đã có ở nhãn "Ngôn ngữ đích"
private val TargetLanguages = listOf("vi" to "Việt", "en" to "Anh", "bilingual" to "Song ngữ")
private val TargetLanguageHints = mapOf(
    "vi" to "Dịch sang tiếng Việt chuẩn văn học.",
    "en" to "Chuyển thể sang tiếng Anh ở trình độ đã chọn bên dưới.",
    "bilingual" to "Mỗi đoạn có bản tiếng Anh kèm bản dịch tiếng Việt đối ứng."
)
private val EnglishLevelOptions = listOf(
    Triple("b1", "B1", "B1: dễ hiểu, từ vựng Oxford 3000"),
    Triple("b2", "B2", "B2: phổ thông, tiểu thuyết hiện đại"),
    Triple("c1", "C1", "C1: nâng cao, văn học lãng mạn"),
    Triple("c2", "C2", "C2: uyên bác, văn phong cổ điển")
)

/** Chọn ngôn ngữ đích (Việt / Anh / Song ngữ) và, với bản Anh, trình độ CEFR. Mỗi lựa chọn có một dòng gợi ý ngắn bên dưới. */
@Composable
fun RetranslateOptionsSection(
    selectedLanguage: String,
    onSelectLanguage: (String) -> Unit,
    selectedEnglishLevel: String,
    onSelectEnglishLevel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val languageIndex = TargetLanguages.indexOfFirst { it.first == selectedLanguage }.coerceAtLeast(0)
    val levelIndex = EnglishLevelOptions.indexOfFirst { it.first == selectedEnglishLevel }.coerceAtLeast(0)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        ReaderSectionLabel("Ngôn ngữ đích")
        SegmentedControl(
            options = TargetLanguages.map { it.second },
            selectedIndex = languageIndex,
            onSelect = { onSelectLanguage(TargetLanguages[it].first) }
        )
        Text(
            text = TargetLanguageHints[TargetLanguages[languageIndex].first].orEmpty(),
            style = AppText.captionRegular,
            color = colors.textSecondary
        )

        if (selectedLanguage in listOf("en", "bilingual")) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            ReaderSectionLabel("Trình độ tiếng Anh (CEFR)")
            SegmentedControl(
                options = EnglishLevelOptions.map { it.second },
                selectedIndex = levelIndex,
                onSelect = { onSelectEnglishLevel(EnglishLevelOptions[it].first) }
            )
            Text(text = EnglishLevelOptions[levelIndex].third, style = AppText.captionRegular, color = colors.textSecondary)
        }
    }
}
