package com.marketsboard.data.provider

import com.marketsboard.data.Clock
import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.Currencies
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.Instrument
import com.marketsboard.data.model.Quote
import com.marketsboard.data.model.QuoteValidator
import com.marketsboard.data.model.SeriesPoint
import com.marketsboard.data.net.HttpFetcher
import com.marketsboard.data.net.HttpResponse
import java.net.URLEncoder
import kotlinx.serialization.json.JsonObject

/**
 * Primary provider: Yahoo Finance chart and search endpoints (unofficial, undocumented, may change or throttle).
 *
 * Previous close semantics: for [ChartRange.D1] the reference is the previous SESSION close (`chartPreviousClose`,
 * then `previousClose`). For longer ranges it is `chartPreviousClose`, which Yahoo defines as the close before the
 * first bar of the range, so change = move over the displayed range; falls back to the first valid series point.
 *
 * Fallback host: [baseUrls] are tried in order; the next host is used only after a network error, timeout or 5xx.
 * BEST EFFORT: only parsed against recorded fixtures, never exercised against the live service from the build host.
 */
class YahooChartProvider(
    private val http: HttpFetcher,
    private val clock: Clock = Clock.System,
    private val baseUrls: List<String> = listOf("https://query1.finance.yahoo.com", "https://query2.finance.yahoo.com"),
) : MarketDataProvider {
    override val id: String = "yahoo"

    override suspend fun quote(symbol: String, range: ChartRange): FetchResult<Quote> {
        val path = "/v8/finance/chart/${enc(symbol)}?range=${range.yahooRange}&interval=${range.yahooInterval}&includePrePost=false"
        val resp = getWithHostFallback(path)
        if (!resp.isSuccess) return errorFor(resp)
        return YahooParser.parseChart(resp.body, symbol, range, clock.nowMs())
    }

    override suspend fun search(query: String): FetchResult<List<Instrument>> {
        val q = query.trim().take(64)
        if (q.isEmpty()) return FetchResult.Ok(emptyList())
        val path = "/v1/finance/search?q=${enc(q)}&quotesCount=8&newsCount=0"
        val resp = getWithHostFallback(path)
        if (!resp.isSuccess) return errorFor(resp)
        return YahooParser.parseSearch(resp.body)
    }

    private suspend fun getWithHostFallback(path: String): HttpResponse {
        var last: HttpResponse? = null
        for (base in baseUrls) {
            val r = http.get(base + path, mapOf("Accept" to "application/json"))
            last = r
            // Another host helps when this one is sick, not when the whole device is offline (DNS or no route).
            val retryOtherHost = !r.offline && (r.failure?.kind.let { it == ErrorKind.Network || it == ErrorKind.Timeout } || r.code in 500..599)
            if (!retryOtherHost) return r
        }
        return last ?: HttpResponse(0, failure = FetchResult.Err(ErrorKind.Unknown, "no hosts configured"))
    }

    /**
     * Non-2xx. Yahoo sends a JSON error body for unknown symbols (HTTP 404), so a NotFound or RateLimited body wins
     * over the bare status. Server errors (5xx) always stay Http(5xx), even with a JSON body, so they trigger backoff.
     */
    private fun errorFor(resp: HttpResponse): FetchResult.Err {
        val status = resp.toError() ?: return FetchResult.Err(ErrorKind.Unknown, "unexpected state")
        if (resp.failure != null || resp.code == 429 || resp.code >= 500) {
            val detail = if (resp.code >= 500) YahooParser.chartErrorFromBody(resp.body)?.message.orEmpty() else ""
            return if (detail.isEmpty()) status else status.copy(message = "${status.message}: $detail")
        }
        val parsed = YahooParser.chartErrorFromBody(resp.body) ?: return status
        return if (parsed.kind == ErrorKind.Unknown) status.copy(message = "${status.message}: ${parsed.message}") else parsed
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/** Pure parsing, separated for tests. */
internal object YahooParser {
    private val SEARCH_TYPES = setOf("EQUITY", "ETF", "INDEX", "CURRENCY", "CRYPTOCURRENCY", "FUTURE")

    /** The `chart.error` object as a typed error, or null when absent or the body is not Yahoo JSON. */
    fun chartErrorFromBody(body: String): FetchResult.Err? {
        val chart = Js.parse(body).asObj()?.obj("chart") ?: return null
        val err = chart.obj("error") ?: return null
        val code = err.str("code").orEmpty()
        val desc = err.str("description").orEmpty()
        val msg = listOf(code, desc).filter { it.isNotEmpty() }.joinToString(": ").ifEmpty { "chart error" }
        val lower = "$code $desc".lowercase()
        val kind = when {
            "not found" in lower || "no data found" in lower || "delisted" in lower -> ErrorKind.NotFound
            "too many requests" in lower -> ErrorKind.RateLimited(null)
            else -> ErrorKind.Unknown
        }
        return FetchResult.Err(kind, msg)
    }

    fun parseChart(body: String, requestedSymbol: String, range: ChartRange, nowMs: Long): FetchResult<Quote> {
        val root = Js.parse(body).asObj() ?: return FetchResult.Err(ErrorKind.Parse, "not a JSON object")
        val chart = root.obj("chart") ?: return FetchResult.Err(ErrorKind.Parse, "missing chart")
        chartErrorFromBody(body)?.let { return it }
        val result = chart.arr("result")?.firstOrNull().asObj()
            ?: return FetchResult.Err(ErrorKind.NotFound, "empty result")
        val meta = result.obj("meta") ?: return FetchResult.Err(ErrorKind.Parse, "missing meta")

        // Series: timestamps (epoch seconds) zipped with closes (nulls allowed). Invalid points dropped by the validator.
        val ts = result.arr("timestamp")
        val closes = result.obj("indicators")?.arr("quote")?.firstOrNull().asObj()?.arr("close")
        val series = ArrayList<SeriesPoint>()
        if (ts != null && closes != null) {
            for (i in 0 until minOf(ts.size, closes.size)) {
                val t = ts[i].asNum() ?: continue
                val c = closes[i].asNum() ?: continue
                if (!t.isFinite() || t <= 0 || t > 4e9) continue
                series.add(SeriesPoint((t * 1000).toLong(), c))
            }
        }
        val cleanSeries = series.filter { QuoteValidator.validPrice(it.close) }

        val price = meta.num("regularMarketPrice")?.takeIf { QuoteValidator.validPrice(it) } ?: cleanSeries.lastOrNull()?.close
            ?: return FetchResult.Err(ErrorKind.Parse, "no regularMarketPrice and no usable series")
        val asOfSec = meta.long("regularMarketTime") ?: cleanSeries.lastOrNull()?.tMs?.div(1000)
            ?: return FetchResult.Err(ErrorKind.Parse, "no provider time")

        val prevClose = when (range) {
            ChartRange.D1 -> meta.num("chartPreviousClose").valid() ?: meta.num("previousClose").valid()
            else -> meta.num("chartPreviousClose").valid() ?: cleanSeries.firstOrNull()?.close
        }

        val raw = Quote(
            symbol = (meta.str("symbol") ?: requestedSymbol).uppercase(),
            name = meta.str("longName") ?: meta.str("shortName") ?: requestedSymbol,
            currency = Currencies.normalize(meta.str("currency")),
            exchange = meta.str("fullExchangeName") ?: meta.str("exchangeName").orEmpty(),
            price = price,
            prevClose = prevClose,
            dayHigh = meta.num("regularMarketDayHigh"),
            dayLow = meta.num("regularMarketDayLow"),
            marketState = meta.str("marketState") ?: deriveMarketState(meta, nowMs, asOfSec * 1000L),
            asOfEpochMs = asOfSec * 1000L,
            series = cleanSeries,
            range = range,
            instrumentType = meta.str("instrumentType"),
            source = "yahoo",
            sessionStartMs = meta.obj("currentTradingPeriod")?.obj("regular")?.long("start")?.times(1000L),
            sessionEndMs = meta.obj("currentTradingPeriod")?.obj("regular")?.long("end")?.times(1000L),
            exchangeTimezoneName = meta.str("exchangeTimezoneName"),
            priceHint = meta.num("priceHint")?.takeIf { it.isFinite() }?.toInt(),
        )
        return QuoteValidator.validate(raw)
    }

    private fun Double?.valid(): Double? = this?.takeIf { QuoteValidator.validPrice(it) }

    /**
     * Best effort, only when Yahoo omits `marketState`. REGULAR needs ALL of: the device clock inside today's regular
     * trading window, AND the last trade (`regularMarketTime`) no more than 20 minutes old. Otherwise CLOSED. If the
     * device clock is behind the provider's last trade (wrong clock) the answer is unknown (null), never REGULAR.
     * Holidays are only detected heuristically (a stale last trade inside the window reads as CLOSED), and a
     * thinly traded symbol with no trade for 20 minutes also reads as CLOSED.
     */
    private fun deriveMarketState(meta: JsonObject, nowMs: Long, lastTradeMs: Long): String? {
        val reg = meta.obj("currentTradingPeriod")?.obj("regular") ?: return null
        val start = reg.long("start") ?: return null
        val end = reg.long("end") ?: return null
        val sinceTrade = nowMs - lastTradeMs
        if (sinceTrade < -SKEW_TOLERANCE_MS) return null
        val inWindow = nowMs >= start * 1000 && nowMs < end * 1000
        return if (inWindow && sinceTrade <= FRESH_TRADE_MS) "REGULAR" else "CLOSED"
    }

    private const val SKEW_TOLERANCE_MS = 120_000L
    private const val FRESH_TRADE_MS = 20 * 60_000L

    fun parseSearch(body: String): FetchResult<List<Instrument>> {
        val root = Js.parse(body).asObj() ?: return FetchResult.Err(ErrorKind.Parse, "not a JSON object")
        val quotes = root.arr("quotes") ?: return FetchResult.Err(ErrorKind.Parse, "missing quotes")
        val out = ArrayList<Instrument>()
        for (e in quotes) {
            val o = e.asObj() ?: continue
            val symbol = o.str("symbol") ?: continue
            val type = o.str("quoteType")?.uppercase() ?: continue
            if (type !in SEARCH_TYPES) continue
            // Yahoo can list one symbol twice (several exchanges); keep the first so UI keys stay unique.
            if (out.any { it.symbol.equals(symbol, ignoreCase = true) }) continue
            out.add(
                Instrument(
                    symbol = symbol.uppercase(),
                    name = o.str("longname") ?: o.str("shortname") ?: symbol,
                    currency = "",
                    exchange = o.str("exchDisp") ?: o.str("exchange").orEmpty(),
                    type = type,
                ),
            )
        }
        return FetchResult.Ok(out)
    }
}
