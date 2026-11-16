package com.marketsboard.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** Platform hook for the system back button. Android supplies a BackHandler; default is a no-op. */
val LocalBackHook = staticCompositionLocalOf<@Composable (enabled: Boolean, onBack: () -> Unit) -> Unit> {
    { _, _ -> }
}

/**
 * Platform hook that opens an http(s) link in the system browser. Android supplies an ACTION_VIEW intent (in try/catch),
 * the sandbox uses java.awt.Desktop; the default does nothing. The screens never call it in Glance mode.
 */
val LocalOpenUrl = staticCompositionLocalOf<(String) -> Unit> { { } }

/**
 * Lays everything out as if the screen were [designWidthDp] wide, whatever the real pixel density is.
 * Head units ship with arbitrary density settings; this keeps the UI proportions identical everywhere.
 */
@Composable
fun DesignScale(designWidthDp: Float = 440f, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = Density(density = constraints.maxWidth / designWidthDp, fontScale = 1f)
        CompositionLocalProvider(LocalDensity provides d) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}
