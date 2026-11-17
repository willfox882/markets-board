package com.marketsboard.ui.tools

import com.marketsboard.core.DrivingStatus
import com.marketsboard.data.Clock
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.net.FetchEvent
import com.marketsboard.data.news.FeedConfig
import com.marketsboard.data.news.NewsState
import com.marketsboard.data.repo.QuotesSource
import com.marketsboard.refresh.NetworkStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart

/** The live inputs of the diagnostics screen, each a flow so the screen follows the data layer. */
class DiagnosticsSources(
    val news: StateFlow<NewsState>,
    val feeds: StateFlow<List<FeedConfig>>,
    val quotes: QuotesSource,
    val watch: StateFlow<List<WatchItem>>,
    val fetches: StateFlow<List<FetchEvent>>,
    val signal: StateFlow<NetworkStatus>,
    val offline: StateFlow<Boolean>,
    val driving: StateFlow<DrivingStatus>,
    val locationGranted: StateFlow<Boolean>,
)

/**
 * Read-only diagnostics. [flow] is COLD: it only runs while the diagnostics page is on screen (the tools presenter collects it
 * only then), so a closed page costs nothing. It re-maps whenever any input changes and every [tickMs] so ages keep moving.
 */
class DiagnosticsPresenter(
    private val src: DiagnosticsSources,
    private val clock: Clock = Clock.System,
    private val tickMs: Long = 5_000,
    private val tick: Flow<Unit> = flow { while (true) { delay(tickMs); emit(Unit) } },
) {
    fun flow(): Flow<DiagnosticsUiState> {
        val a = combine(src.news, src.feeds, src.quotes.states, src.watch, src.fetches) { n, f, q, w, e -> Five(n, f, q, w, e) }
        val b = combine(src.signal, src.offline, src.driving, src.locationGranted, tick.onStart { emit(Unit) }) { sig, off, d, loc, _ -> Four(sig, off, d, loc) }
        return combine(a, b) { x, y ->
            DiagnosticsMapper.map(
                DiagnosticsInput(
                    signal = y.signal, offline = y.offline, providers = src.quotes.providerHealth(), fetches = x.fetches, news = x.news,
                    feedConfigs = x.feeds, watch = x.watch, quotes = x.quotes, driving = y.driving, locationGranted = y.loc, wallNowMs = clock.nowMs(),
                ),
            )
        }
    }

    private class Five(val news: NewsState, val feeds: List<FeedConfig>, val quotes: Map<String, com.marketsboard.data.repo.QuoteState>, val watch: List<WatchItem>, val fetches: List<FetchEvent>)
    private class Four(val signal: NetworkStatus, val offline: Boolean, val driving: DrivingStatus, val loc: Boolean)
}
