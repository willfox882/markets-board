package com.marketsboard.data.model

import java.util.Locale

/** A searchable instrument as returned by provider search. Currency is often unknown ("") until a quote is fetched. */
data class Instrument(
    val symbol: String,
    val name: String,
    val currency: String,
    val exchange: String,
    val type: String,
)

/**
 * One entry of the user's watchlist (persisted). [currency] and [exchange] may be blank when they were not known
 * (added offline); the first successful quote fills blanks, see QuoteRepository.
 */
data class WatchItem(
    val symbol: String,
    val name: String,
    val currency: String,
    val exchange: String,
    /** User override shown instead of [name]. Null means use [name]. */
    val displayName: String? = null,
) {
    val shownName: String get() = displayName?.takeIf { it.isNotBlank() } ?: name
}

/** Chart ranges and the Yahoo parameters that implement them. */
enum class ChartRange(val label: String, val yahooRange: String, val yahooInterval: String) {
    D1("1D", "1d", "5m"),
    D5("5D", "5d", "15m"),
    M1("1M", "1mo", "1d"),
    M3("3M", "3mo", "1d"),
    Y1("1Y", "1y", "1wk"),
}

data class SeriesPoint(val tMs: Long, val close: Double)

/**
 * A price snapshot plus an optional sparkline series.
 *
 * - [asOfEpochMs] is PROVIDER time (last trade / quote time), never the device clock. It does not advance while a
 *   market is closed, so it is not a freshness signal; freshness is tracked by the repository.
 * - [prevClose] is the reference close for [range]: previous session close for D1, the close before the range start
 *   for longer ranges (so change = move over the displayed range). Null when the provider does not know it.
 */
data class Quote(
    val symbol: String,
    val name: String,
    val currency: String,
    val exchange: String,
    val price: Double,
    val prevClose: Double?,
    val dayHigh: Double?,
    val dayLow: Double?,
    val marketState: String?,
    val asOfEpochMs: Long,
    val series: List<SeriesPoint>,
    val range: ChartRange,
    val instrumentType: String? = null,
    /** Id of the provider that produced this price (set by the provider; the repository stamps it when blank). */
    val source: String = "",
    /** Regular session window of the exchange (epoch ms) when the provider says so, for market-hours aware refresh. */
    val sessionStartMs: Long? = null,
    val sessionEndMs: Long? = null,
    /** IANA zone of the exchange, e.g. America/Toronto. */
    val exchangeTimezoneName: String? = null,
    /** Provider's suggested number of decimals (Yahoo `priceHint`): FX needs 4, equities and indices 2. */
    val priceHint: Int? = null,
) {
    /** Decimals to show: the provider's hint, else 4 for currency pairs, else 2. */
    val decimals: Int get() = priceHint?.takeIf { it in 0..8 } ?: if (instrumentType.equals("CURRENCY", ignoreCase = true)) 4 else 2
}

/**
 * Currency code handling. Providers quote some London, Johannesburg and Tel Aviv listings in MINOR units (pence,
 * cents, agorot) with a mixed-case code (`GBp`, `ZAc`). Upper-casing those would label pence as pounds, so they map
 * to the distinct codes GBX, ZAC and ILA; the UI must not show them as GBP, ZAR or ILS.
 */
object Currencies {
    private val MINOR = mapOf("GBX" to "GBP", "ZAC" to "ZAR", "ILA" to "ILS")

    fun normalize(raw: String?): String {
        val s = raw?.trim().orEmpty()
        return when {
            s == "GBp" || s.equals("GBX", ignoreCase = true) -> "GBX"
            s == "ZAc" || s.equals("ZAC", ignoreCase = true) -> "ZAC"
            s.equals("ILA", ignoreCase = true) -> "ILA"
            else -> s.uppercase(Locale.ROOT)
        }
    }

    fun isMinorUnit(code: String): Boolean = code in MINOR

    /** The major currency of a minor unit code (GBX gives GBP), else the code itself. */
    fun majorOf(code: String): String = MINOR[code] ?: code
}

sealed interface ErrorKind {
    data object Network : ErrorKind
    data object Timeout : ErrorKind
    data class RateLimited(val retryAfterMs: Long?) : ErrorKind
    data object NotFound : ErrorKind
    data object Parse : ErrorKind
    data class Http(val code: Int) : ErrorKind
    data object Unknown : ErrorKind

    /** TLS failed on a certificate date check: almost always a wrong device date or time. */
    data object ClockOrTls : ErrorKind

    /** Short stable name for logs and the diagnostics screen. */
    val label: String
        get() = when (this) {
            Network -> "network"
            Timeout -> "timeout"
            is RateLimited -> "rate_limited"
            NotFound -> "not_found"
            Parse -> "parse"
            is Http -> "http_$code"
            Unknown -> "unknown"
            ClockOrTls -> "clock_or_tls"
        }
}

sealed interface FetchResult<out T> {
    data class Ok<out T>(val value: T) : FetchResult<T>
    data class Err(val kind: ErrorKind, val message: String = "") : FetchResult<Nothing>
}

/** Rules that every provider's output must pass before it leaves the provider (and again when read from cache). */
object QuoteValidator {
    /** Larger than any real price (BRK.A is about 7e5) yet small enough to catch unit and parsing bugs. */
    const val MAX_PRICE = 1e9
    const val MAX_SERIES = 2000

    fun validPrice(v: Double?): Boolean = v != null && v.isFinite() && v > 0.0 && v <= MAX_PRICE

    /**
     * Returns the cleaned quote or a Parse error. Bad price or missing provider time rejects the whole quote;
     * bad optional numbers become null; bad series points are dropped with the order of the rest preserved.
     */
    fun validate(q: Quote): FetchResult<Quote> {
        if (q.symbol.isBlank()) return FetchResult.Err(ErrorKind.Parse, "blank symbol")
        if (!validPrice(q.price)) return FetchResult.Err(ErrorKind.Parse, "invalid price ${q.price}")
        if (q.asOfEpochMs <= 0L) return FetchResult.Err(ErrorKind.Parse, "missing provider time")
        val series = q.series.filter { it.tMs > 0L && validPrice(it.close) }.let {
            if (it.size > MAX_SERIES) it.takeLast(MAX_SERIES) else it
        }
        return FetchResult.Ok(
            q.copy(
                prevClose = q.prevClose.takeIf { validPrice(it) },
                dayHigh = q.dayHigh.takeIf { validPrice(it) },
                dayLow = q.dayLow.takeIf { validPrice(it) },
                marketState = q.marketState?.trim()?.takeIf { it.isNotEmpty() },
                sessionStartMs = q.sessionStartMs?.takeIf { it > 0 },
                sessionEndMs = q.sessionEndMs?.takeIf { it > 0 },
                priceHint = q.priceHint?.takeIf { it in 0..8 },
                series = series,
            ),
        )
    }
}
