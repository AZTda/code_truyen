package com.truyencloud.compose.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.EmptyState
import com.truyencloud.compose.components.IosSheet
import com.truyencloud.compose.components.NoticeBanner
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.api.FriendlyErrors
import com.truyencloud.shared.repo.ChapterRepository
import com.truyencloud.shared.repo.NovelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class ClearMessage(val text: String, val tone: NoticeTone)

/**
 * Tờ trượt danh sách truyện đã tải offline kèm dung lượng.
 * Cho phép xoá từng truyện hoặc toàn bộ — luôn qua hộp thoại xác nhận.
 */
@Composable
fun StorageManagerSheet(
    isOpen: Boolean,
    chapterRepository: ChapterRepository?,
    novelRepository: NovelRepository?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Xoá + VACUUM + đo dung lượng thật trả lại. Null thì chỉ xoá nội dung (không thu nhỏ tệp). */
    cleaner: OfflineStorageCleaner? = null
) {
    if (!isOpen || chapterRepository == null) return

    val colors = LocalCustomColors.current
    val scope = rememberCoroutineScope()
    val storageCleaner = remember(chapterRepository, cleaner) { cleaner ?: OfflineStorageCleaner(chapterRepository, null) }

    var totalBytes by remember { mutableStateOf(0L) }
    var cachedNovels by remember { mutableStateOf<List<CachedNovelInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isClearing by remember { mutableStateOf<String?>(null) }
    var clearMessage by remember { mutableStateOf<ClearMessage?>(null) }
    var pendingConfirm by remember { mutableStateOf<StorageConfirm?>(null) }

    suspend fun reload() {
        withContext(Dispatchers.Default) {
            totalBytes = chapterRepository.estimateCacheBytes()
            val summaries = chapterRepository.getCachedNovelSummaries()
            cachedNovels = summaries.map { s ->
                val novel = try { novelRepository?.getNovelById(s.novelId) } catch (_: Exception) { null }
                CachedNovelInfo(
                    novelId = s.novelId,
                    title = novel?.titleVi?.ifBlank { novel.title } ?: s.novelId,
                    cachedChapters = s.cachedCount,
                    sizeBytes = chapterRepository.estimateCacheBytesForNovel(s.novelId)
                )
            }
        }
    }

    LaunchedEffect(isOpen) {
        if (isOpen) {
            isLoading = true
            reload()
            isLoading = false
        }
    }

    IosSheet(onDismissRequest = onDismiss, modifier = modifier, title = "Quản lý bộ nhớ offline") {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Tổng: ${formatStorageBytes(totalBytes)}",
                    style = AppText.body,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                if (cachedNovels.isNotEmpty()) {
                    TextActionButton(
                        text = "Xoá hết",
                        destructive = true,
                        onClick = {
                            pendingConfirm = StorageConfirm(
                                scope = ClearScope.ALL,
                                chapterCount = cachedNovels.sumOf { it.cachedChapters },
                                bytes = totalBytes
                            )
                        }
                    )
                }
            }

            clearMessage?.let {
                NoticeBanner(
                    text = it.text,
                    tone = it.tone,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            when {
                isLoading -> Box(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center
                ) { RowSpinner() }

                cachedNovels.isEmpty() -> EmptyState(
                    message = "Chưa tải truyện nào về thiết bị",
                    icon = TruyenCloudIcons.CloudDone
                )

                else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(cachedNovels, key = { it.novelId }) { info ->
                        CachedNovelRow(
                            title = info.title,
                            cachedChapters = info.cachedChapters,
                            sizeBytes = info.sizeBytes,
                            isClearing = isClearing == info.novelId,
                            onClear = {
                                pendingConfirm = StorageConfirm(
                                    scope = ClearScope.NOVEL,
                                    novelId = info.novelId,
                                    novelTitle = info.title,
                                    chapterCount = info.cachedChapters,
                                    bytes = info.sizeBytes
                                )
                            }
                        )
                    }
                }
            }

            TextActionButton(
                text = "Đóng",
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }

    ClearStorageConfirmDialog(
        confirm = pendingConfirm,
        onDismiss = { pendingConfirm = null },
        onConfirm = {
            val confirm = pendingConfirm ?: return@ClearStorageConfirmDialog
            pendingConfirm = null
            scope.launch {
                isClearing = confirm.novelId ?: "__all__"
                try {
                    val result = withContext(Dispatchers.Default) {
                        if (confirm.scope == ClearScope.ALL) storageCleaner.clearAll()
                        else storageCleaner.clearNovel(confirm.novelId.orEmpty())
                    }
                    reload()
                    clearMessage = ClearMessage(result.message(confirm.novelTitle), NoticeTone.Success)
                } catch (e: Exception) {
                    clearMessage = ClearMessage("Không xoá được: ${FriendlyErrors.of(e)}", NoticeTone.Error)
                } finally {
                    isClearing = null
                }
            }
        }
    )
}
