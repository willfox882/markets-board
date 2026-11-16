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
 *   [MarketDataProvider.supports] the symbol, so a symbol with no Stooq mapping simply has no fallback.
 *   The error reported is the first provider's.
 * - 429 and 5xx put THAT provider in cooldown (Retry-After honoured, else exponential 30 s, 60 s... capped at 15 min,
 *   reset by a success). Cooldowns are repository wide, shared by all symbols, and apply even to forced refreshes.
 * - A failure never discards the last good quote: it is kept and marked Stale(age since last success).
 * - A result whose provider time is OLDER than the quote already held is ignored (out-of-order protection).
 * - Blank currency, exchange or name in a result (Stooq, partial Yahoo meta) is filled from [hints] (the WatchItem).
 * - Ages are computed from [clock]; call [recomputeAges] periodically so Fresh quotes turn Stale without a refresh.
 */
class QuoteRepository(
    private val providers: List<MarketDataProvider>,
    private val cache: FileJsonCache,
    private val clock: Clock,
    private val config: RepoConfig = RepoConfig(),
    private val hints: (String) -> WatchItem? = { null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _states = MutableStateFlow<Map<String, QuoteState>>(emptyMap())
    val states: StateFlow<Map<String, QuoteState>> = _states.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    /** True while any network refresh is running. */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val gate = Semaphore(config.maxConcurrent.coerceAtLeast(1))
    private val inFlight = HashMap<String, CompletableDeferred<Unit>>()
    private val activeCount = AtomicInteger(0)

    private val cooldownUntil = HashMap<String, Long>()
    private val failureStreak = HashMap<String, Int>()
    private val health = Any()

    fun stateOf(symbol: String, range: ChartRange = ChartRange.D1): QuoteState? =
        _states.value[keyFor(Symbols.normalize(symbol) ?: symbol, range)]

    /** Milliseconds until the next provider becomes usable again, or 0. For diagnostics and the refresh policy. */
    fun cooldownRemainingMs(): Long = synchronized(health) {
        val now = clock.nowMs()
        val remaining = providers.mapNotNull { cooldownUntil[it.id] }.map { it - now }
        if (remaining.size < providers.size || remaining.isEmpty()) 0L else remaining.min().coerceAtLeast(0L)
    }

    /** Loads cached quotes for symbols that have no state yet. Cheap; call before the first [refresh]. */
    suspend fun hydrate(symbols: List<String>, range: ChartRange = ChartRange.D1) {
        for (raw in symbols) {
            val sym = Symbols.normalize(raw) ?: continue
            val key = keyFor(sym, range)
            if (_states.value.containsKey(key)) continue
            val entry = withContext(ioDispatcher) { runCatching { cache.get(cacheKey(sym, range), allowExpired = true) }.getOrNull() } ?: continue
            val quote = QuoteCodec.decode(entry.payload)?.takeIf { it.range == range } ?: continue
            val now = clock.nowMs()
            val age = (now - entry.savedAtMs).coerceAtLeast(0)
            if (age > config.maxStaleMs) continue
            val status = if (age <= config.staleAfterMs(range)) QuoteStatus.Fresh else QuoteStatus.Stale(age)
            _states.update { m -> if (m.containsKey(key)) m else m + (key to QuoteState(quote, status, entry.savedAtMs)) }
        }
    }

    /**
     * Refreshes [symbols] for [range]. Suspends until done; cancelling the caller cancels the fetches and restores
     * any state left in Loading. A symbol already being refreshed is awaited, not fetched twice.
     */
    suspend fun refresh(symbols: List<String>, range: ChartRange = ChartRange.D1, force: Boolean = false) {
        val list = symbols.mapNotNull { Symbols.normalize(it) }.distinct()
        if (list.isEmpty()) return
        hydrate(list, range)
        coroutineScope {
            list.map { s -> async { refreshOne(s, range, force) } }.awaitAll()
        }
    }

    /** Recomputes Fresh/Stale from the clock for every held quote. Does not touch the network. */
    fun recomputeAges() {
        val now = clock.nowMs()
        _states.update { m ->
            m.mapValues { (_, st) ->
                val q = st.quote
                val last = st.lastSuccessMs
                if (q == null || last == null) st else st.copy(status = statusFor(q.range, now - last, st.lastError != null))
            }
        }
    }

    // ---- internals ---------------------------------------------------------------------------------------------

    private suspend fun refreshOne(sym: String, range: ChartRange, force: Boolean) {
        val key = keyFor(sym, range)
        val mine = CompletableDeferred<Unit>()
        val existing = synchronized(inFlight) { inFlight[key] ?: run { inFlight[key] = mine; null } }
        if (existing != null) {
            existing.await()
            return
        }
        try {
            doRefresh(sym, range, key, force)
        } finally {
            synchronized(inFlight) { inFlight.remove(key) }
            mine.complete(Unit)
        }
    }

    private suspend fun doRefresh(sym: String, range: ChartRange, key: String, force: Boolean) {
        val before = _states.value[key]
        if (!force && before != null && before.status == QuoteStatus.Fresh && before.lastSuccessMs != null &&
            clock.nowMs() - before.lastSuccessMs < config.minRefetchMs(range)
        ) return

        if (before?.quote == null) {
            _states.update { it + (key to QuoteState(null, QuoteStatus.Loading)) }
        }
        activeCount.incrementAndGet().also { _refreshing.value = true }
        var completed = false
        try {
            val result = gate.withPermit { fetchChain(sym, range) }
            when (result) {
                is FetchResult.Ok -> applySuccess(key, sym, range, result.value)
                is FetchResult.Err -> applyFailure(key, result)
            }
            completed = true
        } finally {
            if (activeCount.decrementAndGet() == 0) _refreshing.value = false
            if (!completed) {
                // Cancelled (or crashed) mid-flight: never leave a symbol stuck in Loading.
                _states.update { m ->
                    if (m[key]?.status == QuoteStatus.Loading) (if (before == null) m - key else m + (key to before)) else m
                }
            }
        }
    }

    private suspend fun fetchChain(sym: String, range: ChartRange): FetchResult<Quote> {
        var firstErr: FetchResult.Err? = null
        var coolingRemaining: Long? = null
        var tried = 0
        for (p in providers) {
            if (!p.supports(sym)) continue
            val now = clock.nowMs()
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
                    return r
                }
                is FetchResult.Err -> {
                    onProviderError(p.id, r)
                    if (firstErr == null) firstErr = r
                }
            }
        }
        firstErr?.let { return it }
        return if (coolingRemaining != null) {
            FetchResult.Err(ErrorKind.RateLimited(coolingRemaining), "providers cooling down")
        } else {
            FetchResult.Err(ErrorKind.Unknown, "no provider available for $sym (tried $tried)")
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
            val n = (failureStreak[id] ?: 0) + 1
            failureStreak[id] = n
            val backoff = minOf(config.backoffBaseMs shl (n - 1).coerceAtMost(20), config.backoffCapMs)
            val retryAfter = (kind as? ErrorKind.RateLimited)?.retryAfterMs?.takeIf { it > 0 }
            val delay = if (retryAfter != null) maxOf(retryAfter, if (n > 1) backoff else 0L) else backoff
            cooldownUntil[id] = clock.nowMs() + minOf(delay, config.backoffCapMs)
        }
    }

    private suspend fun applySuccess(key: String, sym: String, range: ChartRange, fetched: Quote) {
        val now = clock.nowMs()
        val quote = fill(fetched, hints(sym))
        var accepted = false
        _states.update { m ->
            val cur = m[key]?.quote
            if (cur != null && cur.range == quote.range && quote.asOfEpochMs < cur.asOfEpochMs) {
                // Out of order: an older reading arrived after a newer one. Keep what we have.
                accepted = false
                val st = m.getValue(key)
                m + (key to st.copy(status = statusFor(range, now - (st.lastSuccessMs ?: now), true)))
            } else {
                accepted = true
                m + (key to QuoteState(quote, QuoteStatus.Fresh, now, null))
            }
        }
        if (accepted) {
            withContext(ioDispatcher) {
                runCatching { cache.put(cacheKey(sym, range), QuoteCodec.encode(quote), config.maxStaleMs) }
            }
        }
    }

    private fun applyFailure(key: String, err: FetchResult.Err) {
        val now = clock.nowMs()
        _states.update { m ->
            val st = m[key]
            val q = st?.quote
            val next = if (q != null) {
                QuoteState(q, QuoteStatus.Stale((now - (st.lastSuccessMs ?: now)).coerceAtLeast(0)), st.lastSuccessMs, err)
            } else {
                QuoteState(null, QuoteStatus.Error(err.kind), null, err)
            }
            m + (key to next)
        }
    }

    private fun statusFor(range: ChartRange, ageMs: Long, hadError: Boolean): QuoteStatus {
        val age = ageMs.coerceAtLeast(0)
        return if (!hadError && age <= config.staleAfterMs(range)) QuoteStatus.Fresh else QuoteStatus.Stale(age)
    }

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
