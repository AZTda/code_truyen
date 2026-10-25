package com.truyencloud.compose.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppAnimations
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing
import com.truyencloud.compose.theme.springPress

enum class NavTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    BOOKSHELF("Tủ sách", TruyenCloudIcons.BookshelfFilled, TruyenCloudIcons.Bookshelf),
    DISCOVER("Khám phá", TruyenCloudIcons.DiscoverFilled, TruyenCloudIcons.Discover),
    VOCABULARY("Từ vựng", TruyenCloudIcons.SchoolFilled, TruyenCloudIcons.School),
    INSIGHTS("Thống kê", TruyenCloudIcons.InsightsFilled, TruyenCloudIcons.Insights),
    SETTINGS("Cài đặt", TruyenCloudIcons.SettingsFilled, TruyenCloudIcons.Settings)
}

/**
 * Thanh tab dưới chuẩn (không còn viên thuốc nổi): cao 64dp cộng vùng thanh điều hướng hệ thống, nhãn LUÔN hiện 11sp Medium (phóng tối đa 1,15 lần theo chữ hệ thống).
 * Tab chọn = biểu tượng đặc + nhãn màu nhấn; tab khác = biểu tượng viền + chữ phụ. Nền surface 94% kèm đường kẻ mảnh
 * phía trên (Compose Multiplatform không có làm mờ nền thật nên không giả bằng gradient).
 */
@Composable
fun AppBottomNavBar(
    selectedTab: NavTab,
    onTabSelected: (NavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface.copy(alpha = 0.94f))
    ) {
        HairlineDivider()
        // Năm tab trên màn 360dp (mỗi tab 72dp): nhãn chỉ phóng tối đa 1,15 lần để "Khám phá" còn vừa một dòng
        CappedFontScale(max = 1.15f) {
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                NavTab.entries.forEach { tab ->
                    TabItem(
                        tab = tab,
                        selected = tab == selectedTab,
                        onClick = { onTabSelected(tab) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TabItem(tab: NavTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = LocalCustomColors.current
    val tint by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.textSecondary,
        animationSpec = tween(AppAnimations.SHORT_MS),
        label = "tabTint"
    )
    Column(
        modifier = modifier
            .heightIn(min = Spacing.bottomBarHeight)
            .springPress(targetScale = 0.94f, onClick = onClick)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                role = Role.Tab
            }
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = tab.title,
            style = AppText.tabLabel,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
