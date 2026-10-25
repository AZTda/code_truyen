package com.truyencloud.compose.screens.practice

import androidx.compose.animation.core.EaseInOutQuad
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.PillShape
import com.truyencloud.compose.theme.springPress

/**
 * Nút micro + trạng thái thu âm. KHÔNG còn ô "gõ/dán phiên âm" như bản cũ: gõ chữ để lấy điểm phát âm là gian lận
 * (gõ chính từ đích là được điểm tuyệt đối). Điểm chỉ đến từ bản chép lời thật của bộ nhận dạng giọng nói.
 */
@Composable
fun PronunciationRecorderView(
    isListening: Boolean,
    isBusy: Boolean,
    onToggleRecording: () -> Unit,
    transcript: String,
    modifier: Modifier = Modifier
) {
    val c = LocalCustomColors.current

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.size(100.dp), contentAlignment = Alignment.Center) {
            if (isListening) ListeningPulse()

            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(PillShape)
                    .background(if (isListening) c.error else c.accent)
                    .springPress(role = Role.Button) { if (!isBusy) onToggleRecording() },
                contentAlignment = Alignment.Center
            ) {
                if (isBusy) {
                    CircularProgressIndicator(color = c.onAccent, modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                } else {
                    Icon(
                        imageVector = TruyenCloudIcons.Mic,
                        contentDescription = if (isListening) "Dừng thu âm" else "Bắt đầu nói",
                        tint = c.onAccent,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        Text(
            text = when {
                isBusy -> "AI đang chấm điểm cách bạn phát âm..."
                isListening -> "Đang lắng nghe. Nói từ trên, bấm lần nữa để dừng."
                else -> "Bấm micro rồi đọc to từ trên"
            },
            style = AppText.secondaryStrong,
            color = if (isListening) c.error else c.textSecondary,
            textAlign = TextAlign.Center
        )

        if (transcript.isNotBlank()) {
            Text(
                text = "Máy nghe thấy: “$transcript”",
                style = AppText.secondary,
                color = c.textPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(InputShape)
                    .background(c.cardHigh)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}

/** Vòng mờ nhịp thở quanh nút micro khi đang nghe (chỉ chạy trong lúc nghe nên không tốn animation khi rảnh). */
@Composable
private fun ListeningPulse() {
    val c = LocalCustomColors.current
    val transition = rememberInfiniteTransition(label = "listening")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(1000, easing = EaseInOutQuad), RepeatMode.Reverse),
        label = "pulse"
    )
    Box(
        modifier = Modifier
            .size(80.dp)
            .scale(pulse)
            .clip(PillShape)
            .background(c.errorSoft)
    )
}
