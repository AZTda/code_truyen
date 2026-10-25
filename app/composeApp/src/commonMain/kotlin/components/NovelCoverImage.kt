package com.truyencloud.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.truyencloud.compose.theme.AppColors
import com.truyencloud.compose.theme.BookCoverShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.ReaderFont
import com.truyencloud.compose.theme.UIFont
import com.truyencloud.shared.api.TruyenCloudApi
import com.truyencloud.shared.data.Novel
import com.truyencloud.shared.data.displayAuthor
import com.truyencloud.shared.data.displayTitle
import kotlin.math.abs

/**
 * Bìa sách: ảnh bìa qua proxy của máy chủ (lỗi thì thử link gốc rồi ảnh mặc định), còn không thì bìa chữ nền Bắc Âu
 * dịu. Bo 6dp kèm đường kẻ mảnh. Dùng chung cho thẻ truyện, "Đang đọc", băng chuyền và tờ hành động.
 *
 * @param artScale tỉ lệ cỡ chữ của bìa chữ so với bìa chuẩn 96dp, vd. 0.6 cho bìa nhỏ 56dp.
 * @param textBottomInset chừa thêm chân bìa chữ cho huy hiệu đè lên chân bìa để tên tác giả không bị che.
 * @param overlay huy hiệu đặt lên bìa (dấu chọn, yêu thích, "Trong tủ"...).
 */
@Composable
fun NovelCoverImage(
    novel: Novel,
    modifier: Modifier = Modifier,
    artScale: Float = 1f,
    borderWidth: Dp = 1.dp,
    textBottomInset: Dp = 0.dp,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    val colors = LocalCustomColors.current
    val cloth = remember(novel.title) {
        AppColors.coverPalettes[abs(novel.title.hashCode()) % AppColors.coverPalettes.size]
    }
    val proxiedUrl = remember(novel.coverUrl) { TruyenCloudApi.getImageProxyUrl(novel.coverUrl) }
    var isImageError by remember(novel.coverUrl) { mutableStateOf(false) }
    var currentImageModel by remember(novel.coverUrl) {
        mutableStateOf(proxiedUrl.ifBlank { novel.coverUrl })
    }

    Box(
        modifier = modifier
            .clip(BookCoverShape)
            .background(Brush.verticalGradient(cloth))
            .border(width = borderWidth, color = colors.hairline, shape = BookCoverShape),
        contentAlignment = Alignment.Center
    ) {
        if (currentImageModel.isNotBlank() && !isImageError) {
            AsyncImage(
                model = currentImageModel,
                contentDescription = novel.displayTitle,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = {
                    if (currentImageModel != novel.coverUrl && novel.coverUrl.isNotBlank()) {
                        currentImageModel = novel.coverUrl
                    } else if (!currentImageModel.contains("fallback_cover.jpg")) {
                        currentImageModel = TruyenCloudApi.getImageProxyUrl("/static/fallback_cover.jpg")
                    } else {
                        isImageError = true
                    }
                }
            )
        }
        if (currentImageModel.isBlank() || isImageError) {
            TypographicCover(novel = novel, artScale = artScale, bottomInset = textBottomInset)
        }
        overlay()
    }
}

/** Bìa chữ khi không có ảnh: tên truyện (serif), một gạch ngắn, tên tác giả. Nền luôn tối nên chữ sáng. */
@Composable
private fun TypographicCover(novel: Novel, artScale: Float, bottomInset: Dp) {
    // Bìa có kích thước cố định: không cho chữ phóng theo cỡ chữ hệ thống, tên đầy đủ đã hiện ngay dưới bìa
    CappedFontScale(max = 1f) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = (8 * artScale).dp,
                    end = (8 * artScale).dp,
                    top = (10 * artScale).dp,
                    bottom = (10 * artScale).dp + bottomInset
                ),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = novel.displayTitle,
                color = AppColors.coverTitle,
                fontSize = (13 * artScale).sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = ReaderFont,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                lineHeight = (17 * artScale).sp
            )
            if (novel.displayAuthor.isNotBlank()) {
                Spacer(modifier = Modifier.height((8 * artScale).dp))
                Box(
                    modifier = Modifier
                        .width((18 * artScale).dp)
                        .height(1.dp)
                        .background(AppColors.coverSubtitle.copy(alpha = 0.5f))
                )
                Spacer(modifier = Modifier.height((8 * artScale).dp))
                Text(
                    text = novel.displayAuthor,
                    color = AppColors.coverSubtitle,
                    fontSize = (11 * artScale).sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = UIFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
