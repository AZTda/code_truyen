package com.truyencloud.compose.screens.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.truyencloud.compose.components.IosSheet
import com.truyencloud.compose.components.NoticeBanner
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.platform.SpeechResult
import com.truyencloud.compose.platform.rememberSpeechRecognizer
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.api.EnglishApi
import com.truyencloud.shared.api.PronunciationEvaluateResponse
import kotlinx.coroutines.launch

/**
 * Phòng luyện phát âm, mở dưới dạng tờ trượt từ dưới lên (trước đây là một khối dính ở cuối màn hình).
 * Điểm chỉ đến từ bản chép lời THẬT của bộ nhận dạng giọng nói; không nghe được thì nói rõ lý do và không hiện điểm.
 */
@Composable
fun PronunciationSheet(
    word: String,
    ipa: String,
    meaningVi: String,
    englishApi: EnglishApi?,
    onSpeakWord: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Gọi khi có điểm THẬT (0..100); không bao giờ gọi khi không nghe được gì. */
    onScored: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val c = LocalCustomColors.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var isScoring by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var evaluation by remember { mutableStateOf<PronunciationEvaluateResponse?>(null) }
    var score by remember { mutableStateOf<Int?>(null) }
    // Lý do KHÔNG có điểm (không nghe được, bị từ chối quyền, lỗi mạng...). Có lý do thì tuyệt đối không hiện điểm.
    var notice by remember { mutableStateOf<String?>(null) }

    fun scoreTranscript(text: String) {
        val api = englishApi
        if (api == null) {
            notice = "Chưa kết nối được máy chủ chấm điểm."
            return
        }
        coroutineScope.launch {
            isScoring = true
            try {
                val res = api.evaluatePronunciation(word = word, targetIpa = ipa, spokenText = text)
                val real = res.realScore
                if (res.isFallback || real == null) {
                    evaluation = null
                    score = null
                    notice = res.message.ifBlank { "Chưa nghe được giọng nói" }
                } else {
                    evaluation = res
                    score = real
                    notice = null
                    onScored(real)
                }
            } catch (e: Exception) {
                evaluation = null
                score = null
                notice = "Không chấm được điểm: ${e.message ?: "lỗi kết nối máy chủ"}"
            }
            isScoring = false
        }
    }

    val recognizer = rememberSpeechRecognizer { result ->
        when (result) {
            is SpeechResult.Transcript -> {
                transcript = result.text
                scoreTranscript(result.text)
            }
            SpeechResult.NoSpeech -> {
                evaluation = null; score = null
                notice = "Chưa nghe được giọng nói. Hãy nói to, rõ và gần micro hơn rồi thử lại."
            }
            SpeechResult.PermissionDenied -> {
                evaluation = null; score = null
                notice = "Ứng dụng chưa được cấp quyền micro / nhận dạng giọng nói. Hãy bật quyền trong Cài đặt hệ thống rồi thử lại."
            }
            is SpeechResult.Unavailable -> {
                evaluation = null; score = null
                notice = result.reason
            }
            is SpeechResult.Failed -> {
                evaluation = null; score = null
                notice = result.message
            }
        }
    }

    IosSheet(onDismissRequest = onDismiss, modifier = modifier, title = "Luyện phát âm") {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(scrollState)
                .padding(horizontal = Spacing.pageH),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            Text(
                text = "Nói to và rõ vào micro. Điểm tính từ những gì máy nghe được.",
                style = AppText.secondary,
                color = c.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            PronunciationTargetCard(
                word = word,
                ipa = ipa,
                meaningVi = meaningVi,
                onSpeakWord = onSpeakWord
            )

            // Micro: điểm chỉ đến từ bản chép lời thật của bộ nhận dạng giọng nói (không có ô gõ chữ để lấy điểm)
            PronunciationRecorderView(
                isListening = recognizer.isListening,
                isBusy = isScoring,
                onToggleRecording = {
                    if (recognizer.isListening) {
                        recognizer.stop()
                    } else {
                        evaluation = null; score = null; notice = null; transcript = ""
                        recognizer.start()
                    }
                },
                transcript = transcript
            )

            // Không có điểm: nói rõ lý do thay vì hiện điểm giả
            val reason = notice
            if (reason != null && !isScoring && !recognizer.isListening) {
                NoticeBanner(text = reason, tone = NoticeTone.Warning)
            }

            val eval = evaluation
            val realScore = score
            if (eval != null && realScore != null && !isScoring) {
                PronunciationFeedbackCard(evaluation = eval, score = realScore)
            }

            TextActionButton(
                text = "Đóng",
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}
