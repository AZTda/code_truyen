package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.data.Chapter
import com.truyencloud.shared.data.ChapterFlags
import com.truyencloud.shared.reader.ReaderToc
import com.truyencloud.shared.reader.TocJump
import kotlinx.coroutines.delay

/**
 * Mục lục nhanh cho truyện hàng nghìn chương: tìm theo tên, nhảy tới số chương, xếp tăng/giảm dần, tự cuộn tới chương đang
 * đọc, khoá mục theo số chương. Dùng danh sách chương NHẸ (không chứa nội dung) nên mở tức thì; huy hiệu "đã dịch / bản tạm"
 * lấy từ [flags].
 */
@Composable
fun QuickTocSheet(
    chapters: List<Chapter>,
    currentChapterNumber: Int,
    onSelectChapter: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Chương cuối đã biết (0 = chưa biết): chặn nhảy vượt quá. */
    lastChapterNumber: Int = 0,
    flags: Map<Int, ChapterFlags> = emptyMap(),
    serverTranslated: Set<Int> = emptySet()
) {
    val colors = LocalCustomColors.current
    val listState = rememberLazyListState()
    var query by remember { mutableStateOf("") }
    var appliedQuery by remember { mutableStateOf("") }
    var descending by remember { mutableStateOf(false) }
    var onlyTranslated by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("") }
    var jumpError by remember { mutableStateOf<String?>(null) }

    // Gõ tới đâu lọc tới đó nhưng trễ một nhịp ngắn để 5000 dòng không bị lọc lại sau mỗi phím
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(150L)
        appliedQuery = query
    }

    val entries = remember(chapters) { ReaderToc.index(chapters) }
    val translatedCount = remember(chapters, flags, serverTranslated) {
        chapters.count { ch ->
            flags[ch.chapterNumber]?.hasTranslation == true || ch.chapterNumber in serverTranslated
        }
    }
    val visible = remember(entries, appliedQuery, descending, onlyTranslated, flags, serverTranslated) {
        val base = ReaderToc.filter(entries, appliedQuery, descending)
        if (!onlyTranslated) base
        else base.filter { ch ->
            flags[ch.chapterNumber]?.hasTranslation == true || ch.chapterNumber in serverTranslated
        }
    }
    val lastKnown = remember(chapters, lastChapterNumber) {
        maxOf(lastChapterNumber, chapters.maxOfOrNull { it.chapterNumber } ?: 0)
    }

    // Mở mục lục / đổi thứ tự / xoá ô tìm: cuộn tới chương đang đọc. Đang tìm thì về đầu kết quả.
    LaunchedEffect(currentChapterNumber, descending, onlyTranslated, appliedQuery.isBlank(), visible.size) {
        if (appliedQuery.isNotBlank()) {
            listState.scrollToItem(0)
            return@LaunchedEffect
        }
        val position = ReaderToc.positionOf(visible, currentChapterNumber)
        if (position >= 0) listState.scrollToItem((position - 2).coerceAtLeast(0))
    }

    fun submitJump() {
        when (val jump = ReaderToc.parseJump(jumpText, lastKnown)) {
            is TocJump.Go -> {
                jumpError = null
                onSelectChapter(jump.chapterNumber)
                onDismiss()
            }
            is TocJump.OutOfRange -> jumpError = "Truyện hiện có tới chương ${jump.lastChapter}."
            TocJump.Invalid -> jumpError = "Nhập số chương hợp lệ (từ 1)."
        }
    }

    ReaderSheet(
        onDismiss = onDismiss,
        title = "Mục lục",
        subtitle = if (appliedQuery.isBlank() && !onlyTranslated) {
            if (translatedCount > 0) "Tổng số ${chapters.size} chương • Đã dịch $translatedCount chương"
            else "Tổng số ${chapters.size} chương"
        } else "${visible.size}/${chapters.size} chương",
        modifier = modifier.fillMaxHeight(0.72f),
        withIme = true
    ) {
        QuickTocToolbar(
            query = query,
            onQueryChange = { query = it },
            descending = descending,
            onToggleOrder = { descending = !descending },
            jumpText = jumpText,
            onJumpTextChange = {
                jumpText = it.filter(Char::isDigit).take(7)
                jumpError = null
            },
            jumpPlaceholder = if (lastKnown > 0) "Nhảy tới chương số (1–$lastKnown)" else "Nhảy tới chương số",
            jumpError = jumpError,
            onSubmitJump = ::submitJump,
            onlyTranslated = onlyTranslated,
            translatedCount = translatedCount,
            onToggleTranslated = { onlyTranslated = !onlyTranslated }
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (visible.isEmpty()) {
            Text(
                text = if (onlyTranslated && appliedQuery.isBlank()) "Chưa có chương nào được dịch sẵn."
                else if (appliedQuery.isBlank()) "Chưa có mục lục."
                else "Không có chương nào khớp “$appliedQuery”.",
                style = AppText.secondary,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = ReaderSheetInset, vertical = 12.dp)
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(visible, key = { it.chapterNumber }) { ch ->
                QuickTocRow(
                    chapter = ch,
                    isCurrent = ch.chapterNumber == currentChapterNumber,
                    flags = flags[ch.chapterNumber],
                    isServerTranslated = ch.chapterNumber in serverTranslated,
                    onClick = {
                        onSelectChapter(ch.chapterNumber)
                        onDismiss()
                    }
                )
            }
        }
    }
}
