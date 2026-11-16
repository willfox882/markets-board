package com.marketsboard.sandbox

import com.marketsboard.core.ExchangeLabels
import com.marketsboard.data.Clock
import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.Quote
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.repo.QuoteRepository
import com.marketsboard.data.repo.QuoteState
import com.marketsboard.data.repo.QuoteStatus
import com.marketsboard.data.store.WatchlistStore
import com.marketsboard.ui.markets.AddMessage
import com.marketsboard.ui.markets.AddUi
import com.marketsboard.ui.markets.ConfirmRemoveUi
import com.marketsboard.ui.markets.MarketsUiState
import com.marketsboard.ui.markets.SearchRowUi
import com.marketsboard.ui.markets.Suggestions
import com.marketsboard.ui.markets.UiMapper
import kotlinx.coroutines.runBlocking

/**
 * Deterministic Markets states for screenshots and render tests: quotes come from the fake provider at a fixed time
 * (so series and prices never change between runs) and go through the real [UiMapper], like in the app.
 * The fixed time is 2026-11-16 17:45 UTC, which is 12:45 PM ET.
 */
object ScreenshotFixtures {
    const val NOW = 1_794_851_100_000L
    private val clock = Clock { NOW }
    private val fake = FakeMarketDataProvider(clock)

    fun quote(symbol: String, range: ChartRange = ChartRange.D1): Quote = runBlocking { (fake.quote(symbol, range) as FetchResult.Ok).value }

    fun item(symbol: String): WatchItem {
        WatchlistStore.DEFAULTS.firstOrNull { it.symbol == symbol }?.let { return it }
        val i = FakeMarketDataProvider.CATALOGUE.first { it.symbol == symbol }
        return WatchItem(i.symbol, i.name, i.currency, i.exchange)
    }

    fun fresh(q: Quote) = QuoteState(q, QuoteStatus.Fresh, NOW, null, false, NOW)
    fun stale(q: Quote, ageMs: Long) = QuoteState(q, QuoteStatus.Stale(ageMs), NOW - ageMs, null, false, NOW - ageMs)
    fun failed(q: Quote?, kind: ErrorKind, ageMs: Long = 0) = QuoteState(
        q, if (q != null) QuoteStatus.Stale(ageMs) else QuoteStatus.Error(kind), null, FetchResult.Err(kind, "fixture"), false, null,
    )
    fun loading() = QuoteState(null, QuoteStatus.Loading)

    private fun key(symbol: String, range: ChartRange = ChartRange.D1) = QuoteRepository.keyFor(symbol, range)

    fun marketsState(
        items: List<WatchItem>,
        states: Map<String, QuoteState>,
        editMode: Boolean = false,
        clockSkew: Boolean = false,
        cooldownMs: Long = 0,
    ) = MarketsUiState(
        quotes = UiMapper.rows(items, states, cooldownMs),
        header = UiMapper.header(items, states, NOW),
        clockSkew = clockSkew,
        editMode = editMode,
    )

    private fun freshStates(symbols: List<String>) = symbols.associate { key(it) to fresh(quote(it)) }

    /** The owner's two defaults, healthy. */
    fun normal(): MarketsUiState {
        val items = WatchlistStore.DEFAULTS
        return marketsState(items, freshStates(items.map { it.symbol }))
    }

    /** One stale row, one failed refresh keeping its numbers, one still loading, one failed with nothing held. */
    fun mixed(): MarketsUiState {
        val items = listOf("XEQT.TO", "MSFT", "SHOP.TO", "BTC-CAD").map(::item)
        val states = mapOf(
            key("XEQT.TO") to stale(quote("XEQT.TO"), 5 * 60_000L + 20_000L),
            key("MSFT") to failed(quote("MSFT"), ErrorKind.Network, 9 * 60_000L),
            key("SHOP.TO") to loading(),
            key("BTC-CAD") to failed(null, ErrorKind.RateLimited(120_000)),
        )
        return marketsState(items, states)
    }

    /** Six symbols: an index, a stock, FX with four decimals, a six-digit price, a crypto pair and the default ETF. */
    fun six(): MarketsUiState {
        val syms = listOf("XEQT.TO", "MSFT", "^GSPTSE", "USDCAD=X", "BTC-CAD", "GC=F")
        val items = syms.map(::item) + listOf()
        val states = freshStates(syms).toMutableMap()
        val btc = quote("BTC-CAD")
        // A six-digit price: scale the whole series so the sparkline and baseline stay consistent with it.
        val f = 123_456.78 / btc.price
        states[key("BTC-CAD")] = fresh(btc.copy(price = 123_456.78, prevClose = btc.prevClose?.times(f), series = btc.series.map { it.copy(close = it.close * f) }))
        val longName = WatchItem("RY.TO", "Royal Bank of Canada Common Shares Registered", "CAD", "TSX")
        return marketsState(items.dropLast(1) + longName + items.takeLast(1), states + (key("RY.TO") to fresh(quote("RY.TO"))))
    }

    fun skewAndStale(): MarketsUiState {
        val items = WatchlistStore.DEFAULTS
        val states = items.associate { key(it.symbol) to stale(quote(it.symbol), 12 * 60_000L) }
        return marketsState(items, states, clockSkew = true)
    }

    fun editor(): MarketsUiState {
        val items = listOf("XEQT.TO", "MSFT", "^GSPTSE").map(::item)
        return marketsState(items, freshStates(items.map { it.symbol }), editMode = true)
    }

    fun empty() = MarketsUiState(emptyList())

    fun confirmRemove(): MarketsUiState = editor().copy(confirmRemove = ConfirmRemoveUi("MSFT", "Microsoft Corporation"))

    fun detail(symbol: String, range: ChartRange): MarketsUiState {
        val items = WatchlistStore.DEFAULTS
        val states = freshStates(items.map { it.symbol }).toMutableMap()
        if (range != ChartRange.D1) states[key(symbol, range)] = fresh(quote(symbol, range))
        val d = UiMapper.detail(item(symbol), range, states, 0, NOW)
        return marketsState(items, states).copy(detail = d)
    }

    fun detailLoading(symbol: String, range: ChartRange): MarketsUiState {
        val items = WatchlistStore.DEFAULTS
        val states = freshStates(items.map { it.symbol })
        return marketsState(items, states).copy(detail = UiMapper.detail(item(symbol), range, states, 0, NOW))
    }

    fun detailError(symbol: String, range: ChartRange): MarketsUiState {
        val items = WatchlistStore.DEFAULTS
        val states = freshStates(items.map { it.symbol }) + (key(symbol, range) to failed(null, ErrorKind.Network))
        return marketsState(items, states).copy(detail = UiMapper.detail(item(symbol), range, states, 0, NOW))
    }

    private fun resultRows(q: String, have: Set<String>): List<SearchRowUi> = FakeMarketDataProvider.CATALOGUE
        .filter { it.symbol.contains(q.uppercase()) || it.name.uppercase().contains(q.uppercase()) }
        .map { SearchRowUi(it.symbol, it.name, ExchangeLabels.short(it.exchange), com.marketsboard.core.TypeLabels.of(it.type), it.symbol in have) }

    private fun suggestions(have: Set<String>) = Suggestions.ALL.map { it.copy(added = it.symbol in have) }

    /** Add sheet with live results for "bank" (one of them already in the list). */
    fun addWithResults(): MarketsUiState {
        val have = setOf("RY.TO")
        return editor().copy(
            add = AddUi(query = "bank", results = resultRows("bank", have), suggestions = suggestions(have)),
        )
    }

    fun addSuggestions(): MarketsUiState = editor().copy(add = AddUi(suggestions = suggestions(setOf("XEQT.TO", "MSFT"))))

    fun addOffline(): MarketsUiState = editor().copy(
        add = AddUi(
            query = "SHOP.TO", suggestions = suggestions(emptySet()),
            message = AddMessage(
                "Can't verify the symbol while offline. You can add it anyway, prices will appear once you are online",
                warn = true, addAnywaySymbol = "SHOP.TO",
            ),
        ),
    )

    fun replace(): MarketsUiState = editor().copy(
        add = AddUi(replacing = "MSFT", replacingName = "Microsoft Corporation", query = "app", results = resultRows("app", setOf()), suggestions = suggestions(setOf())),
    )
}
