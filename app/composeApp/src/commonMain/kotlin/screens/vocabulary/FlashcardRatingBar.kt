package com.truyencloud.compose.screens.vocabulary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.CappedFontScale
import com.truyencloud.compose.components.PrimaryButton
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.ButtonShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.srs.FsrsScheduler.Rating

/**
 * Chưa lật: [trước] [Lật thẻ] [sau]. Đã lật: bốn ô chấm Quên / Khó / Tốt / Dễ viền mảnh, chữ màu theo ngữ nghĩa kèm
 * khoảng cách ôn thật (không tô màu đặc).
 */
@Composable
internal fun FlashcardRatingBar(
    isFlipped: Boolean,
    intervalPreview: Map<Rating, String>,
    onReview: (Int) -> Unit,
    onFlip: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val c = LocalCustomColors.current
    if (isFlipped) {
        // Bốn ô trên màn 360dp chỉ rộng ~74dp: giới hạn phóng chữ như nhãn thanh tab để "2,5 tháng" không vỡ dòng
        CappedFontScale(max = 1.15f) {
            // IntrinsicSize.Min: khi khoảng cách ôn dài ("2,5 tháng") phải xuống dòng ở màn hẹp thì cả bốn ô cao bằng nhau
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val tile = Modifier.weight(1f).fillMaxHeight()
                ReviewGradeButton(tile, "Quên", intervalPreview[Rating.AGAIN], c.error) { onReview(Rating.AGAIN.value) }
                ReviewGradeButton(tile, "Khó", intervalPreview[Rating.HARD], c.warning) { onReview(Rating.HARD.value) }
                ReviewGradeButton(tile, "Tốt", intervalPreview[Rating.GOOD], c.accent) { onReview(Rating.GOOD.value) }
                ReviewGradeButton(tile, "Dễ", intervalPreview[Rating.EASY], c.success) { onReview(Rating.EASY.value) }
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuietIconButton(TruyenCloudIcons.ChevronLeft, "Thẻ trước", onPrev)
            PrimaryButton(text = "Lật thẻ", onClick = onFlip, modifier = Modifier.weight(1f))
            QuietIconButton(TruyenCloudIcons.ChevronRight, "Thẻ sau", onNext)
        }
    }
}

/** Ô chấm điểm viền mảnh: nhãn màu [color] (chữ, không tô nền đặc); nền chỉ nhuốm nhẹ khi đang nhấn. */
@Composable
internal fun ReviewGradeButton(
    modifier: Modifier = Modifier,
    label: String,
    interval: String?,
    color: Color,
    onClick: () -> Unit
) {
    val c = LocalCustomColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(ButtonShape)
            .background(if (pressed) color.copy(alpha = 0.08f) else Color.Transparent)
            .border(1.dp, c.outline, ButtonShape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = label, style = AppText.button, color = color, maxLines = 1)
        if (interval != null) {
            Text(
                text = interval,
                style = AppText.captionRegular,
                color = c.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
