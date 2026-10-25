package com.truyencloud.compose.screens.insights

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.components.CappedFontScale
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.theme.AppAnimations
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Hai vòng tiến độ ghi nhớ: tỉ lệ nhớ 30 ngày và tỉ lệ từ nhớ lâu dài. Cùng một màu nhấn (đây là tiến độ), con số nằm
 * giữa vòng và chú thích bên dưới nên không phụ thuộc màu. Chưa có lượt ôn nào thì hiện "—" thay vì "0%" gây hiểu nhầm.
 */
@Composable
fun RetentionAndMatureRow(state: InsightsUiState, modifier: Modifier = Modifier) {
    val hasReviews = state.dailyReviewStats.isNotEmpty()
    Row(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RingCard(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            title = "Tỉ lệ nhớ",
            caption = if (hasReviews) "Trả lời đúng trong 30 ngày qua" else "Chưa có lượt ôn nào trong 30 ngày qua",
            centerText = if (hasReviews) InsightsFormat.percent(state.retention30d) else "—",
            progress = if (hasReviews) state.retention30d.toFloat() else 0f
        )
        RingCard(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            title = "Từ nhớ lâu dài",
            caption = "${InsightsFormat.count(state.matureWords)} trên ${InsightsFormat.count(state.totalWords)} từ đã lưu",
            centerText = InsightsFormat.percent(state.maturePercent),
            progress = state.maturePercent
        )
    }
}

@Composable
private fun RingCard(
    title: String,
    caption: String,
    centerText: String,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(progress) { sweep.animateTo(progress.coerceIn(0f, 1f), AppAnimations.slowTween) }
    val ringTrack = colors.hairline
    val ringColor = colors.accent

    FlatCard(
        modifier = modifier.semantics(mergeDescendants = true) {},
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp)
    ) {
        // Thẻ rộng ~154dp: giới hạn phóng chữ để số giữa vòng và chú thích không tràn
        CappedFontScale(max = 1.2f) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeWidth = 8.dp.toPx()
                        val arc = size.minDimension - strokeWidth
                        val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
                        drawArc(
                            color = ringTrack,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = Size(arc, arc),
                            style = Stroke(width = strokeWidth)
                        )
                        val progressSweep = 360f * sweep.value
                        if (progressSweep > 0.5f) {
                            drawArc(
                                color = ringColor,
                                startAngle = -90f,
                                sweepAngle = progressSweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = Size(arc, arc),
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )
                        }
                    }
                    Text(
                        text = centerText,
                        style = AppText.screenTitle.copy(fontSize = 20.sp, lineHeight = 26.sp),
                        color = colors.textPrimary,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = title,
                    style = AppText.secondaryStrong,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = caption,
                    style = AppText.captionRegular,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
