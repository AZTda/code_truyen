package com.truyencloud.compose.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.InsetGroup
import com.truyencloud.compose.components.InsetIconTint
import com.truyencloud.compose.components.InsetRow
import com.truyencloud.compose.components.icons.TruyenCloudIcons
import com.truyencloud.compose.theme.AppAnimations
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

/**
 * Nhóm "Nâng cao", thu gọn sẵn: địa chỉ máy chủ và các thông số của động cơ dịch. Phần này hầu như không cần động tới nên
 * ẩn đi để trang Cài đặt không đầy ắp chi tiết kỹ thuật.
 */
@Composable
fun AdvancedSection(
    server: ServerSettingsState,
    engineStatus: String,
    onServerInputChange: (String) -> Unit,
    onServerTest: () -> Unit,
    onServerSave: () -> Unit,
    onServerReset: () -> Unit,
    activeModel: String,
    activeEffort: String,
    timeoutSeconds: Int,
    cliVersion: String,
    isAgyAuthenticated: Boolean,
    isAdmin: Boolean,
    onSelectEffort: (String) -> Unit,
    onSelectTimeout: (Int) -> Unit,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false
) {
    val colors = LocalCustomColors.current
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Column(modifier = modifier.fillMaxWidth()) {
        InsetGroup(footer = if (expanded) null else "Địa chỉ máy chủ, động cơ dịch và mức suy luận.") {
            InsetRow(
                title = "Nâng cao",
                icon = TruyenCloudIcons.Tune,
                iconTint = InsetIconTint.Neutral,
                onClick = { expanded = !expanded },
                modifier = Modifier.semantics { stateDescription = if (expanded) "Đang mở" else "Đang thu gọn" },
                trailing = {
                    Icon(
                        imageVector = if (expanded) TruyenCloudIcons.ExpandLess else TruyenCloudIcons.ExpandMore,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(AppAnimations.MEDIUM_MS, easing = LinearOutSlowInEasing)) + fadeIn(tween(AppAnimations.MEDIUM_MS)),
            exit = shrinkVertically(tween(AppAnimations.SHORT_MS, easing = LinearOutSlowInEasing)) + fadeOut(tween(AppAnimations.SHORT_MS))
        ) {
            Column(
                modifier = Modifier.padding(top = Spacing.section),
                verticalArrangement = Arrangement.spacedBy(Spacing.section)
            ) {
                ServerSection(
                    server = server,
                    engineStatus = engineStatus,
                    onInputChange = onServerInputChange,
                    onTest = onServerTest,
                    onSave = onServerSave,
                    onReset = onServerReset
                )
                AntigravitySection(
                    activeModel = activeModel,
                    activeEffort = activeEffort,
                    timeoutSeconds = timeoutSeconds,
                    cliVersion = cliVersion,
                    isAgyAuthenticated = isAgyAuthenticated,
                    onSelectEffort = onSelectEffort,
                    onSelectTimeout = onSelectTimeout,
                    isAdmin = isAdmin
                )
            }
        }
    }
}
