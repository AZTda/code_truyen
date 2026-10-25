package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import com.truyencloud.compose.screens.home.imeAboveTabBar
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.EmptyState
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.NoticeBanner
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.SectionHeader
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.platform.SpeechResult
import com.truyencloud.compose.platform.rememberSpeechRecognizer
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.SavedWord

@Composable
fun RealLifeStudioView(
    uiState: VocabularyUiState,
    onSelectWord: (SavedWord) -> Unit,
    onSentenceChange: (String) -> Unit,
    onSubmitSentence: () -> Unit,
    onGenerateArt: (SavedWord) -> Unit,
    onSpeak: (String) -> Unit,
    bottomPadding: Dp = Spacing.lg
) {
    val c = LocalCustomColors.current
    val currentWord = uiState.selectedWord
    var speechNotice by remember { mutableStateOf<String?>(null) }

    val recognizer = rememberSpeechRecognizer { result ->
        when (result) {
            is SpeechResult.Transcript -> {
                speechNotice = null
                val current = uiState.userSentenceInput.trim()
                val next = if (current.isEmpty()) result.text else "$current ${result.text}"
                onSentenceChange(next)
            }
            SpeechResult.NoSpeech -> {
                speechNotice = "Chưa nghe được giọng nói. Hãy nói to và rõ câu tiếng Anh rồi thử lại."
            }
            SpeechResult.PermissionDenied -> {
                speechNotice = "Chưa có quyền micro / nhận dạng giọng nói. Hãy bật quyền trong Cài đặt hệ thống."
            }
            is SpeechResult.Unavailable -> {
                speechNotice = result.reason
            }
            is SpeechResult.Failed -> {
                speechNotice = result.message
            }
        }
    }

    if (currentWord == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                message = "Chưa có từ nào để luyện ứng dụng",
                subMessage = "Hãy lưu vài từ khi đọc truyện tiếng Anh, rồi quay lại đây.",
                icon = TruyenCloudIcons.Wand
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imeAboveTabBar()
            .verticalScroll(rememberScrollState())
            .padding(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        RealLifeWordPickerRibbon(
            allWords = uiState.allWords,
            currentWord = currentWord,
            onSelectWord = onSelectWord
        )

        Column(
            modifier = Modifier.padding(horizontal = Spacing.pageH),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl)
        ) {
            RealLifeActiveWordBanner(
                currentWord = currentWord,
                generatingArtWords = uiState.generatingArtWords,
                onSpeak = onSpeak,
                onGenerateArt = onGenerateArt
            )

            ScenariosSection(uiState, currentWord, onSelectWord, onSpeak)

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader("Tự đặt câu")
                    TonalButton(
                        text = if (recognizer.isListening) "Đang nghe..." else "Nói câu",
                        onClick = {
                            if (recognizer.isListening) {
                                recognizer.stop()
                            } else {
                                speechNotice = null
                                recognizer.start()
                            }
                        },
                        leadingIcon = if (recognizer.isListening) TruyenCloudIcons.Stop else TruyenCloudIcons.Mic
                    )
                }
                Text(
                    text = "Viết hoặc bấm micro để nói một câu tiếng Anh dùng từ '${currentWord.word}' trong công việc hoặc cuộc sống của bạn. " +
                        "AI sẽ chỉ ra lỗi và gợi ý cách nói tự nhiên hơn.",
                    style = AppText.secondary,
                    color = c.textSecondary
                )
                VocabularyTextInput(
                    value = uiState.userSentenceInput,
                    onValueChange = onSentenceChange,
                    placeholder = "Ví dụ: Meeting my business partner was a pure ${currentWord.word}...",
                    singleLine = false,
                    minHeight = 96.dp
                )
                speechNotice?.let {
                    NoticeBanner(text = it, tone = NoticeTone.Warning)
                }
                uiState.evaluationError?.let {
                    NoticeBanner(text = it, tone = NoticeTone.Error)
                }
                PrimaryButton(
                    text = "Góp ý và sửa lỗi",
                    onClick = onSubmitSentence,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.userSentenceInput.isNotBlank() && !uiState.isEvaluatingSentence,
                    isLoading = uiState.isEvaluatingSentence
                )
                uiState.sentenceEvaluation?.let { evaluation ->
                    EvaluationResultCard(evaluation = evaluation, onSpeak = { onSpeak(evaluation.nativeVersion) })
                }
            }

            if (uiState.practicedSentences.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SectionLabel("Câu đã luyện (${uiState.practicedSentences.size})")
                    uiState.practicedSentences.take(3).forEach { past -> PastSentenceCard(item = past) }
                }
            }
        }
    }
}

/** Hai tình huống AI dựng cho từ đang chọn (công sở, đời thường) cùng lời khuyên sắc thái. */
@Composable
private fun ScenariosSection(
    uiState: VocabularyUiState,
    currentWord: SavedWord,
    onSelectWord: (SavedWord) -> Unit,
    onSpeak: (String) -> Unit
) {
    val c = LocalCustomColors.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader("Tình huống ứng dụng")

        if (uiState.isLoadingScenarios) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.lg),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.md))
                Text(text = "AI đang dựng tình huống công sở và đời sống...", style = AppText.secondary, color = c.textSecondary)
            }
            return@Column
        }

        uiState.scenariosError?.let { message ->
            NoticeBanner(
                text = message,
                tone = NoticeTone.Error,
                actionLabel = "Thử lại",
                onAction = { onSelectWord(currentWord) }
            )
        }

        val scenarios = uiState.scenarios ?: return@Column
        ScenarioCard(
            badge = "Công sở và email chuyên nghiệp",
            title = scenarios.workplaceScenario.title,
            english = scenarios.workplaceScenario.dialogueOrSentence,
            meaning = scenarios.workplaceScenario.meaningVi,
            tip = scenarios.workplaceScenario.usageTip,
            onSpeak = { onSpeak(scenarios.workplaceScenario.dialogueOrSentence) }
        )
        ScenarioCard(
            badge = "Giao tiếp đời thường",
            title = scenarios.casualScenario.title,
            english = scenarios.casualScenario.dialogueOrSentence,
            meaning = scenarios.casualScenario.meaningVi,
            tip = scenarios.casualScenario.usageTip,
            onSpeak = { onSpeak(scenarios.casualScenario.dialogueOrSentence) }
        )
        if (scenarios.nativeTip.isNotBlank()) {
            FlatCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionLabel("Lời khuyên về sắc thái")
                    Text(text = scenarios.nativeTip, style = AppText.secondary, color = c.textPrimary)
                }
            }
        }
    }
}
