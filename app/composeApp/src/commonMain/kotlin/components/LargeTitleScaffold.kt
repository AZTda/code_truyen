package com.truyencloud.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import kotlin.math.roundToInt

/**
 * Khung màn hình chính kiểu iOS: tiêu đề lớn 34sp Bold thu lại thành tiêu đề giữa 17sp SemiBold khi cuộn nội dung
 * (exit-until-collapsed bằng `nestedScroll`, cùng cơ chế snap của Material 3). Nền đặc, đường kẻ mảnh hiện ra khi đã thu.
 *
 * Nội dung nằm DƯỚI thanh và cuộn bình thường (LazyColumn, LazyVerticalGrid, Column + verticalScroll đều hoạt động);
 * thanh co lại theo ngón tay rồi nội dung mới cuộn. [content] nhận [PaddingValues] (đáy 16dp) để truyền thẳng vào
 * `contentPadding`; lề ngang `Spacing.pageH` do nội dung tự đặt.
 *
 * Dùng trong tab của HomeScreen: xoá `statusBarsPadding()` ở cột gốc của HomeScreen (thanh này tự chừa thanh trạng thái),
 * hoặc truyền `windowInsets = WindowInsets(0.dp)` nếu giữ nguyên.
 *
 * @param actions nút bên phải: `QuietIconButton(...)` 24dp màu chữ phụ.
 * @param navigationIcon nút bên trái (hiếm dùng ở tab chính).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LargeTitleScaffold(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    navigationIcon: (@Composable () -> Unit)? = null,
    windowInsets: WindowInsets = WindowInsets.statusBars,
    containerColor: Color = Color.Unspecified,
    content: @Composable (PaddingValues) -> Unit
) {
    val colors = LocalCustomColors.current
    val background = containerColor.takeOrElse { colors.bg }
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .nestedScroll(behavior.nestedScrollConnection)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .windowInsetsPadding(windowInsets)
        ) {
            TopBarRow(
                leading = { navigationIcon?.invoke() },
                trailing = {
                    Row(
                        modifier = Modifier.padding(end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions
                    )
                }
            ) {
                Text(
                    text = title,
                    style = AppText.largeTitleCollapsed,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clearAndSetSemantics { }
                        .graphicsLayer { alpha = ((behavior.state.collapsedFraction - 0.5f) * 2f).coerceIn(0f, 1f) }
                )
            }

            // Vùng tiêu đề lớn: cắt ở chiều cao đã thu (clipToBounds đứng TRƯỚC layout để cắt theo kích thước mới)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                        val visible = (placeable.height + behavior.state.heightOffset).roundToInt().coerceIn(0, placeable.height)
                        layout(placeable.width, visible) { placeable.placeRelative(0, 0) }
                    }
            ) {
                CappedFontScale(max = 1.3f) {
                    Text(
                        text = title,
                        style = AppText.largeTitle,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { size ->
                                // Chiều cao tự nhiên của vùng tiêu đề lớn: thanh chỉ được thu đúng bấy nhiêu
                                val state = behavior.state
                                val limit = -size.height.toFloat()
                                if (state.heightOffsetLimit != limit) {
                                    state.heightOffsetLimit = limit
                                    state.heightOffset = state.heightOffset // ép lại trong khoảng mới
                                }
                            }
                            .graphicsLayer { alpha = (1f - behavior.state.collapsedFraction * 1.4f).coerceIn(0f, 1f) }
                            .padding(start = Spacing.pageH, end = Spacing.pageH, bottom = 8.dp)
                            .semantics { heading() }
                    )
                }
            }

            HairlineDivider(Modifier.graphicsLayer { alpha = behavior.state.collapsedFraction.coerceIn(0f, 1f) })
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            content(PaddingValues(bottom = Spacing.lg))
        }
    }
}
