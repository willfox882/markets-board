package com.marketsboard

import com.marketsboard.data.Clock
import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.Instrument
import com.marketsboard.data.model.Quote
import com.marketsboard.data.provider.MarketDataProvider
import com.marketsboard.data.repo.QuoteRepository
import com.marketsboard.data.repo.QuoteState
import com.marketsboard.data.repo.QuoteStatus
import com.marketsboard.data.repo.QuotesSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler

/** Clock driven by the test scheduler's virtual time: wall and monotonic both advance with `advanceTimeBy`. */
class VirtualClock(private val scheduler: TestCoroutineScheduler, private val wallBase: Long = 1_700_000_000_000L) : Clock {
    override fun nowMs(): Long = wallBase + scheduler.currentTime
    override fun monoMs(): Long = scheduler.currentTime
}

data class RefreshCall(val atMs: Long, val symbols: List<String>, val range: ChartRange, val force: Boolean)

/** [QuotesSource] with scripted states that records every call. */
class FakeQuotesSource(private val clock: Clock) : QuotesSource {
    private val _states = MutableStateFlow<Map<String, QuoteState>>(emptyMap())
    override val states: StateFlow<Map<String, QuoteState>> = _states
    override val refreshing: StateFlow<Boolean> = MutableStateFlow(false)

    val calls = mutableListOf<RefreshCall>()
    val forgotten = mutableListOf<String>()
    var agesCalls = mutableListOf<Long>()
    var cooldownMs = 0L
    var refreshDelayMs = 0L
    var failWith: Throwable? = null
    /** Sets the fetched state of each refreshed symbol (default: a fresh REGULAR quote). */
    var onRefresh: (String, ChartRange) -> QuoteState? = { _, _ -> null }

    fun put(symbol: String, state: QuoteState, range: ChartRange = ChartRange.D1) {
        _states.value = _states.value + (QuoteRepository.keyFor(symbol, range) to state)
    }

    fun putAll(map: Map<String, QuoteState>) { _states.value = _states.value + map }

    override fun stateOf(symbol: String, range: ChartRange): QuoteState? = _states.value[QuoteRepository.keyFor(symbol, range)]
    override fun cooldownRemainingMs(): Long = cooldownMs
    override suspend fun hydrate(symbols: List<String>, range: ChartRange) {}

    override suspend fun refresh(symbols: List<String>, range: ChartRange, force: Boolean) {
        calls += RefreshCall(clock.monoMs(), symbols, range, force)
        if (refreshDelayMs > 0) delay(refreshDelayMs)
        failWith?.let { throw it }
        for (s in symbols) onRefresh(s, range)?.let { put(s, it, range) }
    }

    override fun recomputeAges() { agesCalls += clock.monoMs() }
    override suspend fun forget(symbol: String) { forgotten += symbol }
}

fun freshState(q: Quote, atMono: Long = 0) = QuoteState(q, QuoteStatus.Fresh, 0L, null, false, atMono)

/** Provider scripted by lambdas; counts and times search calls. */
class FakeProvider(
    private val clock: Clock,
    var quoteFn: (String, ChartRange) -> FetchResult<Quote> = { s, r -> FetchResult.Ok(com.marketsboard.data.quoteOf(s, 50.0, range = r, currency = "USD", name = "$s Corp")) },
    var searchFn: (String) -> FetchResult<List<Instrument>> = { FetchResult.Ok(emptyList()) },
) : MarketDataProvider {
    override val id = "fake"
    val searches = mutableListOf<Pair<Long, String>>()
    val quotes = mutableListOf<String>()
    var searchDelayMs = 0L

    override suspend fun quote(symbol: String, range: ChartRange): FetchResult<Quote> {
        quotes += symbol
        return quoteFn(symbol, range)
    }

    override suspend fun search(query: String): FetchResult<List<Instrument>> {
        searches += clock.monoMs() to query
        if (searchDelayMs > 0) delay(searchDelayMs)
        return searchFn(query)
    }
}

fun err(kind: ErrorKind) = FetchResult.Err(kind, "test")
