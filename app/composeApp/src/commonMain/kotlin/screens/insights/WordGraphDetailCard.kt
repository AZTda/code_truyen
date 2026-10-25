package com.truyencloud.compose.screens.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FilterChip8
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.api.WordGraphEdge
import com.truyencloud.shared.api.WordGraphNode

/**
 * Thông tin nút đang chọn: từ, mức CEFR, chủ đề, nghĩa tiếng Việt và các từ liên quan (chạm để nhảy sang từ đó).
 */
@Composable
fun WordGraphDetailCard(
    selectedNode: WordGraphNode,
    filteredEdges: List<WordGraphEdge>,
    onSelectOtherNode: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val relations = filteredEdges.filter { it.source == selectedNode.id || it.target == selectedNode.id }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.cardHigh)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = selectedNode.word,
                style = AppText.sectionHeader,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (selectedNode.cefrLevel.isNotBlank()) {
                Text(text = selectedNode.cefrLevel.uppercase(), style = AppText.caption, color = colors.textSecondary)
            }
        }

        if (selectedNode.cluster.isNotBlank()) {
            Row(
                modifier = Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(clusterColor(selectedNode.cluster, colors))
                )
                Text(text = selectedNode.cluster, style = AppText.captionRegular, color = colors.textSecondary)
            }
        }

        if (selectedNode.meaningVi.isNotBlank()) {
            Text(
                text = selectedNode.meaningVi,
                style = AppText.body,
                color = colors.textPrimary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (relations.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                relations.forEach { rel ->
                    val otherId = if (rel.source == selectedNode.id) rel.target else rel.source
                    val prefix = when (rel.relation) {
                        "synonym" -> "Đồng nghĩa"
                        "antonym" -> "Trái nghĩa"
                        else -> "Cùng chủ đề"
                    }
                    FilterChip8(
                        label = "$prefix: $otherId",
                        selected = false,
                        onClick = { onSelectOtherNode(otherId) },
                        containerColor = colors.card
                    )
                }
            }
        }
    }
}
