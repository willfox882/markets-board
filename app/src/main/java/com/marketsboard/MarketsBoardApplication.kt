package com.marketsboard

import android.app.Application
import android.util.Log
import com.marketsboard.ui.news.LinkOpener
import com.marketsboard.ui.settings.AboutInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process entry point. Builds the data layer graph and the UI runtime (presenter + refresh controller). */
class MarketsBoardApplication : Application() {
    /** Null only if building the graph failed (logged); the app must keep running without market data. */
    var graph: AppGraph? = null
        private set

    /** The location permission as the UI sees it (the Activity refreshes it and installs the request hook). */
    val location: AndroidLocationAccess by lazy { AndroidLocationAccess(this) }

    /** Presenter and refresh controller; null when the graph could not be built. */
    var runtime: AppRuntime? = null
        private set

    override fun onCreate() {
        super.onCreate()
        graph = try {
            AppGraph(filesDir, AndroidClock)
        } catch (e: Throwable) {
            Log.e("MarketsBoard", "data layer init failed", e)
            null
        }
        runtime = graph?.let {
            try {
                AppRuntime(it, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), AndroidClock, ConnectivityNetworkSignal(this), AndroidSpeaker(this), location, aboutInfo(), LinkOpener { url -> openUrl(url) })
            } catch (e: Throwable) {
                Log.e("MarketsBoard", "ui runtime init failed", e)
                null
            }
        }
    }

    /** Opens an http(s) link in the system browser (a new task, since this is the application context); false when there is no browser or the link is not a web link. */
    private fun openUrl(url: String): Boolean = try {
        val uri = android.net.Uri.parse(url)
        val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            false
        } else {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addCategory(android.content.Intent.CATEGORY_BROWSABLE).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
    } catch (e: Throwable) {
        Log.w("MarketsBoard", "could not open link", e)
        false
    }

    private fun aboutInfo(): AboutInfo = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        val debug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        AboutInfo(info.versionName.orEmpty(), "${if (debug) "debug" else "release"} ${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)}")
    } catch (e: Throwable) {
        AboutInfo()
    }
}
