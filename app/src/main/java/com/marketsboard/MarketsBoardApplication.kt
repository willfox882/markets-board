package com.marketsboard

import android.app.Application
import android.util.Log

/** Process entry point. Builds the data layer graph; the UI does not use it until M2. */
class MarketsBoardApplication : Application() {
    /** Null only if building the graph failed (logged); the app must keep running without market data. */
    var graph: AppGraph? = null
        private set

    override fun onCreate() {
        super.onCreate()
        graph = try {
            AppGraph(filesDir)
        } catch (e: Throwable) {
            Log.e("MarketsBoard", "data layer init failed", e)
            null
        }
    }
}
