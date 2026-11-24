package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Reliable system-bar & IME safe inset padding for Compose full-screen Dialogs.
 *
 * On Android 15 / Xiaomi HyperOS 2 (especially with 3-button navigation), secondary Dialog
 * windows with decorFitsSystemWindows = false often report 0px for WindowInsets.navigationBars
 * inside the nested ComposeView while still rendering behind the 3-button bar.
 * Taking max(dialogInset, activityRootDecorInset) prevents overlap without ever double-padding.
 */
fun Modifier.zverDialogSafeBarsPadding(): Modifier = composed {
    val context = LocalContext.current
    val dialogView = LocalView.current
    val density = LocalDensity.current

    DisposableEffect(dialogView) {
        val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
        if (dialogWindow != null) {
            try {
                WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
            } catch (_: Throwable) {}
        }
        onDispose {}
    }

    val activityRootInsets = context.findActivity()
        ?.window
        ?.decorView
        ?.let { ViewCompat.getRootWindowInsets(it) }

    val actTopPx = activityRootInsets
        ?.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
        ?.top ?: 0
    val actBottomNavPx = activityRootInsets
        ?.getInsets(WindowInsetsCompat.Type.navigationBars())
        ?.bottom ?: 0
    val actBottomImePx = activityRootInsets
        ?.getInsets(WindowInsetsCompat.Type.ime())
        ?.bottom ?: 0

    val dlgTopPx = max(
        WindowInsets.statusBars.getTop(density),
        WindowInsets.displayCutout.getTop(density)
    )
    val dlgBottomPx = max(
        WindowInsets.navigationBars.getBottom(density),
        WindowInsets.ime.getBottom(density)
    )

    val topPx = max(dlgTopPx, actTopPx)
    val bottomPx = maxOf(dlgBottomPx, actBottomNavPx, actBottomImePx)

    val topDp = with(density) { topPx.toDp() }
    val bottomDp = with(density) { bottomPx.toDp() }

    this.padding(top = topDp, bottom = bottomDp.coerceAtLeast(0.dp))
}
