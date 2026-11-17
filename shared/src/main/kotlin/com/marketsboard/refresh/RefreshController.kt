package com.marketsboard.refresh

import com.marketsboard.core.PollPolicy
import com.marketsboard.data.Clock
import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.repo.QuotesSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    /** How often the loop wakes up to see which symbols are due (the live interval). */
    val tickMs: Long = PollPolicy.LIVE_MS,
    /** A symbol is never polled automatically more often than this, whatever else is configured. */
    val minGapMs: Long = 60_000,
    val agesTickMs: Long = 15_000,
    /** Manual Refresh or Retry presses closer together than this are ignored. */
    val manualDebounceMs: Long = 5_000,
    /** A burst of network signals (available, then validated, a flapping hotspot) is answered by ONE pass this long after the last one. */
    val networkSettleMs: Long = 1_000,
    /** A difference between wall and monotonic time larger than this after a pause counts as the device sleeping. */
    val suspendToleranceMs: Long = 5_000,
    val timeScale: () -> Double = { 1.0 },
)

/**
 * The refresh policy of the Markets tab. Platform code supplies [isActive] (Android: Activity resumed), the
 * watchlist symbols and optionally a [NetworkSignal]; everything else is decided here, so it is testable with a test
 * dispatcher and a fake clock.
 *
 * Policy:
 * - Only while [isActive]. Going inactive cancels the loop (in-flight fetches included, the repository restores any
 *   Loading state); becoming active again starts over with an immediate pass.
 * - On resume after more than [RefreshConfig.minGapMs] away (wall or monotonic, whichever is larger) every symbol is
 *   due at once (the minimum gap is ignored), and when the monotonic clock did not count the sleep (wall minus
 *   monotonic above the tolerance) the held quotes are aged by the difference ([QuotesSource.ageShift]) so they show
 *   as stale until refreshed. Ages are always recomputed before the first pass.
 * - Immediate pass on start, resume and whenever the SET of watchlist symbols changes (a pure reorder does nothing,
 *   not even restart the timer): every symbol whose last completed attempt is at least [RefreshConfig.minGapMs] old.
 * - Then a tick every [RefreshConfig.tickMs]: a symbol is due per [PollPolicy]: 60 s for a live (REGULAR) market,
 *   unknown state, no quote yet or a failed last refresh; 5 min for PRE and POST; for a closed market 30 min or the
 *   next session start when that is sooner. Never faster than 60 s per symbol automatically.
 * - A network-available signal triggers (after [RefreshConfig.networkSettleMs], bursts collapse into one) an immediate pass for
 *   symbols with no quote, a failed last refresh or a stale quote, ignoring the minimum gap. While [NetworkSignal.status] is
 *   Offline the automatic passes do nothing.
 * - While the repository cools down (429 or 5xx) no automatic pass happens; the next tick after it retries.
 * - At most 4 fetches at once (the repository enforces it per `refresh` call).
 * - `recomputeAges()` every 15 s while active.
 * - [onResume] runs at every (re)start of the active loop (Android: `WatchlistStore.flushIfDirty`).
 * - Manual refresh and Retry are forced, still honour the cooldown, and are debounced to one per 5 s.
 * Timing uses the injected monotonic clock for decisions and coroutine `delay` for waiting. A coroutine `delay` does
 * not run while the device is in deep sleep; that is covered by the resume rule above.
 */
@OptIn(FlowPreview::class)
class RefreshController(
    private val scope: CoroutineScope,
    private val quotes: QuotesSource,
    symbols: Flow<List<String>>,
    isActive: StateFlow<Boolean>,
    private val clock: Clock,
    private val config: RefreshConfig = RefreshConfig(),
    private val onResume: () -> Unit = {},
    private val network: NetworkSignal = NetworkSignal.None,
) : Refresher {
    private val lastDone = HashMap<String, Long>()
    private val lastManual = HashMap<String, Long>()
    private val lock = Any()
    @Volatile private var latest: List<String> = emptyList()

    /** Whether the network went away since the last pass that followed its return: on return every symbol is retried. */
    private val outage = OutageTracker(network)

    private var wasActive = false
    private var pausedWall: Long? = null
    private var pausedMono: Long? = null

    init {
        scope.launch {
            symbols.collect { latest = it }
        }
        scope.launch {
            combine(isActive, symbols.map { it.toSet() }.distinctUntilChanged()) { a, s ->
                noteTransition(a)
                a to s
            }
                .distinctUntilChanged()
                .collectLatest { (active, set) ->
                    if (!active || set.isEmpty()) return@collectLatest
                    val syms = ordered(set)
                    safely { onResume() }
                    safely { quotes.recomputeAges() }
                    // Back on screen after the network went away and returned meanwhile: everything is due, as for a return signal.
                    if (network.status.value != NetworkStatus.Offline && outage.consume()) synchronized(lock) { lastDone.clear() }
                    pass(syms, immediate = true)
                    while (true) {
                        pause(config.tickMs)
                        pass(ordered(set), immediate = false)
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
        scope.launch {
            isActive.collectLatest { active ->
                if (active) {
                    // Wait for the burst of signals to end, then answer it with one pass; a running pass is never cancelled by a later signal.
                    network.available.debounce((config.networkSettleMs / config.timeScale().coerceAtLeast(0.001)).toLong().coerceAtLeast(1)).collect { networkPass() }
                }
            }
        }
    }

    /** Records when the app went inactive and, on resume, applies the "was away" rules. Runs inside the flow. */
    private fun noteTransition(active: Boolean) {
        if (active == wasActive) return
        wasActive = active
        if (!active) {
            pausedWall = clock.nowMs()
            pausedMono = clock.monoMs()
            return
        }
        val pw = pausedWall ?: return
        val pm = pausedMono ?: return
        val wall = clock.nowMs() - pw
        val mono = clock.monoMs() - pm
        val suspend = wall - mono
        if (suspend > config.suspendToleranceMs) safely { quotes.ageShift(suspend) }
        if (maxOf(wall, mono) > config.minGapMs) synchronized(lock) { lastDone.clear() }
    }

    private fun ordered(set: Set<String>): List<String> = latest.filter { it in set }.ifEmpty { set.toList() }

    override fun refreshAll() = launchManual("*", latest)

    override fun refresh(symbol: String) = launchManual(symbol, listOf(symbol))

    private fun launchManual(key: String, list: List<String>) {
        if (list.isEmpty()) return
        val now = clock.monoMs()
        synchronized(lock) {
            val last = lastManual[key]
            if (last != null && now - last < config.manualDebounceMs) return
            lastManual[key] = now
        }
        scope.launch {
            safely { quotes.refresh(list, ChartRange.D1, force = true) }
            mark(list)
        }
    }

    /** Network came back: retry what is broken, now, whatever the minimum gap says. */
    private suspend fun networkPass() {
        if (network.status.value == NetworkStatus.Offline) return
        if (quotes.cooldownRemainingMs() > 0) return // a rate limit or server trouble is honoured even right after a return
        val states = quotes.states.value
        val wasOffline = outage.consume()
        val broken = if (wasOffline) latest else latest.filter { s ->
            val st = states[com.marketsboard.data.repo.QuoteRepository.keyFor(s, ChartRange.D1)]
            st == null || st.quote == null || st.lastError != null || st.status !is com.marketsboard.data.repo.QuoteStatus.Fresh
        }
        if (broken.isEmpty()) return
        safely { quotes.refresh(broken, ChartRange.D1, force = false) }
        mark(broken)
    }

    private suspend fun pass(syms: List<String>, immediate: Boolean) {
        safely { quotes.hydrate(syms, ChartRange.D1) }
        // No network at all: no automatic fetches (no timeouts, no spinners). The next network signal retries at once.
        if (network.status.value == NetworkStatus.Offline) { outage.skippedWhileOffline(); return }
        if (quotes.cooldownRemainingMs() > 0) return
        val now = clock.monoMs()
        val wall = clock.nowMs()
        val states = quotes.states.value
        val due = syms.filter { s ->
            val last = synchronized(lock) { lastDone[s] } ?: return@filter true
            val gap = now - last
            val need = if (immediate) config.minGapMs else {
                val st = states[com.marketsboard.data.repo.QuoteRepository.keyFor(s, ChartRange.D1)]
                val q = st?.quote
                val interval = if (q == null || st.lastError != null) PollPolicy.LIVE_MS else PollPolicy.nextIntervalMs(q, wall, gap)
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
