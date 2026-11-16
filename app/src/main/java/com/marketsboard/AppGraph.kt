package com.marketsboard

import com.marketsboard.data.Clock
import com.marketsboard.data.MarketsData
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.net.FetchLog
import com.marketsboard.data.net.HttpFetcher
import com.marketsboard.data.net.JvmHttpFetcher
import com.marketsboard.data.net.LoggingHttpFetcher
import com.marketsboard.data.provider.MarketDataProvider
import com.marketsboard.data.provider.StooqProvider
import com.marketsboard.data.provider.YahooChartProvider
import com.marketsboard.data.repo.QuoteRepository
import com.marketsboard.data.store.EditResult
import com.marketsboard.data.store.FileJsonCache
import com.marketsboard.data.store.WatchlistStore
import java.io.File

/**
 * Hand-built dependency graph with the real implementations. Takes a plain directory (not a Context) so it is
 * unit-testable on the JVM. [AppRuntime] puts the presenter and refresh controller on top of it.
 */
class AppGraph(filesDir: File, clock: Clock = Clock.System, rawFetcher: HttpFetcher = JvmHttpFetcher(clock)) {
    val fetchLog = FetchLog(capacity = 100)
    val watchlist = WatchlistStore(File(filesDir, "watchlist.json"), clock)
    val cache = FileJsonCache(File(filesDir, "cache"), clock)

    val yahoo: MarketDataProvider = YahooChartProvider(LoggingHttpFetcher(rawFetcher, fetchLog, "yahoo", clock), clock)
    val stooq: MarketDataProvider = StooqProvider(LoggingHttpFetcher(rawFetcher, fetchLog, "stooq", clock))

    val quotes = QuoteRepository(
        providers = listOf(yahoo, stooq),
        cache = cache,
        clock = clock,
        hints = { sym -> watchlist.items.value.firstOrNull { it.symbol == sym } },
    )

    /** What the Markets presenter edits through: watchlist + quotes + the provider used for search and validation. */
    val data = MarketsData(watchlist, quotes, yahoo)

    /**
     * Removal hook: removes the item and drops everything held for it (state, cache entries, series).
     * Use this instead of `watchlist.remove` so deleted instruments leave no cache behind.
     */
    suspend fun removeFromWatchlist(symbol: String): EditResult = data.remove(symbol)

    /** Swap hook: replaces the item keeping its position and forgets the old symbol's data. */
    suspend fun replaceInWatchlist(old: String, new: WatchItem): EditResult = data.replace(old, new)
}
