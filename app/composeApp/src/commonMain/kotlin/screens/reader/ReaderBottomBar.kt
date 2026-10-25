package com.truyencloud.compose.screens.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.components.HairlineDivider
import com.truyencloud.compose.components.QuietIconButton
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppAnimations
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.ButtonShape
import com.truyencloud.compose.theme.LocalCustomColors

/**
 * Thanh dưới của màn đọc: nền ĐẶC màu surface (kéo xuống cả vùng thanh điều hướng) + đường kẻ mảnh ở đỉnh. Một hàng nút 48dp:
 * chương trước, nghe đọc, tự cuộn (chỉ chế độ cuộn dọc), nút giữa (đổi bản gốc / bản dịch, hoặc "Dịch AI" khi chưa có bản dịch),
 * cài đặt đọc, chương sau. Nút nào cũng có mô tả cho trình đọc màn hình.
 */
@Composable
fun ReaderBottomBar(
    visible: Boolean,
    hasTranslation: Boolean,
    showOriginal: Boolean,
    isTranslating: Boolean,
    isPaginatedMode: Boolean = false,
    isAutoScrolling: Boolean = false,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onTranslate: () -> Unit,
    onToggleOriginal: () -> Unit,
    onToggleTts: () -> Unit = {},
    onToggleAutoScroll: () -> Unit = {},
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(AppAnimations.SHORT_MS, easing = LinearOutSlowInEasing)) +
            slideInVertically(tween(AppAnimations.SHORT_MS, easing = LinearOutSlowInEasing)) { it / 2 },
        exit = fadeOut(tween(AppAnimations.SHORT_MS, easing = LinearOutSlowInEasing)) +
            slideOutVertically(tween(AppAnimations.SHORT_MS, easing = LinearOutSlowInEasing)) { it / 2 },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .navigationBarsPadding()
                // Chặn cú chạm lọt xuống chữ phía sau
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            HairlineDivider()
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QuietIconButton(TruyenCloudIcons.ChevronLeft, "Chương trước", onPrev, tint = colors.textPrimary)
                QuietIconButton(TruyenCloudIcons.Headphones, "Nghe đọc truyện", onToggleTts)
                if (!isPaginatedMode) {
                    QuietIconButton(
                        icon = TruyenCloudIcons.AutoScroll,
                        contentDescription = if (isAutoScrolling) "Dừng tự động cuộn" else "Bật tự động cuộn",
                        onClick = onToggleAutoScroll,
                        tint = if (isAutoScrolling) colors.accent else colors.textSecondary
                    )
                }
                // Khoảng giữa hai nhóm nút: đủ rộng thì hiện chip có chữ; màn hẹp / chữ lớn thì rút về nút biểu tượng để không bị cắt "B…"
                BoxWithConstraints(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    val compact = maxWidth < 100.dp * LocalDensity.current.fontScale
                    when {
                        hasTranslation && compact -> QuietIconButton(
                            icon = TruyenCloudIcons.Translate,
                            contentDescription = if (showOriginal) "Xem bản dịch" else "Xem bản gốc",
                            onClick = onToggleOriginal,
                            tint = if (showOriginal) colors.accent else colors.textSecondary
                        )
                        hasTranslation -> FilterChip8(
                            label = "Bản gốc",
                            selected = showOriginal,
                            onClick = onToggleOriginal,
                            leadingIcon = TruyenCloudIcons.Translate,
                            // Thanh dưới là màu surface: ở giao diện sáng / sepia màu thẻ trùng nó nên chip phải dùng cardHigh mới thấy khung
                            containerColor = colors.cardHigh
                        )
                        else -> TranslateAction(isTranslating = isTranslating, compact = compact, onTranslate = onTranslate)
                    }
                }
                QuietIconButton(TruyenCloudIcons.FormatSize, "Cài đặt đọc", onOpenSettings, tint = colors.textPrimary)
                QuietIconButton(TruyenCloudIcons.ChevronRight, "Chương sau", onNext, tint = colors.textPrimary)
            }
        }
    }
}

/**
 * Nút "Dịch AI" (chính, tô màu nhấn) hoặc trạng thái "Đang dịch…" khi đang chờ; vùng chạm 48dp, nút nhìn thấy cao 40dp.
 * [compact] = chỉ biểu tượng (vẫn có mô tả cho trình đọc màn hình) khi chỗ giữa hai nhóm nút quá hẹp.
 */
@Composable
private fun TranslateAction(isTranslating: Boolean, compact: Boolean, onTranslate: () -> Unit) {
    val colors = LocalCustomColors.current
    val description = if (isTranslating) "Đang dịch" else "Dịch AI"
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clickable(enabled = !isTranslating, role = Role.Button, onClick = onTranslate)
            .semantics { contentDescription = description }
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .widthIn(min = if (compact) 40.dp else 0.dp)
                .clip(ButtonShape)
                .background(if (isTranslating) colors.cardHigh else colors.accent)
                .padding(horizontal = if (compact) 0.dp else 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isTranslating) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent)
                if (!compact) Spacer(modifier = Modifier.width(8.dp))
            } else if (compact) {
                Icon(TruyenCloudIcons.Translate, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(20.dp))
            }
            if (!compact) {
                Text(
                    text = if (isTranslating) "Đang dịch…" else "Dịch AI",
                    style = AppText.secondaryStrong,
                    color = if (isTranslating) colors.textSecondary else colors.onAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
