package com.truyencloud.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.UIKit.UIApplication

@Composable
actual fun KeepScreenOn(enabled: Boolean) {
    DisposableEffect(enabled) {
        val app = UIApplication.sharedApplication
        val previous = app.idleTimerDisabled
        if (enabled) app.idleTimerDisabled = true
        onDispose { app.idleTimerDisabled = previous }
    }
}
