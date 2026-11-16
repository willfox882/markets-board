package com.marketsboard

import android.Manifest
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.marketsboard.briefing.TriggerSource
import com.marketsboard.ui.markets.MarketsUiState
import com.marketsboard.ui.news.NewsPhase
import com.marketsboard.ui.news.NewsUiState
import com.marketsboard.ui.DashFonts
import com.marketsboard.ui.LocalBackHook
import com.marketsboard.ui.LocalOpenUrl
import com.marketsboard.ui.MarketsBoardRoot
import com.marketsboard.ui.MarketsBoardTheme

class MainActivity : ComponentActivity() {
    private var speed: LocationSpeedSource? = null
    private var askedThisRun = false
    private var permanentlyDenied = false

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val app = application as MarketsBoardApplication
        app.location.refresh()
        if (app.location.granted.value) {
            permanentlyDenied = false
            speed?.start()
        } else {
            // Denied is a normal state. When Android will not show the dialog again, the next request opens the settings page.
            permanentlyDenied = !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /** Shows the system dialog, or the app's settings page when Android will not ask again. Never throws. */
    private fun requestLocation() {
        askedThisRun = true
        try {
            if (permanentlyDenied) {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            } else {
                permissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        } catch (e: Throwable) {
            Log.w("MarketsBoard", "location request failed: ${e.javaClass.simpleName}")
        }
    }

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
        (application as MarketsBoardApplication).location.requester = { requestLocation() }
        speed = runtime?.let { rt -> LocationSpeedSource(this) { kmh -> rt.driving.onSpeed(kmh) } }
        setContent {
            MarketsBoardTheme {
                CompositionLocalProvider(
                    LocalBackHook provides { enabled, onBack -> BackHandler(enabled, onBack) },
                    LocalOpenUrl provides { url -> openUrl(url) },
                ) {
                    if (runtime != null) {
                        val state by runtime.presenter.state.collectAsState()
                        val news by runtime.newsPresenter.state.collectAsState()
                        val briefing by runtime.briefing.state.collectAsState()
                        val settings by runtime.settingsPresenter.state.collectAsState()
                        val driving by runtime.driving.status.collectAsState()
                        MarketsBoardRoot(
                            state, news, actions = runtime.presenter, mode = driving.mode, newsActions = runtime.newsPresenter,
                            driving = driving, briefing = briefing, briefingActions = runtime.briefing,
                            settings = settings, settingsActions = runtime.settingsPresenter,
                        )
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

    /** Opens an http(s) link in the system browser; false when there is no browser or the link is not a web link. */
    private fun openUrl(url: String): Boolean = try {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            false
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
            true
        }
    } catch (e: Throwable) {
        Log.w("MarketsBoard", "could not open link", e)
        false
    }

    /** Refreshing runs only while the Activity is resumed (see RefreshController). */
    override fun onResume() {
        super.onResume()
        val app = application as MarketsBoardApplication
        app.location.refresh() // the user may have changed it in system settings
        app.runtime?.gate?.resume()
        // GPS speed only while the Activity is resumed (no service). Ask once per run when it is missing.
        if (app.location.granted.value) speed?.start() else if (!askedThisRun) requestLocation()
    }

    override fun onPause() {
        speed?.stop() // the gate keeps its last state; a lost fix never unlocks it
        (application as MarketsBoardApplication).runtime?.gate?.pause() // also stops any briefing
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) (application as MarketsBoardApplication).runtime?.speaker?.shutdown()
        (application as MarketsBoardApplication).location.requester = null
        super.onDestroy()
    }

    private fun isBriefingKey(code: Int) = code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_HEADSETHOOK

    /**
     * Media play/pause (and the headset hook) start or stop the briefing while this Activity is in the foreground, when the
     * Settings switch is on. A future body-bus steering wheel hook would call the same `BriefingTrigger` with
     * `TriggerSource.SteeringWheel`.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isBriefingKey(keyCode)) {
            val rt = (application as MarketsBoardApplication).runtime
            if (rt != null && event.repeatCount == 0 && rt.briefing.trigger(TriggerSource.MediaKey)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val rt = (application as MarketsBoardApplication).runtime
        if (isBriefingKey(keyCode) && rt != null && rt.graph.settingsStore.settings.value.mediaKeyTrigger) return true
        return super.onKeyUp(keyCode, event)
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
