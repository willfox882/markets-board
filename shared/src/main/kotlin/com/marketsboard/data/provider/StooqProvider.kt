package com.marketsboard.data.provider

import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.Instrument
import com.marketsboard.data.model.Quote
import com.marketsboard.data.model.QuoteValidator
import com.marketsboard.data.net.HttpFetcher
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Fallback provider using Stooq's CSV quote endpoint. BEST EFFORT and deliberately thin:
 * - no currency, name, exchange, previous close or series: the caller's WatchItem fills currency/name/exchange
 *   (QuoteRepository does this) and the sparkline is simply absent;
 * - Stooq stamps quotes in Warsaw time (assumed, unverified); a missing date means the symbol has no data;
 * - 1D only (see [supports]); search is not supported (returns an empty list);
 * - Stooq has no TSX coverage, so `.TO` symbols such as XEQT.TO have no fallback today (expected, the UI handles it).
 * Only parsed against recorded fixtures, never exercised against the live service from the build host.
 */
class StooqProvider(
    private val http: HttpFetcher,
    private val baseUrl: String = "https://stooq.com",
) : MarketDataProvider {
    override val id: String = "stooq"

    override fun supports(symbol: String): Boolean = YahooToStooq.map(symbol) != null

    /** Only the 1D price snapshot: Stooq's quote CSV has no history, so other ranges have no fallback. */
    override fun supports(symbol: String, range: ChartRange): Boolean = range == ChartRange.D1 && supports(symbol)

    override suspend fun quote(symbol: String, range: ChartRange): FetchResult<Quote> {
        if (range != ChartRange.D1) return FetchResult.Err(ErrorKind.NotFound, "no fallback available for range ${range.label}")
        val mapped = YahooToStooq.map(symbol)
            ?: return FetchResult.Err(ErrorKind.NotFound, "no fallback available for $symbol")
        val url = "$baseUrl/q/l/?s=${URLEncoder.encode(mapped, "UTF-8")}&f=sd2t2ohlcv&h&e=csv"
        val resp = http.get(url, mapOf("Accept" to "text/csv,*/*"))
        resp.toError()?.let { return it }
        return StooqParser.parse(resp.body, symbol, range)
    }

    override suspend fun search(query: String): FetchResult<List<Instrument>> = FetchResult.Ok(emptyList())
}

/** Yahoo symbol to Stooq symbol. Null when there is no known mapping (many non-US listings such as `.TO`). */
object YahooToStooq {
    private val INDEXES = mapOf(
        "^GSPC" to "^spx",
        "^IXIC" to "^ndq",
        "^DJI" to "^dji",
        "^GDAXI" to "^dax",
        "^FTSE" to "^ukx",
        "^N225" to "^nkx",
    )
    private val FX = Regex("^([A-Z]{6})=X$")
    private val FUTURE = Regex("^([A-Z]{1,3})=F$")
    private val US_TICKER = Regex("^[A-Z]{1,5}(-[A-Z])?$")

    fun map(yahooSymbol: String): String? {
        val s = yahooSymbol.trim().uppercase()
        INDEXES[s]?.let { return it }
        FX.matchEntire(s)?.let { return it.groupValues[1].lowercase() }
        FUTURE.matchEntire(s)?.let { return it.groupValues[1].lowercase() + ".f" }
        if (US_TICKER.matches(s)) return s.lowercase() + ".us"
        return null
    }
}

internal object StooqParser {
    private val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")
    private const val UNKNOWN = "N/D"

    /** Stooq answers 200 with this plain text once the free daily quota is used up. */
    private const val DAILY_LIMIT_MARKER = "exceeded the daily hits limit"
    private const val DAILY_LIMIT_COOLDOWN_MS = 3_600_000L

    /** Columns are located by header name, so a missing trailing field (7 values for 8 headers) is fine. */
    fun parse(csv: String, requestedSymbol: String, range: ChartRange): FetchResult<Quote> {
        if (csv.contains(DAILY_LIMIT_MARKER, ignoreCase = true)) {
            return FetchResult.Err(ErrorKind.RateLimited(DAILY_LIMIT_COOLDOWN_MS), "stooq daily hits limit exceeded")
        }
        val lines = csv.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return FetchResult.Err(ErrorKind.Parse, "csv has no data row")
        val header = lines[0].split(',').map { it.trim().lowercase() }
        val row = lines[1].split(',').map { it.trim() }
        if ("symbol" !in header || "date" !in header || "close" !in header || row.size > header.size || row.size < header.size - 1) {
            return FetchResult.Err(ErrorKind.Parse, "unexpected csv columns")
        }
        fun col(name: String): String? = header.indexOf(name).takeIf { it >= 0 && it < row.size }?.let { row[it] }?.takeIf { it != UNKNOWN && it.isNotEmpty() }
        val close = col("close")?.toDoubleOrNull()
        val date = col("date")
        if (close == null || date == null) return FetchResult.Err(ErrorKind.NotFound, "no data (N/D)")
        val day = try {
            LocalDate.parse(date)
        } catch (e: Exception) {
            return FetchResult.Err(ErrorKind.Parse, "bad date")
        }
        val time = try {
            col("time")?.let { LocalTime.parse(it) } ?: LocalTime.MIDNIGHT
        } catch (e: Exception) {
            LocalTime.MIDNIGHT
        }
        val asOf = day.atTime(time).atZone(ZONE).toInstant().toEpochMilli()
        val raw = Quote(
            symbol = requestedSymbol.uppercase(),
            name = requestedSymbol.uppercase(),
            currency = "",
            exchange = "",
            price = close,
            prevClose = null,
            dayHigh = col("high")?.toDoubleOrNull(),
            dayLow = col("low")?.toDoubleOrNull(),
            marketState = null,
            asOfEpochMs = asOf,
            series = emptyList(),
            range = range,
            source = "stooq",
        )
        return QuoteValidator.validate(raw)
    }
}
