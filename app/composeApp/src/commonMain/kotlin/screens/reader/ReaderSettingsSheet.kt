package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.HairlineDivider
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.theme.Spacing

/**
 * Cài đặt đọc: giao diện + nền đọc, chữ và bố cục, hành vi, học tiếng Anh, công cụ AI. Mọi thay đổi gọi ngay callback để xem trước
 * trên trang phía sau; nút "Áp dụng cài đặt" chốt toàn bộ giá trị đang chọn rồi đóng.
 */
@Composable
fun ReaderSettingsSheet(
    fontSize: Int,
    themeMode: String,
    fontFamilyType: String = "serif",
    lineSpacingFactor: Float = 1.8f,
    isPaginatedMode: Boolean = false,
    pageMargin: Int = 16,
    isBilingualMode: Boolean = false,
    isEnglishMode: Boolean = false,
    isProgressiveMode: Boolean = false,
    currentEnglishLevel: String = "c1",
    progressiveRatio: Double = 0.5,
    dueWordsCount: Int = 0,
    allWordsCount: Int = 0,
    keepScreenOn: Boolean = true,
    volumeKeyPaging: Boolean = false,
    onKeepScreenOnChange: (Boolean) -> Unit = {},
    onVolumeKeyPagingChange: (Boolean) -> Unit = {},
    onFontSizeChange: (Int) -> Unit,
    onThemeChange: (String) -> Unit,
    onFontFamilyChange: (String) -> Unit = {},
    onLineSpacingChange: (Float) -> Unit = {},
    onTogglePaginatedMode: (Boolean) -> Unit = {},
    onPageMarginChange: (Int) -> Unit = {},
    onToggleBilingualMode: () -> Unit = {},
    onToggleEnglishMode: () -> Unit = {},
    onToggleProgressiveMode: () -> Unit = {},
    onSelectProgressiveRatio: (Double) -> Unit = {},
    onSelectEnglishLevel: (String) -> Unit = {},
    onApplySettings: ((
        themeMode: String,
        fontSize: Int,
        fontFamilyType: String,
        lineSpacingFactor: Float,
        isPaginatedMode: Boolean,
        pageMargin: Int,
        isBilingualMode: Boolean,
        isEnglishMode: Boolean,
        isProgressiveMode: Boolean,
        englishLevel: String,
        progressiveRatio: Double
    ) -> Unit)? = null,
    onOpenCompanionChat: () -> Unit = {},
    onOpenVocabDeck: () -> Unit = {},
    onOpenRecap: () -> Unit = {},
    onOpenComprehension: () -> Unit = {},
    onOpenLoreLookup: () -> Unit = {},
    onOpenGlossary: () -> Unit = {},
    onRetranslate: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var localTheme by remember(themeMode) { mutableStateOf(themeMode) }
    var localFontSize by remember(fontSize) { mutableStateOf(fontSize) }
    var localFontFamily by remember(fontFamilyType) { mutableStateOf(fontFamilyType) }
    var localLineSpacing by remember(lineSpacingFactor) { mutableStateOf(lineSpacingFactor) }
    var localPaginated by remember(isPaginatedMode) { mutableStateOf(isPaginatedMode) }
    var localPageMargin by remember(pageMargin) { mutableStateOf(pageMargin) }
    var localBilingual by remember(isBilingualMode) { mutableStateOf(isBilingualMode) }
    var localEnglish by remember(isEnglishMode) { mutableStateOf(isEnglishMode) }
    var localProgressive by remember(isProgressiveMode) { mutableStateOf(isProgressiveMode) }
    var localEnglishLevel by remember(currentEnglishLevel) { mutableStateOf(currentEnglishLevel.lowercase()) }
    var localProgressiveRatio by remember(progressiveRatio) { mutableStateOf(progressiveRatio) }
    var localKeepScreenOn by remember(keepScreenOn) { mutableStateOf(keepScreenOn) }
    var localVolumeKeys by remember(volumeKeyPaging) { mutableStateOf(volumeKeyPaging) }

    ReaderSheet(onDismiss = onDismiss, title = "Cài đặt đọc", modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = Spacing.xs, bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.section)
        ) {
            ReaderThemeSection(
                themeMode = localTheme,
                fontFamilyType = localFontFamily,
                onThemeChange = {
                    localTheme = it
                    onThemeChange(it)
                },
                onFontFamilyChange = {
                    localFontFamily = it
                    onFontFamilyChange(it)
                }
            )

            ReaderTypographySection(
                fontSize = localFontSize,
                isPaginatedMode = localPaginated,
                lineSpacingFactor = localLineSpacing,
                pageMargin = localPageMargin,
                onFontSizeChange = {
                    localFontSize = it
                    onFontSizeChange(it)
                },
                onTogglePaginatedMode = {
                    localPaginated = it
                    onTogglePaginatedMode(it)
                },
                onLineSpacingChange = {
                    localLineSpacing = it
                    onLineSpacingChange(it)
                },
                onPageMarginChange = {
                    localPageMargin = it
                    onPageMarginChange(it)
                }
            )

            ReaderBehaviorSection(
                keepScreenOn = localKeepScreenOn,
                volumeKeyPaging = localVolumeKeys,
                onKeepScreenOnChange = {
                    localKeepScreenOn = it
                    onKeepScreenOnChange(it)
                },
                onVolumeKeyPagingChange = {
                    localVolumeKeys = it
                    onVolumeKeyPagingChange(it)
                }
            )

            ReaderEnglishImmersionSection(
                isProgressiveMode = localProgressive,
                isBilingualMode = localBilingual,
                isEnglishMode = localEnglish,
                englishLevel = localEnglishLevel,
                progressiveRatio = localProgressiveRatio,
                dueWordsCount = dueWordsCount,
                allWordsCount = allWordsCount,
                onToggleProgressive = {
                    val next = !localProgressive
                    localProgressive = next
                    if (next) {
                        localBilingual = false
                        localEnglish = false
                    }
                    onToggleProgressiveMode()
                },
                onToggleBilingual = {
                    val next = !localBilingual
                    localBilingual = next
                    if (next) {
                        localEnglish = false
                        localProgressive = false
                    }
                    onToggleBilingualMode()
                },
                onToggleEnglish = {
                    val next = !localEnglish
                    localEnglish = next
                    if (next) {
                        localBilingual = false
                        localProgressive = false
                    }
                    onToggleEnglishMode()
                },
                onSelectProgressiveRatio = {
                    localProgressiveRatio = it
                    onSelectProgressiveRatio(it)
                },
                onSelectEnglishLevel = {
                    localEnglishLevel = it
                    onSelectEnglishLevel(it)
                },
                onOpenVocabDeck = {
                    onDismiss()
                    onOpenVocabDeck()
                }
            )

            ReaderAiIntelligenceSection(
                onOpenCompanionChat = {
                    onDismiss()
                    onOpenCompanionChat()
                },
                onOpenRecap = {
                    onDismiss()
                    onOpenRecap()
                },
                onOpenLoreLookup = {
                    onDismiss()
                    onOpenLoreLookup()
                },
                onOpenGlossary = {
                    onDismiss()
                    onOpenGlossary()
                },
                onOpenComprehension = {
                    onDismiss()
                    onOpenComprehension()
                },
                onRetranslate = {
                    onDismiss()
                    onRetranslate()
                }
            )
        }

        // Nút chốt cố định ở đáy sheet
        HairlineDivider()
        PrimaryButton(
            text = "Áp dụng cài đặt",
            onClick = {
                onApplySettings?.invoke(
                    localTheme,
                    localFontSize,
                    localFontFamily,
                    localLineSpacing,
                    localPaginated,
                    localPageMargin,
                    localBilingual,
                    localEnglish,
                    localProgressive,
                    localEnglishLevel,
                    localProgressiveRatio
                )
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = ReaderSheetInset, vertical = 12.dp)
        )
    }
}
