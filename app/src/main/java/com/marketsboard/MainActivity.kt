package com.marketsboard

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.marketsboard.ui.markets.MarketsUiState
import com.marketsboard.ui.news.NewsComingSoon
import com.marketsboard.ui.DashFonts
import com.marketsboard.ui.LocalBackHook
import com.marketsboard.ui.MarketsBoardRoot
import com.marketsboard.ui.MarketsBoardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DashFonts.family = FontFamily(
            Font(R.font.geist_light, FontWeight.Light), Font(R.font.geist_regular, FontWeight.Normal),
            Font(R.font.geist_medium, FontWeight.Medium), Font(R.font.geist_semibold, FontWeight.SemiBold),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        hideSystemBars()
        val runtime = (application as MarketsBoardApplication).runtime
        setContent {
            MarketsBoardTheme {
                CompositionLocalProvider(LocalBackHook provides { enabled, onBack -> BackHandler(enabled, onBack) }) {
                    if (runtime != null) {
                        val state by runtime.presenter.state.collectAsState()
                        MarketsBoardRoot(state, NewsComingSoon, actions = runtime.presenter)
                    } else {
                        // The data layer could not start (storage unavailable): keep the shell alive, show nothing invented.
                        MarketsBoardRoot(MarketsUiState(emptyList()), NewsComingSoon)
                    }
                }
            }
        }
    }

    /** Refreshing runs only while the Activity is resumed (see RefreshController). */
    override fun onResume() {
        super.onResume()
        (application as MarketsBoardApplication).runtime?.active?.value = true
    }

    override fun onPause() {
        (application as MarketsBoardApplication).runtime?.active?.value = false
        super.onPause()
    }

    /** Swiping in system bars or a dialog can leave them visible; re-hide whenever the window regains focus. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
