package com.truyencloud.compose.screens.insights

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.FlatCard
import com.truyencloud.compose.components.TextActionButton
import com.truyencloud.compose.theme.AppAnimations
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.ChipShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.api.WordGraphResponse

/**
 * Mạng lưới ngữ nghĩa các từ đã lưu (Feature #7): thẻ phẳng gồm thống kê + nút đặt lại khung nhìn, hàng chip lọc theo
 * chủ đề, vùng vẽ tương tác và thẻ chi tiết của nút đang chọn.
 */
@Composable
fun WordGraphSection(
    graph: WordGraphResponse,
    modifier: Modifier = Modifier
) {
    var selectedCluster by remember { mutableStateOf<String?>(null) }
    var selectedNodeId by remember { mutableStateOf<String?>(null) }
    var zoomScale by remember { mutableStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    val colors = LocalCustomColors.current
    val textMeasurer = rememberTextMeasurer()

    val filteredNodes = remember(graph.nodes, selectedCluster) {
        if (selectedCluster == null) graph.nodes else graph.nodes.filter { it.cluster == selectedCluster }
    }
    val filteredNodeIds = remember(filteredNodes) { filteredNodes.map { it.id }.toSet() }
    val filteredEdges = remember(graph.edges, filteredNodeIds) {
        graph.edges.filter { it.source in filteredNodeIds && it.target in filteredNodeIds }
    }
    val selectedNode = remember(filteredNodes, selectedNodeId) {
        filteredNodes.firstOrNull { it.id == selectedNodeId }
    }

    FlatCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${filteredNodes.size} từ · ${filteredEdges.size} liên kết ngữ nghĩa",
                    style = AppText.secondary,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // Nút chữ có đệm ngang 12dp: dịch sang phải để chữ thẳng mép với lề thẻ
                TextActionButton(
                    text = "Đặt lại",
                    onClick = {
                        zoomScale = 1.0f
                        panOffset = Offset.Zero
                    },
                    modifier = Modifier.offset(x = 12.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ClusterChip(
                    label = "Tất cả",
                    count = graph.nodes.size,
                    dotColor = null,
                    selected = selectedCluster == null,
                    onClick = { selectedCluster = null }
                )
                graph.clusters.forEach { cluster ->
                    ClusterChip(
                        label = cluster.name,
                        count = cluster.count,
                        dotColor = clusterColor(cluster.name, colors),
                        selected = selectedCluster == cluster.name,
                        onClick = { selectedCluster = cluster.name }
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            WordGraphCanvas(
                filteredNodes = filteredNodes,
                filteredEdges = filteredEdges,
                selectedNodeId = selectedNodeId,
                zoomScale = zoomScale,
                onZoomScaleChange = { zoomScale = it },
                panOffset = panOffset,
                onPanOffsetChange = { panOffset = it },
                onSelectNodeId = { selectedNodeId = it },
                textMeasurer = textMeasurer
            )

            AnimatedVisibility(
                visible = selectedNode != null,
                enter = fadeIn(tween(AppAnimations.SHORT_MS)),
                exit = fadeOut(tween(AppAnimations.SHORT_MS))
            ) {
                if (selectedNode != null) {
                    Column {
                        Spacer(modifier = Modifier.height(12.dp))
                        WordGraphDetailCard(
                            selectedNode = selectedNode,
                            filteredEdges = filteredEdges,
                            onSelectOtherNode = { selectedNodeId = it }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Chip lọc chủ đề: giống FilterChip8 (cao 32dp trong vùng chạm 48dp, đã chọn = nền accentSoft) nhưng thêm chấm màu của
 * chủ đề đứng trước nhãn để chip đóng vai chú giải cho màu nút. Nền chưa chọn là cardHigh vì chip nằm trong một thẻ.
 */
@Composable
private fun ClusterChip(
    label: String,
    count: Int,
    dotColor: Color?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalCustomColors.current
    val textColor = if (selected) colors.onAccentSoft else colors.textSecondary
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(ChipShape)
                .background(if (selected) colors.accentSoft else colors.cardHigh)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (dotColor != null) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            }
            Text(text = label, style = AppText.secondaryStrong, color = textColor, maxLines = 1)
            Text(text = "$count", style = AppText.secondaryStrong, color = textColor, maxLines = 1)
        }
    }
}
