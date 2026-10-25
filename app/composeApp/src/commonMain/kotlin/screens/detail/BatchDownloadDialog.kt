package com.truyencloud.compose.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.components.IosSheet
import com.truyencloud.compose.components.NoticeBanner
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.SecondaryButton
import com.truyencloud.compose.components.SegmentedControl
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.components.TonalButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

private val PresetCounts = listOf(10, 20, 50, 100, -1)

/**
 * Tờ trượt "Tải & Dịch ngầm" (IosSheet): chọn số chương tính từ chương bắt đầu, hoặc bật dịch ngầm cả bộ truyện trên máy chủ.
 * Tiến độ và thông báo của tác vụ nền hiện ngay trong tờ này nên đóng ra mở lại vẫn thấy.
 */
@Composable
fun BatchDownloadDialog(
    novelTitle: String,
    currentChapter: Int,
    totalChapters: Int,
    onStartBatch: (fromChapter: Int, toChapter: Int) -> Unit,
    onStartOvernight: (fromChapter: Int, toChapter: Int) -> Unit = { _, _ -> },
    onStopOvernight: () -> Unit = {},
    onSyncAllServerTranslated: () -> Unit = {},
    onExportEpub: () -> Unit = {},
    studioState: DetailStudioUiState? = null,
    onOpenVoicePicker: () -> Unit = {},
    onPrerenderAudio: (Int, Int) -> Unit = { _, _ -> },
    serverTranslatedCount: Int = 0,
    downloadedCount: Int = 0,
    isOvernightEnabled: Boolean = false,
    onDismiss: () -> Unit,
    isQueuing: Boolean = false,
    successMessage: String? = null,
    batchProgress: Float? = null,
    /** Chưa đăng nhập: máy chủ chỉ dịch hàng loạt cho tài khoản đăng nhập, khách chỉ tải được bản có sẵn. */
    requiresLogin: Boolean = false,
    modifier: Modifier = Modifier
) {
    IosSheet(onDismissRequest = onDismiss, modifier = modifier, title = "Tải & Dịch ngầm") {
        BatchDownloadBody(
            novelTitle = novelTitle,
            currentChapter = currentChapter,
            totalChapters = totalChapters,
            onStartBatch = onStartBatch,
            onStartOvernight = onStartOvernight,
            onStopOvernight = onStopOvernight,
            onSyncAllServerTranslated = onSyncAllServerTranslated,
            onExportEpub = onExportEpub,
            studioState = studioState,
            onOpenVoicePicker = onOpenVoicePicker,
            onPrerenderAudio = onPrerenderAudio,
            serverTranslatedCount = serverTranslatedCount,
            downloadedCount = downloadedCount,
            isOvernightEnabled = isOvernightEnabled,
            onDismiss = onDismiss,
            isQueuing = isQueuing,
            successMessage = successMessage,
            batchProgress = batchProgress,
            requiresLogin = requiresLogin
        )
    }
}

/** Phần thân của tờ trượt (tách riêng để xem trước mà không cần cửa sổ ModalBottomSheet). */
@Composable
internal fun BatchDownloadBody(
    novelTitle: String,
    currentChapter: Int,
    totalChapters: Int,
    onStartBatch: (fromChapter: Int, toChapter: Int) -> Unit,
    onStartOvernight: (fromChapter: Int, toChapter: Int) -> Unit = { _, _ -> },
    onStopOvernight: () -> Unit = {},
    onSyncAllServerTranslated: () -> Unit = {},
    onExportEpub: () -> Unit = {},
    studioState: DetailStudioUiState? = null,
    onOpenVoicePicker: () -> Unit = {},
    onPrerenderAudio: (Int, Int) -> Unit = { _, _ -> },
    serverTranslatedCount: Int = 0,
    downloadedCount: Int = 0,
    isOvernightEnabled: Boolean = false,
    onDismiss: () -> Unit,
    isQueuing: Boolean,
    successMessage: String?,
    batchProgress: Float?,
    requiresLogin: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val initialStart = if (currentChapter > 0) currentChapter else 1
    val maxChapters = if (totalChapters > 0) totalChapters else (initialStart + 100)
    var startChapter by remember(currentChapter, totalChapters) {
        mutableStateOf(initialStart.coerceIn(1, maxChapters))
    }
    var selectedCount by remember { mutableStateOf(PresetCounts.first()) }

    val toChapter = if (selectedCount == -1) {
        minOf(maxChapters, startChapter + 99)
    } else {
        (startChapter + selectedCount - 1).coerceAtMost(maxChapters)
    }
    val overnightToChapter = if (selectedCount == -1) {
        maxChapters
    } else {
        maxChapters
    }
    val actualCount = (toChapter - startChapter + 1).coerceAtLeast(0)
    val epubReadyCount = maxOf(serverTranslatedCount, downloadedCount)
    val isBatchError = successMessage?.startsWith("Lỗi", ignoreCase = true) == true ||
        successMessage?.contains("thất bại", ignoreCase = true) == true ||
        successMessage?.contains("mất quá nhiều thời gian", ignoreCase = true) == true

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.pageH),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = novelTitle,
            style = AppText.bookTitleCompact,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = "Máy chủ dịch lần lượt từng chương, rồi tải về máy để bạn đọc khi không có mạng.",
            style = AppText.secondary,
            color = colors.textSecondary
        )

        if (requiresLogin) {
            NoticeBanner(
                text = "Đăng nhập để dịch hàng loạt trên máy chủ. Khi chưa đăng nhập, ứng dụng chỉ tải về máy những chương " +
                    "máy chủ đã có sẵn (có thể còn là tiếng Trung).",
                tone = NoticeTone.Warning
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Chương bắt đầu: Ch.$startChapter", style = AppText.secondaryStrong, color = colors.textPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip8(
                        label = "Từ đầu (Ch.1)",
                        selected = startChapter == 1,
                        onClick = { startChapter = 1 }
                    )
                    if (currentChapter > 1) {
                        FilterChip8(
                            label = "Ch. đang đọc ($currentChapter)",
                            selected = startChapter == currentChapter,
                            onClick = { startChapter = currentChapter.coerceIn(1, maxChapters) }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(-10 to "-10", -1 to "-1", 1 to "+1", 10 to "+10").forEach { (delta, label) ->
                    FilterChip8(
                        label = label,
                        selected = false,
                        onClick = { startChapter = (startChapter + delta).coerceIn(1, maxChapters) }
                    )
                }
            }

            Text(text = "Số chương muốn tải", style = AppText.secondaryStrong, color = colors.textSecondary)
            SegmentedControl(
                options = PresetCounts.map { if (it == -1) "Hết truyện" else "$it" },
                selectedIndex = PresetCounts.indexOf(selectedCount).coerceAtLeast(0),
                onSelect = { selectedCount = PresetCounts[it] }
            )
            Text(
                text = "Tải về máy: Ch.$startChapter → Ch.$toChapter ($actualCount chương)",
                style = AppText.body,
                color = colors.textPrimary
            )
        }

        if (studioState != null) {
            BatchTtsVoiceSection(
                studioState = studioState,
                fromChapter = startChapter,
                toChapter = toChapter,
                isQueuing = isQueuing,
                onOpenVoicePicker = onOpenVoicePicker,
                onPrerenderAudio = onPrerenderAudio
            )
        }

        BatchFeedback(message = successMessage, progress = batchProgress, isError = isBatchError)

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PrimaryButton(
                text = if (requiresLogin) "Tải bản có sẵn" else "Bắt đầu tải ($actualCount chương)",
                onClick = { onStartBatch(startChapter, toChapter) },
                isLoading = isQueuing,
                leadingIcon = TruyenCloudIcons.FileDownload,
                modifier = Modifier.fillMaxWidth()
            )
            if (isOvernightEnabled) {
                SecondaryButton(
                    text = "Tạm dừng Dịch Ngầm bộ này",
                    onClick = onStopOvernight,
                    enabled = !isQueuing,
                    leadingIcon = TruyenCloudIcons.MoonStar,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                TonalButton(
                    text = "Dịch ngầm cả bộ (Ch.$startChapter → $overnightToChapter)",
                    onClick = { onStartOvernight(startChapter, overnightToChapter) },
                    enabled = !isQueuing,
                    leadingIcon = TruyenCloudIcons.MoonStar,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (serverTranslatedCount > 0) {
                TonalButton(
                    text = "Kéo hết $serverTranslatedCount chương máy chủ đã dịch về máy",
                    onClick = onSyncAllServerTranslated,
                    enabled = !isQueuing,
                    leadingIcon = TruyenCloudIcons.CloudDownload,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (serverTranslatedCount > 0 || downloadedCount > 0) {
                SecondaryButton(
                    text = "Xuất sách điện tử EPUB ($epubReadyCount chương)",
                    onClick = onExportEpub,
                    enabled = !isQueuing,
                    leadingIcon = TruyenCloudIcons.BookDown,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            TextActionButton(text = "Đóng", onClick = onDismiss)
        }
    }
}
