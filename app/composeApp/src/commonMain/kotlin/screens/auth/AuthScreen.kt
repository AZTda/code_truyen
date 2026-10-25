package com.truyencloud.compose.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.components.TruyenCloudLogo
import com.truyencloud.compose.theme.AppText
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.compose.theme.Spacing

/**
 * Màn đăng nhập: logo nhỏ ở giữa, tên ứng dụng, một câu giới thiệu, rồi biểu mẫu ([SignInForm]). Nền phẳng, không hào quang;
 * cuộn được và nâng lên trên bàn phím, giới hạn bề rộng 480dp trên màn rộng.
 */
@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    onAuthSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalCustomColors.current
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isAuthenticated) {
        if (uiState.isAuthenticated) {
            onAuthSuccess()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(Spacing.xxl))

            TruyenCloudLogo(size = 64.dp)

            Spacer(modifier = Modifier.height(Spacing.lg))

            Text(
                text = "TruyenCloud",
                style = AppText.screenTitle,
                color = colors.textPrimary,
                modifier = Modifier.semantics { heading() }
            )

            Spacer(modifier = Modifier.height(Spacing.sm))

            Text(
                text = "Đăng nhập để đồng bộ tủ sách và tiến độ đọc giữa các thiết bị. Bạn vẫn đọc offline được dưới chế độ khách.",
                style = AppText.secondary,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xxl)
            )

            Spacer(modifier = Modifier.height(Spacing.xl))

            SignInForm(
                uiState = uiState,
                onEmailChange = viewModel::onEmailChange,
                onPasswordChange = viewModel::onPasswordChange,
                onSubmit = viewModel::submit,
                onContinueAsGuest = viewModel::continueAsGuest,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(Spacing.xxl))
        }
    }
}
