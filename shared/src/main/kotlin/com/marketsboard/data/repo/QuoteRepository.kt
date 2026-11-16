package com.marketsboard.data.repo

import com.marketsboard.data.Clock
import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.Quote
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.provider.MarketDataProvider
import com.marketsboard.data.store.FileJsonCache
import com.marketsboard.data.store.QuoteCodec
import com.marketsboard.data.store.Symbols
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Quotes for the UI. Stale-while-revalidate: [hydrate] puts cached quotes into [states] immediately, [refresh] then
 * updates them from the network in the background (at most [RepoConfig.maxConcurrent] fetches at once).
 *
 * Rules:
 * - States are keyed by [keyFor]: the plain symbol for D1 (the watchlist range), `SYMBOL@5D` etc for other ranges,
 *   so a detail chart never overwrites the list row.
 * - Providers are tried in order. Any error (including NotFound) moves on to the next provider that
 *   [MarketDataProvider.supports] the symbol AND range, so a symbol with no Stooq mapping, or a non-1D range, simply
 *   has no fallback. The error reported is the first provider's.
 * - A FALLBACK result (not from the first provider) is merged onto the held quote: price, provider time, high and low
 *   are taken, previous close and series are kept, so the row keeps its change and sparkline. With no held quote the
 *   fallback quote is used as is. A fallback quote more than a day older than the held one, or more than a day in the
 *   future, is rejected. The caveat: after a session roll the kept previous close is one session old.
 *   A quote sourced from a fallback is never throttled by the minimum refetch interval, so the primary is retried soon.
 * - 429 and 5xx put THAT provider in cooldown (Retry-After honoured up to [RepoConfig.maxRetryAfterMs], else
 *   exponential 30 s, 60 s... capped at 15 min, reset by a success). One failing window counts once, however many
 *   symbols fail inside it. Cooldowns are repository wide and apply even to forced refreshes.
 * - A failure never discards the last good quote: it is kept and marked Stale.
 * - Ages, minimum refetch and cooldowns use the MONOTONIC clock, so a device clock that jumps can neither keep data
 *   fresh forever nor block refreshes. The wall clock is used only for the persisted cache time and to detect
 *   skew ([QuoteState.clockSkew]). A cache entry whose age cannot be trusted is Stale with an unknown age.
 * - After a Network-kind failure (or two timeouts) the not-yet-started symbols of the same [refresh] call are failed
 *   at once instead of each waiting for its own timeout.
 * - Blank currency, exchange or name in a result is filled from [hints] (the WatchItem).
 * - Call [recomputeAges] periodically so Fresh quotes turn Stale without a refresh; call [forget] when an instrument
 *   is removed from the watchlist (AppGraph.removeFromWatchlist does both removal and forget).
 */
class QuoteRepository(
    private val providers: List<MarketDataProvider>,
    private val cache: FileJsonCache,
    private val clock: Clock,
    private val config: RepoConfig = RepoConfig(),
    private val hints: (String) -> WatchItem? = { null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : QuotesSource {
    private val _states = MutableStateFlow<Map<String, QuoteState>>(emptyMap())
    override val states: StateFlow<Map<String, QuoteState>> = _states.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    /** True while any network refresh is running. */
    override val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val gate = Semaphore(config.maxConcurrent.coerceAtLeast(1))
    private val inFlight = HashMap<String, CompletableDeferred<Unit>>()
    private var activeCount = 0
    private val activeLock = Any()

    private val cooldownUntil = HashMap<String, Long>() // monotonic ms
    private val failureStreak = HashMap<String, Int>()
    private val health = Any()

    private val forgotten = HashSet<String>()

    private fun gone(key: String) = synchronized(forgotten) { key in forgotten }

    private val primaryId: String? get() = providers.firstOrNull()?.id

    override fun stateOf(symbol: String, range: ChartRange): QuoteState? =
        _states.value[keyFor(Symbols.normalize(symbol) ?: symbol, range)]

    /** Milliseconds until some provider becomes usable again, or 0 when at least one is usable now. */
    override fun cooldownRemainingMs(): Long = synchronized(health) {
        val now = clock.monoMs()
        val remaining = providers.mapNotNull { cooldownUntil[it.id] }.map { it - now }
        if (remaining.size < providers.size || remaining.isEmpty()) 0L else remaining.min().coerceAtLeast(0L)
    }

    /** Loads cached quotes for symbols that have no state yet. Cheap; call before the first [refresh]. */
    override suspend fun hydrate(symbols: List<String>, range: ChartRange) {
        for (raw in symbols) {
            val sym = Symbols.normalize(raw) ?: continue
            val key = keyFor(sym, range)
            if (_states.value.containsKey(key)) continue
            val entry = withContext(ioDispatcher) { runCatching { cache.get(cacheKey(sym, range), allowExpired = true) }.getOrNull() } ?: continue
            val quote = QuoteCodec.decode(entry.payload)?.takeIf { it.range == range } ?: continue
            val wallNow = clock.nowMs()
            val wallAge = wallNow - entry.savedAtMs
            // A negative age means the cache was written by a clock ahead of the current one: the age is unknown.
            val age: Long? = if (wallAge < 0) null else wallAge
            if (age != null && age > config.maxStaleMs) continue
            val anchor = age?.let { clock.monoMs() - it }
            val skew = wallAge < -config.skewToleranceMs || skewed(quote, wallNow)
            val status = statusFor(range, age, hadError = false)
            _states.update { m -> if (m.containsKey(key) || gone(key)) m else m + (key to QuoteState(quote, status, entry.savedAtMs, null, skew, anchor)) }
        }
    }

    /**
     * Refreshes [symbols] for [range]. Suspends until done; cancelling the caller cancels the fetches and restores
     * any state left in Loading. A symbol already being refreshed is awaited, not fetched twice.
     */
    override suspend fun refresh(symbols: List<String>, range: ChartRange, force: Boolean) {
        val list = symbols.mapNotNull { Symbols.normalize(it) }.distinct()
        if (list.isEmpty()) return
        synchronized(forgotten) { list.forEach { forgotten.remove(keyFor(it, range)) } }
        hydrate(list, range)
        val batch = Batch()
        coroutineScope {
            list.map { s -> async { refreshOne(s, range, force, batch) } }.awaitAll()
        }
    }

    /** Recomputes Fresh/Stale and clock skew for every held quote. Does not touch the network. */
    override fun recomputeAges() {
        val wallNow = clock.nowMs()
        val mono = clock.monoMs()
        _states.update { m ->
            m.mapValues { (_, st) ->
                val q = st.quote
                if (q == null) st else st.copy(
                    status = statusFor(q.range, st.anchorMono?.let { (mono - it).coerceAtLeast(0) }, st.lastError != null),
                    clockSkew = skewed(q, wallNow) || (st.clockSkew && st.anchorMono == null),
                )
            }
        }
    }

    /**
     * Drops everything held for [symbol] (all ranges): states, cached quotes and series. Call it when the instrument
     * leaves the watchlist, otherwise cache files and series for deleted items would linger until evicted.
     * A fetch already running for it will not bring the state back.
     */
    override suspend fun forget(symbol: String) {
        val sym = Symbols.normalize(symbol) ?: return
        val keys = ChartRange.entries.map { keyFor(sym, it) }
        synchronized(forgotten) { forgotten.addAll(keys) }
        _states.update { m -> m - keys.toSet() }
        withContext(ioDispatcher) { ChartRange.entries.forEach { r -> runCatching { cache.remove(cacheKey(sym, r)) } } }
    }

    // ---- internals ---------------------------------------------------------------------------------------------

    /** Shared by the symbols of one [refresh] call to short-circuit after the network proved to be down. */
    private class Batch {
        val offline = AtomicReference<FetchResult.Err?>(null)
        val timeouts = AtomicInteger(0)

        fun skipError(): FetchResult.Err? =
            offline.get() ?: if (timeouts.get() >= 2) FetchResult.Err(ErrorKind.Timeout, "skipped after repeated timeouts") else null

        fun observe(e: FetchResult.Err?) {
            when (e?.kind) {
                ErrorKind.Network, ErrorKind.ClockOrTls -> offline.compareAndSet(null, FetchResult.Err(e.kind, "skipped after network failure: ${e.message}"))
                ErrorKind.Timeout -> timeouts.incrementAndGet()
                else -> Unit
            }
        }
    }

    private class Chain(val quote: Quote?, val err: FetchResult.Err?, val fromFallback: Boolean)

    private suspend fun refreshOne(sym: String, range: ChartRange, force: Boolean, batch: Batch) {
        val key = keyFor(sym, range)
        val mine = CompletableDeferred<Unit>()
        val existing = synchronized(inFlight) { inFlight[key] ?: run { inFlight[key] = mine; null } }
        if (existing != null) {
            existing.await()
            return
        }
        try {
            doRefresh(sym, range, key, force, batch)
        } finally {
            synchronized(inFlight) { inFlight.remove(key) }
            mine.complete(Unit)
        }
    }

    private fun isFallbackSourced(q: Quote): Boolean = q.source.isNotEmpty() && primaryId != null && q.source != primaryId

    private suspend fun doRefresh(sym: String, range: ChartRange, key: String, force: Boolean, batch: Batch) {
        val before = _states.value[key]
        val beforeQuote = before?.quote
        if (!force && before != null && beforeQuote != null && before.status == QuoteStatus.Fresh) {
            val age = before.anchorMono?.let { (clock.monoMs() - it).coerceAtLeast(0) }
            // A fallback sourced quote is never throttled: the primary should be retried on the next cycle.
            if (age != null && age < config.minRefetchMs(range) && !isFallbackSourced(beforeQuote)) return
        }

        if (beforeQuote == null) {
            _states.update { it + (key to QuoteState(null, QuoteStatus.Loading)) }
        }
        setActive(+1)
        var completed = false
        try {
            val chain = gate.withPermit {
                val skip = batch.skipError()
                if (skip != null) Chain(null, skip, false) else fetchChain(sym, range).also { batch.observe(it.err) }
            }
            if (gone(key)) {
                completed = true
                _states.update { m -> m - key }
                return
            }
            if (chain.quote != null) applySuccess(key, sym, range, chain) else applyFailure(key, chain.err ?: FetchResult.Err(ErrorKind.Unknown, "no result"))
            completed = true
        } finally {
            setActive(-1)
            if (!completed) {
                // Cancelled (or crashed) mid-flight: never leave a symbol stuck in Loading.
                _states.update { m ->
                    if (m[key]?.status == QuoteStatus.Loading) (if (before == null) m - key else m + (key to before)) else m
                }
            }
        }
    }

    private fun setActive(delta: Int) = synchronized(activeLock) {
        activeCount += delta
        _refreshing.value = activeCount > 0
    }

    private suspend fun fetchChain(sym: String, range: ChartRange): Chain {
        var firstErr: FetchResult.Err? = null
        var coolingRemaining: Long? = null
        var tried = 0
        for ((index, p) in providers.withIndex()) {
            if (!p.supports(sym, range)) continue
            val now = clock.monoMs()
            val until = synchronized(health) { cooldownUntil[p.id] }
            if (until != null && until > now) {
                coolingRemaining = minOf(coolingRemaining ?: Long.MAX_VALUE, until - now)
                continue
            }
            tried++
            val r: FetchResult<Quote> = try {
                p.quote(sym, range)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                FetchResult.Err(ErrorKind.Unknown, "${p.id}: ${e.javaClass.simpleName}: ${e.message}")
            }
            when (r) {
                is FetchResult.Ok -> {
                    onProviderSuccess(p.id)
                    val q = if (r.value.source.isBlank()) r.value.copy(source = p.id) else r.value
                    return Chain(q, null, fromFallback = index > 0)
                }
                is FetchResult.Err -> {
                    onProviderError(p.id, r)
                    if (firstErr == null) firstErr = r
                }
            }
        }
        firstErr?.let { return Chain(null, it, false) }
        return if (coolingRemaining != null) {
            Chain(null, FetchResult.Err(ErrorKind.RateLimited(coolingRemaining), "providers cooling down"), false)
        } else {
            Chain(null, FetchResult.Err(ErrorKind.Unknown, "no provider available for $sym (tried $tried)"), false)
        }
    }

    private fun onProviderSuccess(id: String) = synchronized(health) {
        failureStreak.remove(id)
        cooldownUntil.remove(id)
    }

    private fun onProviderError(id: String, e: FetchResult.Err) {
        val kind = e.kind
        val throttled = kind is ErrorKind.RateLimited
        val serverTrouble = kind is ErrorKind.Http && kind.code >= 500
        if (!throttled && !serverTrouble) return
        synchronized(health) {
            val now = clock.monoMs()
            // Concurrent symbols failing in the same window are one event: the streak counts windows, not symbols.
            if ((cooldownUntil[id] ?: Long.MIN_VALUE) > now) return
            val n = (failureStreak[id] ?: 0) + 1
            failureStreak[id] = n
            val backoff = minOf(config.backoffBaseMs shl (n - 1).coerceAtMost(20), config.backoffCapMs)
            val retryAfter = (kind as? ErrorKind.RateLimited)?.retryAfterMs?.takeIf { it > 0 }
            val delay = if (retryAfter != null) minOf(maxOf(retryAfter, if (n > 1) backoff else 0L), config.maxRetryAfterMs) else backoff
            cooldownUntil[id] = now + delay
        }
    }

    private suspend fun applySuccess(key: String, sym: String, range: ChartRange, chain: Chain) {
        val wallNow = clock.nowMs()
        val mono = clock.monoMs()
        val fetched = chain.quote!!
        val held = _states.value[key]?.quote
        var quote = fetched
        if (chain.fromFallback && held != null && held.range == range) {
            val day = config.fallbackSanityMs
            if (held.asOfEpochMs - fetched.asOfEpochMs > day || fetched.asOfEpochMs > wallNow + day) {
                applyFailure(key, FetchResult.Err(ErrorKind.Parse, "fallback quote rejected: provider time out of range"))
                return
            }
            // Keep the held previous close, series, names; take only what a price snapshot can supply.
            quote = held.copy(
                price = fetched.price,
                asOfEpochMs = fetched.asOfEpochMs,
                dayHigh = fetched.dayHigh ?: held.dayHigh,
                dayLow = fetched.dayLow ?: held.dayLow,
                marketState = fetched.marketState ?: held.marketState,
                source = fetched.source,
            )
        }
        quote = fill(quote, hints(sym))
        val skew = skewed(quote, wallNow)
        _states.update { m -> if (gone(key)) m else m + (key to QuoteState(quote, QuoteStatus.Fresh, wallNow, null, skew, mono)) }
        if (!gone(key)) {
            withContext(ioDispatcher) {
                runCatching { cache.put(cacheKey(sym, range), QuoteCodec.encode(quote), config.maxStaleMs) }
            }
        }
    }

    private fun applyFailure(key: String, err: FetchResult.Err) {
        val mono = clock.monoMs()
        _states.update { m ->
            if (gone(key)) return@update m
            val st = m[key]
            val q = st?.quote
            val next = if (q != null) {
                val age = st.anchorMono?.let { (mono - it).coerceAtLeast(0) }
                st.copy(status = statusFor(q.range, age, hadError = true), lastError = err)
            } else {
                QuoteState(null, QuoteStatus.Error(err.kind), null, err)
            }
            m + (key to next)
        }
    }

    /** Unknown age (null) is never Fresh. */
    private fun statusFor(range: ChartRange, ageMs: Long?, hadError: Boolean): QuoteStatus = when {
        ageMs == null -> QuoteStatus.Stale(0, ageKnown = false)
        !hadError && ageMs <= config.staleAfterMs(range) -> QuoteStatus.Fresh
        else -> QuoteStatus.Stale(ageMs)
    }

    private fun skewed(q: Quote, wallNow: Long) = wallNow < q.asOfEpochMs - config.skewToleranceMs

    private fun fill(q: Quote, hint: WatchItem?): Quote {
        if (hint == null) return q
        return q.copy(
            name = if (q.name.isBlank() || q.name.equals(q.symbol, ignoreCase = true)) hint.name.ifBlank { q.name } else q.name,
            currency = q.currency.ifBlank { hint.currency },
            exchange = q.exchange.ifBlank { hint.exchange },
        )
    }

    companion object {
        fun keyFor(symbol: String, range: ChartRange): String = if (range == ChartRange.D1) symbol else "$symbol@${range.label}"
        fun cacheKey(symbol: String, range: ChartRange): String = "quote/$symbol/${range.label}"
    }
}
