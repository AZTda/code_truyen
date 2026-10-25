package com.truyencloud.compose.screens.insights

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppAnimations
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.api.GrammarPatternItem

/**
 * Các cấu trúc ngữ pháp học thuật / văn học đã gặp trong truyện (Feature #4). Mỗi cấu trúc là một thẻ phẳng bấm để mở
 * công thức, giải thích và ví dụ trích từ chương đã đọc.
 */
@Composable
fun GrammarPatternsSection(
    patterns: List<GrammarPatternItem>,
    modifier: Modifier = Modifier
) {
    if (patterns.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        patterns.forEach { item ->
            GrammarPatternCard(item = item)
        }
    }
}

@Composable
private fun GrammarPatternCard(
    item: GrammarPatternItem,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    var isExpanded by remember { mutableStateOf(false) }
    val meta = buildList {
        if (item.category.isNotBlank()) add(item.category)
        if (item.frequency > 0) add("Gặp ${item.frequency} lần")
    }.joinToString(" · ")

    FlatCard(
        modifier = modifier
            .fillMaxWidth()
            .semantics { stateDescription = if (isExpanded) "Đang mở" else "Đang thu gọn" },
        onClick = { isExpanded = !isExpanded }
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.patternName.ifBlank { item.pattern },
                        style = AppText.bodyStrong,
                        color = colors.textPrimary
                    )
                    if (meta.isNotEmpty()) {
                        Text(text = meta, style = AppText.captionRegular, color = colors.textSecondary)
                    }
                }
                Icon(
                    imageVector = if (isExpanded) TruyenCloudIcons.ExpandLess else TruyenCloudIcons.ExpandMore,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.padding(start = 8.dp).size(20.dp)
                )
            }

            if (item.patternName.isNotBlank() && item.pattern.isNotBlank()) {
                Text(
                    text = item.pattern,
                    style = AppText.secondaryStrong,
                    color = colors.onAccentSoft,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.accentSoft)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            if (item.explanationVi.isNotBlank()) {
                Text(
                    text = item.explanationVi,
                    style = AppText.secondary,
                    color = colors.textSecondary,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(tween(AppAnimations.SHORT_MS)) + expandVertically(tween(AppAnimations.MEDIUM_MS)),
                exit = fadeOut(tween(AppAnimations.SHORT_MS)) + shrinkVertically(tween(AppAnimations.MEDIUM_MS))
            ) {
                GrammarExamples(examples = item.examples, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
