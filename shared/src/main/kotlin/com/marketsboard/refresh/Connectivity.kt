package com.marketsboard.refresh

import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.news.FeedStatus
import com.marketsboard.data.news.NewsOverall
import com.marketsboard.data.news.NewsSource
import com.marketsboard.data.news.NewsState
import com.marketsboard.data.repo.QuoteRepository
import com.marketsboard.data.repo.QuoteState
import com.marketsboard.data.repo.QuotesSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The decisions "are we offline?", pure so they can be tested without flows. */
object ConnectivityRules {
    /** Only a real "no route" counts: a timeout alone says a host is slow, not that the network is gone. */
    private fun isNoNetwork(k: ErrorKind?) = k == ErrorKind.Network

    /** Every watchlist symbol (all of them held a state) failed its latest refresh with a no-network error. */
    fun quotesDown(quotes: Map<String, QuoteState>, symbols: List<String>): Boolean {
        val states = symbols.mapNotNull { quotes[QuoteRepository.keyFor(it, ChartRange.D1)] }
        return symbols.isNotEmpty() && states.size == symbols.size && states.all { isNoNetwork(it.lastError?.kind) }
    }

    /** Every news feed that was asked is failing with a no-network error right now (per-feed state, so a recovered feed clears it by itself). */
    fun newsDown(news: NewsState): Boolean =
        news.overall !is NewsOverall.Loading && news.feedStates.isNotEmpty() && news.feedStates.all { it.status == FeedStatus.Error && isNoNetwork(it.errorKind) }

    /** What the Markets tab says: the platform has no network, or every symbol failed for lack of one. A news-only failure is not Markets' business. */
    fun marketsOffline(signal: NetworkStatus, quotes: Map<String, QuoteState>, symbols: List<String>): Boolean =
        signal == NetworkStatus.Offline || quotesDown(quotes, symbols)

    /** The whole app: Markets offline, or every news feed unreachable. */
    fun offline(signal: NetworkStatus, news: NewsState, quotes: Map<String, QuoteState>, symbols: List<String>): Boolean =
        marketsOffline(signal, quotes, symbols) || newsDown(news)
}

/**
 * The calm "offline" flags of the whole app. [offline] (anything is unreachable) drives the amber line on News and Settings and
 * the News tab's own look; [marketsOffline] (no network, or every symbol failing) drives the line and the dimmed rows on Markets.
 * Both clear by themselves as soon as the data layer succeeds again (each symbol is retried every minute, news every minute while
 * a feed is failing), with or without a network signal.
 */
class Connectivity(
    scope: CoroutineScope,
    network: NetworkSignal,
    news: NewsSource,
    quotes: QuotesSource,
    symbols: Flow<List<String>>,
) {
    val signal: StateFlow<NetworkStatus> = network.status

    private val both = combine(network.status, news.state, quotes.states, symbols) { sig, n, q, s ->
        ConnectivityRules.offline(sig, n, q, s) to ConnectivityRules.marketsOffline(sig, q, s)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false to false)

    val offline: StateFlow<Boolean> = both.map { it.first }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)
    val marketsOffline: StateFlow<Boolean> = both.map { it.second }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)
}
