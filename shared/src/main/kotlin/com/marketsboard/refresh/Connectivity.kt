package com.marketsboard.refresh

import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.ErrorKind
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
import kotlinx.coroutines.flow.stateIn

/** The decision "are we offline?", pure so it can be tested without flows. */
object ConnectivityRules {
    private fun isNetworkKind(k: ErrorKind) = k == ErrorKind.Network || k == ErrorKind.Timeout

    /**
     * Offline when the platform says there is no network, or when the data layer shows that EVERYTHING it tried in its latest
     * pass failed for a network reason: all watchlist symbols (nothing succeeded since the failure), or every news feed
     * (`lastPassFailed`) with a network-kind error. One slow host is not "offline".
     */
    fun offline(signal: NetworkStatus, news: NewsState, quotes: Map<String, QuoteState>, symbols: List<String>): Boolean {
        if (signal == NetworkStatus.Offline) return true
        val newsDown = news.lastPassFailed && (news.errorKind?.let(::isNetworkKind) ?: false) && news.overall !is NewsOverall.Loading
        val states = symbols.mapNotNull { quotes[QuoteRepository.keyFor(it, ChartRange.D1)] }
        val quotesDown = symbols.isNotEmpty() && states.size == symbols.size && states.all { s -> s.lastError?.kind?.let(::isNetworkKind) ?: false }
        return newsDown || quotesDown
    }
}

/**
 * One calm "offline" flag for the whole app: [offline] drives a single amber banner ("Offline, showing last data") while
 * the screens keep showing the last data without error bands or spinners. It clears by itself when the signal says Online
 * and a pass succeeds, or when a pass succeeds at all.
 */
class Connectivity(
    scope: CoroutineScope,
    network: NetworkSignal,
    news: NewsSource,
    quotes: QuotesSource,
    symbols: Flow<List<String>>,
) {
    val signal: StateFlow<NetworkStatus> = network.status

    val offline: StateFlow<Boolean> = combine(network.status, news.state, quotes.states, symbols) { sig, n, q, s ->
        ConnectivityRules.offline(sig, n, q, s)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)
}
