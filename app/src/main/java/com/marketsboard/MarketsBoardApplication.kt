package com.marketsboard

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process entry point. Builds the data layer graph and the UI runtime (presenter + refresh controller). */
class MarketsBoardApplication : Application() {
    /** Null only if building the graph failed (logged); the app must keep running without market data. */
    var graph: AppGraph? = null
        private set

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
                AppRuntime(it, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), AndroidClock, ConnectivityNetworkSignal(this))
            } catch (e: Throwable) {
                Log.e("MarketsBoard", "ui runtime init failed", e)
                null
            }
        }
    }
}
