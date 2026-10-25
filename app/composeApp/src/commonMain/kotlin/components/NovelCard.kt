package com.truyencloud.compose.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.BookCoverShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.displayAuthor
import com.truyencloud.shared.data.displayTitle

/**
 * Thẻ truyện dùng ở Tủ sách, Khám phá và kết quả tìm kiếm: bìa 96:136, thanh tiến độ 3dp ngay dưới bìa, tên truyện
 * serif SemiBold 15/20 (tối đa 2 dòng), tác giả 12sp, rồi một dòng chú thích nhỏ (nguồn hoặc [footnote]).
 *
 * Chiều rộng mặc định [Spacing.coverWidth] (96dp); truyền `Modifier.fillMaxWidth()` để thẻ lấp đầy ô lưới, hoặc
 * `Modifier.width(..)` cho hàng cuộn ngang. Bìa luôn giữ tỉ lệ 96:136 theo chiều rộng đó.
 *
 * @param onLongClick nhấn giữ (Tủ sách: chọn + mở tờ hành động). Null = không có nhấn giữ.
 * @param selectionMode đang ở chế độ chọn nhiều: hiện vòng chọn ở góc bìa.
 * @param selected thẻ này đang được chọn.
 * @param inLibrary truyện đã có trong tủ sách (Khám phá / tìm kiếm): nhãn "Trong tủ" ở chân bìa.
 * @param showFavorite hiện trái tim nhỏ nếu [Novel.isFavorite] (chỉ có nghĩa với truyện trong tủ).
 * @param footnote dòng chú thích thay cho tên nguồn, vd. "Còn 12 chương".
 */
@Composable
fun NovelCard(
    novel: Novel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    inLibrary: Boolean = false,
    showFavorite: Boolean = false,
    overnightEnabled: Boolean = false,
    translatedChapters: Int = 0,
    footnote: String? = null
) {
    val colors = LocalCustomColors.current

    Column(
        modifier = modifier
            .width(Spacing.coverWidth)
            .pressAndHold(
                targetScale = if (selectionMode) 0.98f else 0.97f,
                onLongClickLabel = "Mở menu tủ sách",
                onLongClick = onLongClick,
                onClick = onClick
            )
            // Đọc màn hình (TalkBack / VoiceOver) cần biết thẻ nào đang được chọn
            .then(if (selectionMode) Modifier.semantics { this.selected = selected } else Modifier)
    ) {
        NovelCoverImage(
            novel = novel,
            textBottomInset = if (inLibrary) 20.dp else 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(COVER_RATIO)
                .then(if (selected) Modifier.border(2.dp, colors.accent, BookCoverShape) else Modifier)
        ) {
            if (inLibrary) {
                InLibraryBadge(modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
            }
            if (showFavorite && novel.isFavorite && !selectionMode) {
                CoverIconBadge(
                    icon = TruyenCloudIcons.HeartFilled,
                    description = "Yêu thích",
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                )
            }
            if (overnightEnabled && !selectionMode) {
                CoverIconBadge(
                    icon = TruyenCloudIcons.MoonStar,
                    description = "Đang dịch ngầm ban đêm",
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                )
            }
            if ((translatedChapters > 0 || overnightEnabled) && !selectionMode) {
                TranslatedCoverBadge(
                    translatedChapters = translatedChapters,
                    overnightEnabled = overnightEnabled,
                    modifier = Modifier
                        .align(if (inLibrary) Alignment.BottomEnd else Alignment.BottomStart)
                        .padding(6.dp)
                )
            }
            if (selectionMode) {
                SelectionMark(selected = selected, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
            }
        }

        val progress = progressOf(novel)
        if (progress != null) {
            Spacer(modifier = Modifier.height(6.dp))
            ProgressBar3(progress = progress)
        }

        // Thẻ chỉ rộng ~100dp: giới hạn phóng chữ để tên truyện 2 dòng không bị cắt quá nhiều
        CappedFontScale(max = 1.2f) {
            Column {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = novel.displayTitle,
                    style = AppText.bookTitle,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = novel.displayAuthor,
                    style = AppText.author,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
                val translatedLabel = if (translatedChapters > 0) {
                    val total = novel.totalChapters.takeIf { it > 0 }
                    if (total != null) {
                        val pct = ((translatedChapters * 100) / total).coerceIn(0, 100)
                        if (overnightEnabled) "Dịch ngầm $translatedChapters/$total ($pct%)"
                        else "Đã dịch $translatedChapters/$total ch."
                    } else "Đã dịch $translatedChapters ch."
                } else if (overnightEnabled) {
                    "Đang dịch ngầm"
                } else null
                val caption = footnote ?: translatedLabel ?: NovelSources.label(novel.sourceId)
                if (caption != null) {
                    Text(
                        text = caption,
                        style = AppText.captionRegular,
                        color = if (footnote == null && translatedLabel != null) colors.accent else colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Tỉ lệ rộng / cao của bìa chuẩn 96x136dp. */
internal const val COVER_RATIO = 96f / 136f

/** Tiến độ 0..1 nếu đã đọc và biết tổng số chương; null thì không hiện thanh. */
internal fun progressOf(novel: Novel): Float? =
    if (novel.lastReadChapter > 0 && novel.totalChapters > 0) {
        (novel.lastReadChapter.toFloat() / novel.totalChapters.toFloat()).coerceIn(0f, 1f)
    } else null
