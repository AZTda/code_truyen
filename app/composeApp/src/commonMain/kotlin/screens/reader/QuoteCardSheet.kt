package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont
import com.truyencloud.compose.theme.Spacing

/**
 * Thẻ trích dẫn: đoạn văn đã chọn (serif, vì là chữ của truyện), tên truyện, tác giả và chương, trên một thẻ phẳng màu thẻ.
 * Nút "Sao chép trích dẫn" chép cả nguồn trích vào bộ nhớ tạm.
 */
@Composable
fun QuoteCardSheet(
    quoteText: String,
    novelTitle: String,
    author: String,
    chapterTitle: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var isCopied by remember { mutableStateOf(false) }

    ReaderSheet(onDismiss = onDismiss, title = "Thẻ trích dẫn", modifier = modifier) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ReaderSheetInset)
        ) {
            FlatCard(
                modifier = Modifier.fillMaxWidth(),
                outlined = true,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "“",
                        style = AppText.largeTitle.copy(fontFamily = ReaderFont, fontSize = 48.sp, lineHeight = 40.sp),
                        color = colors.textTertiary,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Text(
                        text = quoteText,
                        style = AppText.body.copy(fontFamily = ReaderFont, fontSize = 17.sp, lineHeight = 28.sp),
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(modifier = Modifier.width(48.dp).height(1.dp).background(colors.hairline))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = novelTitle, style = AppText.bookTitle, color = colors.textPrimary, textAlign = TextAlign.Center)
                    Text(
                        text = "$author · $chapterTitle",
                        style = AppText.author,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "TruyenCloud", style = AppText.caption, color = colors.textTertiary)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ReaderSheetInset, vertical = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TonalButton(
                text = if (isCopied) "Đã sao chép" else "Sao chép",
                onClick = {
                    val fullText = "“$quoteText”\n— $novelTitle ($author)"
                    clipboardManager.setText(AnnotatedString(fullText))
                    isCopied = true
                },
                modifier = Modifier.weight(1f),
                leadingIcon = if (isCopied) TruyenCloudIcons.CheckCircle else null
            )
            PrimaryButton(text = "Xong", onClick = onDismiss, modifier = Modifier.weight(1f))
        }
    }
}
