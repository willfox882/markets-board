package com.marketsboard.refresh

import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.news.FeedSource
import com.marketsboard.data.news.NewsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
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
    /** While any feed is failing, the next automatic pass comes after this long (the repository still honours each feed's own backoff). */
    val failedPollMs: Long = 60_000L,
    /** A burst of network signals is answered by one pass this long after the last one. */
    val networkSettleMs: Long = 1_000L,
    /** Divides every delay; only the sandbox "time speed" uses it. */
    val timeScale: () -> Double = { 1.0 },
)

/**
 * The news polling policy, same shape as the Markets refresh controller:
 * - everything runs only while [isActive] (Activity resumed) and restarts on every resume;
 * - cached headlines are loaded first (SWR), then an immediate pass, then one every [NewsPollConfig.pollMs];
 * - [network] coming back retries failed feeds at once (everything, when automatic passes were skipped while it was offline; bursts of signals collapse into one pass);
 * - a change of the watchlist or the feed list retags immediately and runs a pass for any new feed;
 * - manual refresh is forced (ignores minimum gap and backoff) and debounced.
 * Passes are serialised inside [NewsRepository], so overlapping triggers cost nothing.
 */
@OptIn(FlowPreview::class)
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

    /** Declared BEFORE the init block: its coroutines may start on another thread before the constructor ends. */
    private val outage = OutageTracker(network)

    init {
        scope.launch {
            // Cached headlines are available before the first resume.
            try { repo.hydrate() } catch (e: CancellationException) { throw e } catch (e: Throwable) { /* never fatal */ }
        }
        scope.launch {
            isActive.collectLatest { active ->
                if (active) coroutineScope {
                    launch {
                        // Back on screen after an outage that ended meanwhile: answer it now instead of waiting for the next poll.
                        safely { if (network.status.value != NetworkStatus.Offline && outage.pending()) returnPass() else pollOnce() }
                        loopPolls()
                    }
                    launch {
                        // Wait for the burst of signals to end, then answer it with one pass; a running pass is never cancelled by a later signal.
                        network.available.debounce(scaled(config.networkSettleMs)).collect {
                            if (network.status.value == NetworkStatus.Offline) return@collect
                            safely { if (outage.pending()) returnPass() else repo.refresh(onlyFailed = true) }
                        }
                    }
                    launch { while (true) { delay(scaled(config.agesTickMs)); repo.recompute() } }
                    launch {
                        combine(feeds.feeds, feeds.symbolFeeds, watch) { f, s, w -> Triple(f, s, w.map { it.symbol to it.name }) }
                            .distinctUntilChanged().drop(1).collect { safely { repo.onConfigChanged(); pollOnce() } }
                    }
                }
            }
        }
    }

    /** Every [NewsPollConfig.pollMs], but every [NewsPollConfig.failedPollMs] while a feed is failing, so the app recovers by itself within about a minute. */
    private suspend fun loopPolls() {
        while (true) {
            val failing = repo.state.value.feedStates.any { it.status == com.marketsboard.data.news.FeedStatus.Error }
            delay(scaled(if (failing) minOf(config.pollMs, config.failedPollMs) else config.pollMs))
            safely { pollOnce() }
        }
    }

    /** One automatic pass, unless the platform says there is no network at all (then it waits for the network signal). */
    private suspend fun pollOnce() {
        if (network.status.value == NetworkStatus.Offline) { outage.skippedWhileOffline(); return }
        if (outage.pending()) returnPass() else repo.refresh()
    }

    /** The network is back after an outage: everything that is due, plus failed feeds; backoff and Retry-After of the servers are still honoured. */
    private suspend fun returnPass() {
        outage.consume()
        repo.refresh(afterOutage = true)
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
