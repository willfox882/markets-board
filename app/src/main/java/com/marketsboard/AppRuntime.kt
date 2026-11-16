package com.marketsboard

import com.marketsboard.data.Clock
import com.marketsboard.refresh.ActiveGate
import com.marketsboard.refresh.NetworkSignal
import com.marketsboard.refresh.RefreshController
import com.marketsboard.ui.UiMode
import com.marketsboard.ui.markets.MarketsPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Process-scoped UI runtime: the refresh controller and the Markets presenter on top of an [AppGraph]. It lives in
 * the Application (not the Activity) so a recreated Activity keeps the same presenter; [active] is flipped by the
 * Activity from onResume and onPause and pauses all refreshing while the app is in the background.
 * Nothing is held only in memory except which screen is open: the watchlist and quotes live in their stores.
 */
class AppRuntime(
    val graph: AppGraph,
    scope: CoroutineScope,
    clock: Clock = AndroidClock,
    network: NetworkSignal = NetworkSignal.None,
) {
    /** Flipped by the Activity: [ActiveGate.resume] in onResume, [ActiveGate.pause] in onPause. Reused by the M3 news poller. */
    val gate = ActiveGate()
    val uiMode = MutableStateFlow(UiMode.Normal)

    val controller = RefreshController(
        scope = scope,
        quotes = graph.quotes,
        symbols = graph.watchlist.items.map { list -> list.map { it.symbol } },
        isActive = gate.isActive,
        clock = clock,
        // Retry a failed watchlist write on every resume, off the main thread.
        onResume = { scope.launch(Dispatchers.IO) { graph.watchlist.flushIfDirty() } },
        network = network,
    )

    val presenter = MarketsPresenter(scope, graph.data, controller, clock, mode = uiMode)
}
