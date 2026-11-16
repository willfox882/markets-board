package com.marketsboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import com.marketsboard.ui.news.NewsPhase
import com.marketsboard.ui.news.NewsUiState
import com.marketsboard.ui.DashFonts
import com.marketsboard.ui.LocalBackHook
import com.marketsboard.ui.LocalOpenUrl
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
                CompositionLocalProvider(
                    LocalBackHook provides { enabled, onBack -> BackHandler(enabled, onBack) },
                    LocalOpenUrl provides { url -> openUrl(url) },
                ) {
                    if (runtime != null) {
                        val state by runtime.presenter.state.collectAsState()
                        val news by runtime.newsPresenter.state.collectAsState()
                        MarketsBoardRoot(state, news, actions = runtime.presenter, newsActions = runtime.newsPresenter)
                    } else {
                        // The data layer could not start (storage unavailable): keep the shell alive, show nothing invented.
                        MarketsBoardRoot(
                            MarketsUiState(emptyList()),
                            NewsUiState(phase = NewsPhase.Empty, emptyTitle = "News is unavailable", emptyHint = "The app could not open its storage."),
                        )
                    }
                }
            }
        }
    }

    /** Opens an http(s) link in the system browser. Any failure (no browser on the head unit, bad link) is ignored. */
    private fun openUrl(url: String) {
        try {
            val uri = Uri.parse(url)
            if (uri.scheme != "http" && uri.scheme != "https") return
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: Throwable) {
            Log.w("MarketsBoard", "could not open link", e)
        }
    }

    /** Refreshing runs only while the Activity is resumed (see RefreshController). */
    override fun onResume() {
        super.onResume()
        (application as MarketsBoardApplication).runtime?.gate?.resume()
    }

    override fun onPause() {
        (application as MarketsBoardApplication).runtime?.gate?.pause()
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
