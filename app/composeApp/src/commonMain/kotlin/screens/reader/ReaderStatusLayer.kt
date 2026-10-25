package com.truyencloud.compose.screens.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.NoticeTone
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.reader.ProvisionalTranslation

/**
 * Lớp trạng thái phủ trên cùng của màn đọc — chỉ hiện khi VẪN có chữ để đọc (chưa có chữ thì [ReaderBody] đã hiện thẻ
 * tiến trình / thẻ lỗi ở giữa màn hình):
 *  - thanh tiến trình mảnh + chip "Đang tải chương N…" khi đang chuyển chương;
 *  - lỗi tải chương: banner [Thử lại] [Mục lục] [Đóng] ngay trên chương cũ;
 *  - đang dịch / dịch lại: banner không chặn có nút Hủy;
 *  - lỗi dịch: banner [Thử lại] [Đọc bản gốc];
 *  - bản dịch máy tạm: banner "Dịch lại bằng AI" (khách chưa đăng nhập: chỉ lời nhắc đăng nhập, không có nút — máy chủ không dịch cho khách).
 */
@Composable
internal fun ReaderStatusLayer(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val hasText = state.paragraphs.isNotEmpty()
    if (!hasText) return
    val chapterNumber = state.currentChapter?.chapterNumber
    val translatingCurrent = state.isTranslating &&
        (state.translatingChapterNumber == null || state.translatingChapterNumber == chapterNumber)
    val loadingOverText = state.isLoadingChapter

    Box(modifier = modifier.fillMaxWidth()) {
        if (loadingOverText) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter),
                color = colors.accent,
                trackColor = Color.Transparent
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                // Thanh trên đang hiện thì banner nằm dưới nó
                .padding(top = if (state.showBars) Spacing.topBarHeight + Spacing.md else Spacing.sm, start = Spacing.md, end = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (loadingOverText) {
                ReaderLoadingChip(target = state.targetChapterNumber, onCancel = viewModel::cancelChapterLoad)
            }
            state.chapterError?.let { error ->
                ReaderBannerCard(
                    message = error,
                    tone = NoticeTone.Error,
                    actions = listOf(
                        BannerAction("Thử lại", primary = true, onClick = viewModel::retryLoadChapter),
                        BannerAction("Mục lục", onClick = viewModel::toggleQuickToc),
                        BannerAction("Đóng", onClick = viewModel::dismissChapterError)
                    )
                )
            }
            if (translatingCurrent) {
                ReaderTranslatingBanner(chapterNumber = state.translatingChapterNumber ?: chapterNumber, onCancel = viewModel::cancelTranslation)
            } else {
                state.translationError?.let { error ->
                    val loginRequired = state.translationErrorKind.isGuestBlocked()
                    ReaderBannerCard(
                        message = error,
                        tone = NoticeTone.Error,
                        actions = buildList {
                            if (!loginRequired) add(BannerAction("Thử lại", primary = true, onClick = viewModel::retryLoadChapter))
                            if (!state.showOriginal) add(BannerAction("Đọc bản gốc", primary = loginRequired, onClick = viewModel::readOriginal))
                            add(BannerAction("Đóng", onClick = viewModel::dismissTranslationError))
                        }
                    )
                }
                if (state.isProvisionalTranslation && !state.showOriginal && state.translationError == null) {
                    val loggedIn = TruyenCloudApi.authToken.isNotBlank()
                    ReaderBannerCard(
                        message = ProvisionalTranslation.bannerMessage(loggedIn),
                        tone = NoticeTone.Info,
                        actions = if (loggedIn) {
                            listOf(BannerAction(ProvisionalTranslation.UPGRADE_ACTION, primary = true, onClick = viewModel::upgradeProvisionalTranslation))
                        } else {
                            emptyList()
                        }
                    )
                }
            }
        }
    }
}
