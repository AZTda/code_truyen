package com.truyencloud.compose.components.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser

/** Độ dày nét của bộ icon viền bo tròn (lưới 24x24, hiển thị 24dp). */
internal const val LINE_ICON_STROKE = 1.75f

/**
 * Dựng icon nét viền bo tròn từ chuỗi path SVG (lưới 24x24). Mỗi chuỗi là một path; tiền tố chọn kiểu vẽ:
 * - không tiền tố: chỉ nét viền
 * - `f:` tô đặc (không nét), `e:` tô đặc kiểu even-odd (phần chồng thành lỗ rỗng), `b:` vừa tô đặc vừa nét.
 *
 * Màu trắng chỉ là chỗ giữ chỗ: `Icon(tint = ...)` luôn nhuộm lại theo màu truyền vào.
 */
internal fun lineIcon(name: String, vararg paths: String): ImageVector = buildIcon(name) {
    for (spec in paths) {
        val kind = if (spec.length > 2 && spec[1] == ':') spec[0] else ' '
        val data = if (kind == ' ') spec else spec.substring(2)
        val nodes = PathParser().parsePathString(data).toNodes()
        val white = SolidColor(Color.White)
        when (kind) {
            'f' -> addPath(pathData = nodes, fill = white)
            'e' -> addPath(pathData = nodes, pathFillType = PathFillType.EvenOdd, fill = white)
            'b' -> addPath(
                pathData = nodes, fill = white, stroke = white, strokeLineWidth = LINE_ICON_STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            )
            else -> addPath(
                pathData = nodes, stroke = white, strokeLineWidth = LINE_ICON_STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            )
        }
    }
}

/** Khai báo icon dạng `by lazy` để chỉ dựng khi dùng lần đầu. */
internal fun lazyIcon(name: String, vararg paths: String): Lazy<ImageVector> = lazy { lineIcon(name, *paths) }

/** Vòng tròn đầy bán kính [r] tâm ([cx], [cy]) dưới dạng path, dùng cho chấm nhỏ trong icon. */
internal fun dot(cx: Float, cy: Float, r: Float = 1.2f): String =
    "f:M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0 $r $r 0 1 0 ${-2 * r} 0Z"

/** Sách mở (dùng cho Tủ sách, đang đọc, truyện): hai trang cong chạm gáy giữa. */
internal const val OPEN_BOOK = "M3 5.6C5.4 4.5 8.9 4.6 12 6.8C15.1 4.6 18.6 4.5 21 5.6V18.4C18.6 17.3 15.1 17.4 12 19.6" +
    "C8.9 17.4 5.4 17.3 3 18.4Z"
internal const val OPEN_BOOK_SPINE = "M12 6.8V19.6"

internal const val TRAY_DOWN = "M12 4v11 M7.5 10.5L12 15l4.5-4.5 M4 15.5v2A2.5 2.5 0 0 0 6.5 20h11a2.5 2.5 0 0 0 2.5-2.5v-2"
internal const val TRAY_UP = "M12 15V4 M7.5 8.5L12 4l4.5 4.5 M4 15.5v2A2.5 2.5 0 0 0 6.5 20h11a2.5 2.5 0 0 0 2.5-2.5v-2"
internal const val CIRCLE_24 = "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z"
internal const val BOOKMARK = "M7 3.5h10A1.5 1.5 0 0 1 18.5 5v15.5l-6.5-4.2-6.5 4.2V5A1.5 1.5 0 0 1 7 3.5Z"
internal const val SPEAKER = "M11 5L6.5 9H3.5v6h3L11 19Z"
internal const val FLAME = "M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.07-2.14-.22-4.05 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5" +
    "a7 7 0 1 1-14 0c0-1.15.43-2.29 1-3a2.5 2.5 0 0 0 2.5 2.5Z"
