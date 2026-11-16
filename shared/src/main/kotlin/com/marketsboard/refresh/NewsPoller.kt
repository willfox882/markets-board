package com.marketsboard.refresh

import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.news.FeedSource
import com.marketsboard.data.news.NewsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Manual news refresh hook for the UI ("Refresh" in the header, "Retry" in the error band). */
interface NewsRefresher {
    fun refreshNow()

    companion object { val None: NewsRefresher = object : NewsRefresher { override fun refreshNow() {} } }
}

class NewsPollConfig(
    /** Time between automatic passes while the app is active. */
    val pollMs: Long = 10 * 60_000L,
    /** How often ages are recomputed (Fresh turns Stale without a fetch). */
    val agesTickMs: Long = 30_000L,
    /** Manual presses closer together than this are ignored. */
    val manualDebounceMs: Long = 5_000L,
    /** Divides every delay; only the sandbox "time speed" uses it. */
    val timeScale: () -> Double = { 1.0 },
)

/**
 * The news polling policy, same shape as the Markets refresh controller:
 * - everything runs only while [isActive] (Activity resumed) and restarts on every resume;
 * - cached headlines are loaded first (SWR), then an immediate pass, then one every [NewsPollConfig.pollMs];
 * - [network] coming back retries failed feeds at once;
 * - a change of the watchlist or the feed list retags immediately and runs a pass for any new feed;
 * - manual refresh is forced (ignores minimum gap and backoff) and debounced.
 * Passes are serialised inside [NewsRepository], so overlapping triggers cost nothing.
 */
class NewsPoller(
    private val scope: CoroutineScope,
    private val repo: NewsRepository,
    private val feeds: FeedSource,
    private val watch: StateFlow<List<WatchItem>>,
    private val isActive: Flow<Boolean>,
    private val network: NetworkSignal = NetworkSignal.None,
    private val clock: com.marketsboard.data.Clock = com.marketsboard.data.Clock.System,
    private val config: NewsPollConfig = NewsPollConfig(),
) : NewsRefresher {
    private var lastManualMono = Long.MIN_VALUE / 2

    init {
        scope.launch {
            // Cached headlines are available before the first resume.
            try { repo.hydrate() } catch (e: CancellationException) { throw e } catch (e: Throwable) { /* never fatal */ }
        }
        scope.launch {
            isActive.collectLatest { active ->
                if (active) coroutineScope {
                    launch { safely { repo.refresh() }; loopPolls() }
                    launch { network.available.collect { safely { repo.refresh(onlyFailed = true) } } }
                    launch { while (true) { delay(scaled(config.agesTickMs)); repo.recompute() } }
                    launch {
                        combine(feeds.feeds, feeds.symbolFeeds, watch) { f, s, w -> Triple(f, s, w.map { it.symbol to it.name }) }
                            .distinctUntilChanged().drop(1).collect { safely { repo.onConfigChanged(); repo.refresh() } }
                    }
                }
            }
        }
    }

    private suspend fun loopPolls() {
        while (true) {
            delay(scaled(config.pollMs))
            safely { repo.refresh() }
        }
    }

    private fun scaled(ms: Long): Long = (ms / config.timeScale().coerceAtLeast(0.01)).toLong().coerceAtLeast(1)

    private suspend fun safely(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Throwable) { /* the repository never throws; belt and braces */ }
    }

    override fun refreshNow() {
        val now = clock.monoMs()
        if (now - lastManualMono < config.manualDebounceMs) return
        lastManualMono = now
        scope.launch { safely { repo.refresh(force = true) } }
    }
}
