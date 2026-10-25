package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.InsetGroupHeader
import com.truyencloud.compose.components.IosSheet
import com.truyencloud.compose.components.SegmentedControl
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

/**
 * Tờ trượt "Hồ sơ văn phong và chỉ dẫn dịch": thể loại chính, mức giữ từ Hán Việt và chỉ dẫn tùy chỉnh. Hồ sơ dịch dùng
 * chung cho mọi người dùng trên máy chủ nên chỉ quản trị viên ghi được (403 với người khác): [isAdmin] = false thì mọi
 * điều khiển bị khoá và có dòng giải thích.
 */
@Composable
fun TranslationProfileSheet(
    selectedGenre: String,
    onGenreChange: (String) -> Unit,
    sinoVietnameseRatio: String,
    onSinoVietnameseRatioChange: (String) -> Unit,
    customPrompt: String,
    onCustomPromptChange: (String) -> Unit,
    onSaveProfile: () -> Unit,
    isSaving: Boolean,
    saveMessage: String,
    onDismiss: () -> Unit,
    /** Hồ sơ dịch dùng chung cho mọi người dùng: chỉ quản trị viên được ghi (403 với người khác). */
    isAdmin: Boolean = true
) {
    val colors = LocalCustomColors.current
    val ratioIndex = RATIO_OPTIONS.indexOfFirst { it.value == sinoVietnameseRatio }

    IosSheet(onDismissRequest = onDismiss, title = "Hồ sơ văn phong và chỉ dẫn dịch") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            if (!isAdmin) AdminOnlyNotice()

            InsetGroupHeader("Thể loại tiểu thuyết chính")
            GENRE_ITEMS.forEach { item ->
                GenreRow(
                    item = item,
                    isSelected = item.name == selectedGenre,
                    enabled = isAdmin,
                    onClick = { onGenreChange(item.name) }
                )
            }

            Spacer(modifier = Modifier.height(Spacing.lg))
            InsetGroupHeader("Mức độ giữ từ ngữ Hán Việt")
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                OnCardTrack {
                    SegmentedControl(
                        options = RATIO_OPTIONS.map { it.title },
                        selectedIndex = ratioIndex.coerceAtLeast(0),
                        onSelect = { onSinoVietnameseRatioChange(RATIO_OPTIONS[it].value) },
                        enabled = isAdmin
                    )
                }
                if (ratioIndex >= 0) {
                    Text(
                        text = RATIO_OPTIONS[ratioIndex].subtitle,
                        style = AppText.secondary,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.lg))
            InsetGroupHeader("Chỉ dẫn dịch thuật tùy chỉnh")
            TranslationProfilePromptCard(
                customPrompt = customPrompt,
                onCustomPromptChange = onCustomPromptChange,
                quickSuggestionTags = QUICK_SUGGESTION_TAGS,
                saveMessage = saveMessage,
                isSaving = isSaving,
                onSaveProfile = onSaveProfile,
                enabled = isAdmin,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}
