package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.TonalButton

/** Nút cuối bài: "Nộp bài" (khi đã trả lời đủ) hoặc "Làm lại" + "Hoàn thành" sau khi nộp. */
@Composable
internal fun ComprehensionActionBar(
    isSubmitted: Boolean,
    answeredAll: Boolean,
    questionCount: Int,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isSubmitted) {
        PrimaryButton(
            text = if (answeredAll) "Nộp bài đọc hiểu" else "Trả lời đủ $questionCount câu để nộp bài",
            onClick = onSubmit,
            modifier = modifier.fillMaxWidth(),
            enabled = answeredAll
        )
    } else {
        Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TonalButton(text = "Làm lại", onClick = onRetry, modifier = Modifier.weight(1f))
            PrimaryButton(text = "Hoàn thành", onClick = onDismiss, modifier = Modifier.weight(1f))
        }
    }
}
