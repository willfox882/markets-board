package com.marketsboard.sandbox

import com.marketsboard.data.Clock
import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.Instrument
import com.marketsboard.data.model.Quote
import com.marketsboard.data.model.QuoteValidator
import com.marketsboard.data.model.SeriesPoint
import com.marketsboard.data.provider.MarketDataProvider
import java.util.SplittableRandom
import kotlin.math.exp
import kotlin.math.sin
import kotlinx.coroutines.delay

/** What the fake provider does on the next calls. Switch it from code (and from the M2 sandbox panel). */
enum class FakeBehavior { Normal, Slow, Offline, RateLimited, PartialFailure, BadData }

/**
 * Deterministic stand-in for the real providers, for the sandbox and tests. Any symbol works: prices are a stable
 * function of (symbol, time bucket), so the same symbol always looks the same and moves a little with time.
 * Currency inference: `.TO`/`.V`/`.CN` is CAD, `-CAD` crypto is CAD, `=X` is FX (quoted in the pair's second
 * currency), futures `=F` are USD, everything else USD.
 */
class FakeMarketDataProvider(
    private val clock: Clock = Clock.System,
    @Volatile var behavior: FakeBehavior = FakeBehavior.Normal,
    @Volatile var slowDelayMs: Long = 2_500,
    @Volatile var retryAfterMs: Long? = 60_000,
) : MarketDataProvider {
    override val id: String = "fake"

    @Volatile var quoteCalls: Int = 0
        private set

    override suspend fun quote(symbol: String, range: ChartRange): FetchResult<Quote> {
        quoteCalls++
        val sym = symbol.trim().uppercase()
        when (behavior) {
            FakeBehavior.Offline -> return FetchResult.Err(ErrorKind.Network, "fake: offline")
            FakeBehavior.RateLimited -> return FetchResult.Err(ErrorKind.RateLimited(retryAfterMs), "fake: HTTP 429")
            FakeBehavior.Slow -> delay(slowDelayMs)
            FakeBehavior.PartialFailure -> if (stableHash(sym) % 2 == 0) return FetchResult.Err(ErrorKind.Http(503), "fake: HTTP 503")
            else -> Unit
        }
        if (sym.startsWith("ZZZ")) return FetchResult.Err(ErrorKind.NotFound, "fake: unknown symbol")
        val q = build(sym, range)
        // BadData: run an impossible price through the same validator the real providers use.
        return QuoteValidator.validate(if (behavior == FakeBehavior.BadData) q.copy(price = Double.NaN) else q)
    }

    override suspend fun search(query: String): FetchResult<List<Instrument>> {
        when (behavior) {
            FakeBehavior.Offline -> return FetchResult.Err(ErrorKind.Network, "fake: offline")
            FakeBehavior.RateLimited -> return FetchResult.Err(ErrorKind.RateLimited(retryAfterMs), "fake: HTTP 429")
            FakeBehavior.Slow -> delay(slowDelayMs)
            else -> Unit
        }
        val q = query.trim().uppercase()
        if (q.isEmpty()) return FetchResult.Ok(emptyList())
        return FetchResult.Ok(CATALOGUE.filter { it.symbol.contains(q) || it.name.uppercase().contains(q) }.take(8))
    }

    private fun build(sym: String, range: ChartRange): Quote {
        val interval = intervalMs(range)
        val bucket = clock.nowMs() / interval
        val points = POINTS[range] ?: 60
        val base = basePrice(sym)
        val series = (0 until points).map { i ->
            val b = bucket - (points - 1 - i)
            SeriesPoint(b * interval, round(base * level(sym, range, b)))
        }
        val price = series.last().close
        // D1 reference is the previous session close, longer ranges the close before the range started.
        val prev = round(base * level(sym, range, bucket - points))
        return Quote(
            symbol = sym,
            name = nameFor(sym),
            currency = currencyFor(sym),
            exchange = exchangeFor(sym),
            price = price,
            prevClose = prev,
            dayHigh = series.maxOf { it.close },
            dayLow = series.minOf { it.close },
            marketState = "REGULAR",
            asOfEpochMs = series.last().tMs,
            series = series,
            range = range,
            instrumentType = typeFor(sym),
        )
    }

    /** Smooth deterministic wobble plus per-bucket noise: stable per (symbol, range, bucket), no hidden state. */
    private fun level(sym: String, range: ChartRange, bucket: Long): Double {
        val seed = stableHash(sym).toLong()
        val slow = 0.03 * sin(bucket / 23.0 + (seed % 628) / 100.0)
        val fast = 0.012 * sin(bucket / 5.0 + (seed % 314) / 50.0)
        val noise = (SplittableRandom(seed * 1_000_003L + bucket * 31L + range.ordinal).nextDouble() - 0.5) * 0.006
        return exp(slow + fast + noise)
    }

    private fun round(v: Double) = Math.round(v * 100.0) / 100.0

    private fun intervalMs(r: ChartRange) = when (r) {
        ChartRange.D1 -> 5 * 60_000L
        ChartRange.D5 -> 15 * 60_000L
        ChartRange.M1, ChartRange.M3 -> 86_400_000L
        ChartRange.Y1 -> 7 * 86_400_000L
    }

    companion object {
        private val POINTS = mapOf(ChartRange.D1 to 78, ChartRange.D5 to 130, ChartRange.M1 to 22, ChartRange.M3 to 63, ChartRange.Y1 to 52)

        private data class Known(val name: String, val exchange: String, val currency: String, val type: String, val base: Double)

        private val KNOWN = mapOf(
            "XEQT.TO" to Known("iShares Core Equity ETF Portfolio", "TSX", "CAD", "ETF", 41.9),
            "MSFT" to Known("Microsoft Corporation", "NASDAQ", "USD", "EQUITY", 420.0),
            "^GSPTSE" to Known("S&P/TSX Composite", "TSX", "CAD", "INDEX", 24800.0),
            "^GSPC" to Known("S&P 500", "SNP", "USD", "INDEX", 5600.0),
            "^IXIC" to Known("NASDAQ Composite", "NASDAQ", "USD", "INDEX", 17900.0),
            "USDCAD=X" to Known("USD/CAD", "CCY", "CAD", "CURRENCY", 1.37),
            "CL=F" to Known("Crude Oil WTI", "NYMEX", "USD", "FUTURE", 78.0),
            "GC=F" to Known("Gold", "COMEX", "USD", "FUTURE", 2350.0),
            "BTC-CAD" to Known("Bitcoin CAD", "CCC", "CAD", "CRYPTOCURRENCY", 92000.0),
            "ETH-CAD" to Known("Ethereum CAD", "CCC", "CAD", "CRYPTOCURRENCY", 4800.0),
            "VFV.TO" to Known("Vanguard S&P 500 Index ETF", "TSX", "CAD", "ETF", 125.0),
            "XIU.TO" to Known("iShares S&P/TSX 60 Index ETF", "TSX", "CAD", "ETF", 34.0),
            "SHOP.TO" to Known("Shopify Inc.", "TSX", "CAD", "EQUITY", 140.0),
            "RY.TO" to Known("Royal Bank of Canada", "TSX", "CAD", "EQUITY", 165.0),
            "TD.TO" to Known("Toronto-Dominion Bank", "TSX", "CAD", "EQUITY", 82.0),
            "ENB.TO" to Known("Enbridge Inc.", "TSX", "CAD", "EQUITY", 55.0),
            "AAPL" to Known("Apple Inc.", "NASDAQ", "USD", "EQUITY", 215.0),
            "NVDA" to Known("NVIDIA Corporation", "NASDAQ", "USD", "EQUITY", 125.0),
        )

        val CATALOGUE: List<Instrument> = KNOWN.map { (s, k) -> Instrument(s, k.name, k.currency, k.exchange, k.type) }

        fun stableHash(s: String): Int {
            var h = 17
            for (c in s) h = h * 31 + c.code
            return h and 0x7fffffff
        }

        fun currencyFor(sym: String): String = KNOWN[sym]?.currency ?: when {
            sym.endsWith(".TO") || sym.endsWith(".V") || sym.endsWith(".CN") -> "CAD"
            sym.endsWith("-CAD") -> "CAD"
            sym.endsWith("=X") -> if (sym.length >= 8) sym.substring(3, 6) else "USD"
            else -> "USD"
        }

        fun exchangeFor(sym: String): String = KNOWN[sym]?.exchange ?: when {
            sym.endsWith(".TO") -> "TSX"
            sym.endsWith(".V") -> "TSXV"
            sym.endsWith("=X") -> "CCY"
            sym.endsWith("=F") -> "NYMEX"
            sym.contains('-') -> "CCC"
            else -> "NASDAQ"
        }

        fun typeFor(sym: String): String = KNOWN[sym]?.type ?: when {
            sym.startsWith("^") -> "INDEX"
            sym.endsWith("=X") -> "CURRENCY"
            sym.endsWith("=F") -> "FUTURE"
            sym.contains('-') -> "CRYPTOCURRENCY"
            else -> "EQUITY"
        }

        fun nameFor(sym: String): String = KNOWN[sym]?.name ?: "$sym (fake)"

        fun basePrice(sym: String): Double = KNOWN[sym]?.base ?: (10 + stableHash(sym) % 49_000 / 100.0)
    }
}
