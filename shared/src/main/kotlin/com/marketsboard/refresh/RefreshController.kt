package com.marketsboard.refresh

import com.marketsboard.core.MarketPhase
import com.marketsboard.data.Clock
import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.repo.QuotesSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Manual refresh hooks the UI uses ("Refresh" in the header, "Retry" on a failed row). */
interface Refresher {
    /** Refreshes every watchlist symbol now (forced, still honouring the repository cooldown). */
    fun refreshAll()

    fun refresh(symbol: String)

    companion object { val None: Refresher = object : Refresher { override fun refreshAll() {}; override fun refresh(symbol: String) {} } }
}

/**
 * Tunables of the refresh policy. [timeScale] only exists for the sandbox ("time speed-up"): delays are divided by
 * it, and the sandbox clock runs that much faster, so the policy behaves identically, just quicker. Production uses 1.
 */
class RefreshConfig(
    val liveIntervalMs: Long = 60_000,
    val closedIntervalMs: Long = 300_000,
    /** A symbol is never polled automatically more often than this, whatever else is configured. */
    val minGapMs: Long = 60_000,
    val agesTickMs: Long = 15_000,
    val timeScale: () -> Double = { 1.0 },
)

/**
 * The refresh policy of the Markets tab (M2). Platform code supplies [isActive] (Android: Activity resumed) and the
 * watchlist symbols; everything else is decided here, so it is testable with a test dispatcher and a fake clock.
 *
 * Policy:
 * - Only while [isActive]. Going inactive cancels the loop (in-flight fetches included, the repository restores any
 *   Loading state); becoming active again starts over with an immediate pass.
 * - Immediate pass on start, on resume and whenever the watchlist changes: every symbol whose last completed
 *   attempt is at least [RefreshConfig.minGapMs] old (a newly added symbol has none, so it goes at once).
 * - Then a tick every [RefreshConfig.liveIntervalMs]: a symbol is due when its last attempt is at least 60 s old if
 *   it is live (market REGULAR, PRE or POST, or no quote yet, or the last refresh failed) and at least 5 min old
 *   when its market is closed. So with every visible market closed the network sees one pass per 5 minutes.
 * - Never faster than 60 s per symbol for automatic polls. Manual refresh (forced) is the user's call.
 * - While the repository is cooling down after a 429 or 5xx, no automatic pass happens at all (the repository would
 *   only answer "rate limited"); the next tick after the cooldown retries.
 * - At most 4 fetches run at once: the repository enforces it for every pass, which is one `refresh` call.
 * - `recomputeAges()` every 15 s while active, so Fresh turns Stale without a refresh.
 * - [onResume] runs at every (re)start of the active loop (Android: `WatchlistStore.flushIfDirty`).
 * Timing uses the injected monotonic clock for decisions and coroutine `delay` for waiting.
 */
class RefreshController(
    private val scope: CoroutineScope,
    private val quotes: QuotesSource,
    symbols: Flow<List<String>>,
    isActive: StateFlow<Boolean>,
    private val clock: Clock,
    private val config: RefreshConfig = RefreshConfig(),
    private val onResume: () -> Unit = {},
) : Refresher {
    private val lastDone = HashMap<String, Long>()
    private val lock = Any()
    @Volatile private var latest: List<String> = emptyList()

    init {
        scope.launch {
            combine(isActive, symbols.distinctUntilChanged()) { a, s -> latest = s; a to s }
                .distinctUntilChanged()
                .collectLatest { (active, syms) ->
                    if (!active || syms.isEmpty()) return@collectLatest
                    safely { onResume() }
                    pass(syms, immediate = true)
                    while (true) {
                        pause(config.liveIntervalMs)
                        pass(syms, immediate = false)
                    }
                }
        }
        scope.launch {
            isActive.collectLatest { active ->
                if (!active) return@collectLatest
                while (true) {
                    safely { quotes.recomputeAges() }
                    pause(config.agesTickMs)
                }
            }
        }
    }

    override fun refreshAll() = launchManual(latest)

    override fun refresh(symbol: String) = launchManual(listOf(symbol))

    private fun launchManual(list: List<String>) {
        if (list.isEmpty()) return
        scope.launch {
            safely { quotes.refresh(list, ChartRange.D1, force = true) }
            mark(list)
        }
    }

    private suspend fun pass(syms: List<String>, immediate: Boolean) {
        safely { quotes.hydrate(syms, ChartRange.D1) }
        if (quotes.cooldownRemainingMs() > 0) return
        val now = clock.monoMs()
        val wall = clock.nowMs()
        val states = quotes.states.value
        val due = syms.filter { s ->
            val last = synchronized(lock) { lastDone[s] } ?: return@filter true
            val gap = now - last
            val need = if (immediate) config.minGapMs else {
                val interval = if (MarketPhase.wantsLiveRate(states[s], wall)) config.liveIntervalMs else config.closedIntervalMs
                maxOf(interval, config.minGapMs)
            }
            gap >= need
        }
        if (due.isEmpty()) return
        // Completion is recorded only when the call returns, so a cancelled pass (pause, list change) is retried.
        safely { quotes.refresh(due, ChartRange.D1, force = false) }
        mark(due)
    }

    private fun mark(list: List<String>) {
        val t = clock.monoMs()
        synchronized(lock) { list.forEach { lastDone[it] = t } }
    }

    private suspend fun pause(ms: Long) = delay((ms / config.timeScale().coerceAtLeast(0.001)).toLong().coerceAtLeast(1))

    /** Crash-proof boundary: nothing thrown by the data layer may kill the loop (cancellation still propagates). */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The repository never throws; if something does, the next tick tries again.
        }
    }
}
