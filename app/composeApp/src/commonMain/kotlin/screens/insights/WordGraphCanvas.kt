package com.truyencloud.compose.screens.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truyencloud.compose.theme.InputShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.UIFont
import com.truyencloud.shared.api.WordGraphEdge
import com.truyencloud.shared.api.WordGraphNode
import kotlin.math.sqrt

/**
 * Vùng vẽ mạng lưới từ: chụm hai ngón để phóng to, kéo để di chuyển, chạm vào nút để chọn. Nền phẳng (màu nền trang) nằm
 * trong thẻ; không lưới sao, không quầng sáng — nút đang chọn có một vòng viền.
 */
@Composable
fun WordGraphCanvas(
    filteredNodes: List<WordGraphNode>,
    filteredEdges: List<WordGraphEdge>,
    selectedNodeId: String?,
    zoomScale: Float,
    onZoomScaleChange: (Float) -> Unit,
    panOffset: Offset,
    onPanOffsetChange: (Offset) -> Unit,
    onSelectNodeId: (String?) -> Unit,
    textMeasurer: TextMeasurer,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val nodeMap = remember(filteredNodes) { filteredNodes.associateBy { it.id } }

    // Khối pointerInput(Unit) chỉ chạy một lần nên phải đọc giá trị mới nhất qua State, nếu không mỗi lần kéo sẽ
    // tính lại từ trạng thái ban đầu và đồ thị không tích luỹ được.
    val latestZoom by rememberUpdatedState(zoomScale)
    val latestPan by rememberUpdatedState(panOffset)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(InputShape)
            .background(colors.bg)
            .semantics {
                contentDescription = "Mạng lưới ${filteredNodes.size} từ. Chạm vào một nút để xem chi tiết, chụm hai ngón để phóng to."
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        onZoomScaleChange((latestZoom * zoom).coerceIn(0.6f, 2.5f))
                        onPanOffsetChange(latestPan + pan)
                    }
                }
                .pointerInput(filteredNodes, zoomScale, panOffset) {
                    // Vùng chạm 48dp quanh mỗi nút
                    val hitRadius = 24.dp.toPx()
                    detectTapGestures { tapOffset ->
                        val width = size.width.toFloat()
                        val height = size.height.toFloat()

                        var closest: WordGraphNode? = null
                        var minDistance = Float.MAX_VALUE

                        for (node in filteredNodes) {
                            val nodeScreenX = (node.x * width) * zoomScale + panOffset.x + (1 - zoomScale) * width / 2
                            val nodeScreenY = (node.y * height) * zoomScale + panOffset.y + (1 - zoomScale) * height / 2
                            val dist = sqrt(
                                (tapOffset.x - nodeScreenX) * (tapOffset.x - nodeScreenX) +
                                    (tapOffset.y - nodeScreenY) * (tapOffset.y - nodeScreenY)
                            )
                            if (dist < hitRadius && dist < minDistance) {
                                minDistance = dist
                                closest = node
                            }
                        }
                        onSelectNodeId(closest?.id)
                    }
                }
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            fun toScreen(nx: Float, ny: Float): Offset {
                val sx = (nx * canvasWidth) * zoomScale + panOffset.x + (1 - zoomScale) * canvasWidth / 2
                val sy = (ny * canvasHeight) * zoomScale + panOffset.y + (1 - zoomScale) * canvasHeight / 2
                return Offset(sx, sy)
            }

            // 1. Cạnh: nét mảnh, cạnh chạm tới nút đang chọn đậm hơn
            for (edge in filteredEdges) {
                val src = nodeMap[edge.source] ?: continue
                val dst = nodeMap[edge.target] ?: continue
                val isEdgeSelected = selectedNodeId == edge.source || selectedNodeId == edge.target

                val base = when (edge.relation) {
                    "synonym" -> colors.accent
                    "antonym" -> colors.error
                    else -> colors.textTertiary
                }
                drawLine(
                    color = base.copy(alpha = if (isEdgeSelected) 0.95f else 0.35f),
                    start = toScreen(src.x, src.y),
                    end = toScreen(dst.x, dst.y),
                    strokeWidth = (if (isEdgeSelected) 2.dp else 1.dp).toPx(),
                    cap = StrokeCap.Round
                )
            }

            // 2. Nút + nhãn (nhãn tối thiểu 12sp)
            for (node in filteredNodes) {
                val p = toScreen(node.x, node.y)
                val isSelected = node.id == selectedNodeId
                val baseColor = clusterColor(node.cluster, colors)
                val baseRadius = if (node.cefrLevel == "c1" || node.cefrLevel == "c2") 6.5.dp else 5.dp
                val radius = baseRadius.toPx() * zoomScale.coerceIn(0.8f, 2f)

                if (isSelected) {
                    drawCircle(
                        color = colors.textPrimary,
                        radius = radius + 4.dp.toPx(),
                        center = p,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
                drawCircle(color = baseColor, radius = radius, center = p)

                val label = textMeasurer.measure(
                    text = node.word,
                    style = TextStyle(
                        color = if (isSelected) colors.textPrimary else colors.textSecondary,
                        fontSize = (12f * zoomScale).coerceIn(12f, 15f).sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        fontFamily = UIFont
                    )
                )
                drawText(
                    textLayoutResult = label,
                    topLeft = Offset(p.x - label.size.width / 2, p.y + radius + 3.dp.toPx())
                )
            }
        }
    }
}
