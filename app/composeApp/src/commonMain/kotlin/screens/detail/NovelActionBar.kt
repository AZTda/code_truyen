package com.truyencloud.compose.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.CappedFontScale
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons

/**
 * Hành động chính của trang truyện. Một nút chính "Đọc tiếp · Chương 12" / "Bắt đầu đọc" chiếm cả hàng; dưới nó là hàng
 * hành động phụ có nhãn: thêm vào tủ, yêu thích, nghe, tải về.
 *
 * [lastReadChapter] lấy từ tiến độ THẬT trong Room (xem NovelDetailViewModel) nên nhãn luôn đúng sau khi đọc xong rồi quay
 * lại. Hai ô tủ sách / yêu thích chỉ hiện khi [libraryLoaded] (Room đã trả lời) để nhãn không nhấp nháy từ "Thêm vào tủ"
 * sang "Trong tủ"; chỗ của chúng được giữ nên hai ô nghe và tải không bị xô lệch.
 */
@Composable
fun NovelActionBar(
    lastReadChapter: Int = 0,
    hasChapters: Boolean = true,
    onStartReading: () -> Unit,
    onStartTts: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    libraryLoaded: Boolean = false,
    isInLibrary: Boolean = false,
    isFavorite: Boolean = false,
    shelfName: String = "reading",
    onAddToLibrary: () -> Unit = {},
    onRemoveFromLibrary: () -> Unit = {},
    onToggleFavorite: () -> Unit = {}
) {
    val readLabel = when {
        !hasChapters -> "Chưa có chương"
        lastReadChapter > 0 -> "Đọc tiếp · Chương $lastReadChapter"
        else -> "Bắt đầu đọc"
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(
            text = readLabel,
            onClick = onStartReading,
            enabled = hasChapters,
            leadingIcon = if (hasChapters) TruyenCloudIcons.BookOpen else null,
            modifier = Modifier.fillMaxWidth()
        )

        // Bốn ô bằng nhau ~80dp trên màn 360dp; nhãn tối đa hai dòng nên giới hạn phóng chữ để hàng không quá cao
        CappedFontScale(max = 1.2f) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (libraryLoaded) {
                    if (isInLibrary) {
                        DetailQuickAction(
                            icon = TruyenCloudIcons.Check,
                            label = "Trong tủ",
                            onClick = onRemoveFromLibrary,
                            active = true,
                            state = "Kệ ${shelfLabel(shelfName)}. Chạm để đổi kệ hoặc gỡ khỏi tủ sách"
                        )
                    } else {
                        DetailQuickAction(
                            icon = TruyenCloudIcons.Add,
                            label = "Thêm vào tủ",
                            onClick = onAddToLibrary
                        )
                    }
                    DetailQuickAction(
                        icon = if (isFavorite) TruyenCloudIcons.HeartFilled else TruyenCloudIcons.Heart,
                        label = "Yêu thích",
                        onClick = onToggleFavorite,
                        active = isFavorite,
                        state = if (isFavorite) "Đã bật" else "Chưa bật"
                    )
                } else {
                    // Giữ chỗ hai ô tủ sách cho tới khi biết trạng thái
                    Spacer(modifier = Modifier.weight(2f))
                }
                DetailQuickAction(icon = TruyenCloudIcons.Headphones, label = "Nghe", onClick = onStartTts)
                DetailQuickAction(icon = TruyenCloudIcons.MoonStar, label = "Dịch ngầm", onClick = onDownload)
            }
        }
    }
}
