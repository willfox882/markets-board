package com.marketsboard.data

import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.Quote
import com.marketsboard.data.provider.YahooChartProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YahooProviderTest {
    private val clock = FakeClock(1_714_750_000_000L) // inside the fixtures' regular session

    private fun provider(http: FakeHttp) = YahooChartProvider(http, clock)
    private fun fixtureHttp(name: String) = FakeHttp { ok(Fixtures.text(name)) }

    private fun okQuote(name: String, symbol: String = "X", range: ChartRange = ChartRange.D1): Quote {
        val r = runBlocking { provider(fixtureHttp(name)).quote(symbol, range) }
        return (r as? FetchResult.Ok)?.value ?: error("expected Ok, got $r")
    }

    private fun err(name: String, symbol: String = "X"): FetchResult.Err {
        val r = runBlocking { provider(fixtureHttp(name)).quote(symbol, ChartRange.D1) }
        return r as? FetchResult.Err ?: error("expected Err, got $r")
    }

    @Test fun parsesXeqtInCad() {
        val q = okQuote("yahoo_chart_xeqt_1d.json", "XEQT.TO")
        assertEquals("XEQT.TO", q.symbol)
        assertEquals("iShares Core Equity ETF Portfolio", q.name)
        assertEquals("CAD", q.currency)
        assertEquals("Toronto", q.exchange)
        assertEquals(41.87, q.price, 1e-9)
        assertEquals(41.52, q.prevClose!!, 1e-9)
        assertEquals(41.9, q.dayHigh!!, 1e-9)
        assertEquals(41.5, q.dayLow!!, 1e-9)
        assertEquals("ETF", q.instrumentType)
        assertEquals(8, q.series.size)
        assertEquals(ChartRange.D1, q.range)
    }

    @Test fun parsesMsftInUsd() {
        val q = okQuote("yahoo_chart_msft_1d.json", "MSFT")
        assertEquals("USD", q.currency)
        assertEquals("NasdaqGS", q.exchange)
        assertEquals(413.54, q.price, 1e-9)
        assertEquals(411.46, q.prevClose!!, 1e-9)
    }

    @Test fun asOfComesFromProviderTimeInMilliseconds() {
        val q = okQuote("yahoo_chart_msft_1d.json")
        assertEquals(1_714_766_400_000L, q.asOfEpochMs)
        assertEquals(1_714_743_000_000L, q.series.first().tMs)
        assertEquals(300_000L, q.series[1].tMs - q.series[0].tMs)
    }

    @Test fun marketStateDerivedFromTradingPeriodAndFreshLastTrade() {
        // Regular session in the fixture: 1714743000 .. 1714766400 (epoch seconds).
        fun state(nowMs: Long, lastTradeSec: Long): String? {
            clock.now = nowMs
            val body = Fixtures.text("yahoo_chart_msft_1d.json").replace("\"regularMarketTime\": 1714766400", "\"regularMarketTime\": $lastTradeSec")
            return ((runBlocking { provider(FakeHttp { ok(body) }).quote("MSFT", ChartRange.D1) }) as FetchResult.Ok).value.marketState
        }
        assertEquals("REGULAR", state(1_714_750_000_000L, 1_714_749_900)) // traded 100 s ago inside the window
        assertEquals("CLOSED", state(1_714_750_000_000L, 1_714_747_000)) // inside the window but last trade 50 min old (halt, holiday)
        assertEquals("CLOSED", state(1_714_900_000_000L, 1_714_899_900)) // outside the window
        assertNull(state(1_714_750_000_000L, 1_714_760_000)) // device clock behind the last trade: unknown, never REGULAR
    }

    @Test fun nullClosesAreDroppedAndOrderKept() {
        val q = okQuote("yahoo_chart_nulls.json")
        assertEquals(5, q.series.size)
        assertEquals(listOf(411.9, 413.1, 412.8, 414.2, 413.2), q.series.map { it.close })
        assertTrue(q.series.zipWithNext().all { (a, b) -> a.tMs < b.tMs })
    }

    @Test fun badSeriesPointsAreDroppedNotFatal() {
        val q = okQuote("yahoo_chart_bad_points.json")
        assertEquals(5, q.series.size) // -1.0, 0, 1e30 dropped
    }

    @Test fun errorObjectNotFoundMapsToNotFound() {
        val e = err("yahoo_chart_not_found.json", "NOPE")
        assertEquals(ErrorKind.NotFound, e.kind)
        assertTrue(e.message.contains("Not Found"))
    }

    @Test fun emptyResultIsNotFound() {
        assertEquals(ErrorKind.NotFound, err("yahoo_chart_empty_result.json").kind)
    }

    @Test fun malformedJsonIsParseError() {
        assertEquals(ErrorKind.Parse, err("yahoo_chart_malformed.json").kind)
    }

    @Test fun nonJsonHtmlIs200ButParseError() {
        val r = runBlocking { provider(FakeHttp { ok("<html>Edge: Too Many Requests</html>") }).quote("MSFT", ChartRange.D1) }
        assertEquals(ErrorKind.Parse, (r as FetchResult.Err).kind)
    }

    @Test fun missingChartObjectIsParseError() {
        val r = runBlocking { provider(FakeHttp { ok("""{"foo":1}""") }).quote("MSFT", ChartRange.D1) }
        assertEquals(ErrorKind.Parse, (r as FetchResult.Err).kind)
    }

    @Test fun missingMetaFieldsFallBackToSeriesAndRequestedSymbol() {
        val q = okQuote("yahoo_chart_missing_meta.json", "msft")
        assertEquals(413.54, q.price, 1e-9) // last close
        assertEquals("MSFT", q.symbol)
        assertEquals("msft", q.name) // no longName or shortName: requested symbol
        assertEquals("", q.currency) // never guessed; the WatchItem fills it
        assertEquals("", q.exchange)
        assertEquals(1_714_743_000_000L + 7 * 300_000L, q.asOfEpochMs) // last series timestamp, not the device clock
        assertNull(q.prevClose) // D1 and no meta previous close
    }

    @Test fun missingMetaWithoutSeriesIsParseError() {
        val r = runBlocking { provider(FakeHttp { ok("""{"chart":{"result":[{"meta":{"symbol":"MSFT"}}],"error":null}}""") }).quote("MSFT", ChartRange.D1) }
        assertEquals(ErrorKind.Parse, (r as FetchResult.Err).kind)
    }

    @Test fun resultWithoutMetaIsParseError() {
        val r = runBlocking { provider(FakeHttp { ok("""{"chart":{"result":[{"timestamp":[1]}],"error":null}}""") }).quote("MSFT", ChartRange.D1) }
        assertEquals(ErrorKind.Parse, (r as FetchResult.Err).kind)
    }

    @Test fun negativeMetaPriceFallsBackToLastCloseNotNegative() {
        val q = okQuote("yahoo_chart_negative_price.json")
        assertEquals(413.54, q.price, 1e-9)
    }

    @Test fun absurdMetaPriceIsNotTrusted() {
        val q = okQuote("yahoo_chart_absurd_price.json")
        assertEquals(413.54, q.price, 1e-9)
    }

    @Test fun absurdPriceWithoutSeriesIsParseError() {
        val body = """{"chart":{"result":[{"meta":{"symbol":"X","regularMarketPrice":1e300,"regularMarketTime":1714766400}}],"error":null}}"""
        val r = runBlocking { provider(FakeHttp { ok(body) }).quote("X", ChartRange.D1) }
        assertEquals(ErrorKind.Parse, (r as FetchResult.Err).kind)
    }

    @Test fun wrongTypesInsideJsonDoNotCrash() {
        val body = """{"chart":{"result":[{"meta":{"symbol":5,"regularMarketPrice":"41.5","regularMarketTime":"x","currency":null},"timestamp":"no","indicators":{"quote":"no"}}],"error":null}}"""
        val r = runBlocking { provider(FakeHttp { ok(body) }).quote("X", ChartRange.D1) }
        assertEquals(ErrorKind.Parse, (r as FetchResult.Err).kind)
    }

    @Test fun previousCloseForD1PrefersChartPreviousCloseThenPreviousClose() {
        val body = { extra: String ->
            """{"chart":{"result":[{"meta":{"symbol":"X","currency":"USD","regularMarketPrice":10.0,"regularMarketTime":1714766400$extra},"timestamp":[1714766000,1714766400],"indicators":{"quote":[{"close":[9.5,10.0]}]}}],"error":null}}"""
        }
        fun q(extra: String, range: ChartRange): Quote = runBlocking { provider(FakeHttp { ok(body(extra)) }).quote("X", range) }.let { (it as FetchResult.Ok).value }
        assertEquals(8.0, q(""","chartPreviousClose":8.0,"previousClose":9.0""", ChartRange.D1).prevClose!!, 1e-9)
        assertEquals(9.0, q(""","previousClose":9.0""", ChartRange.D1).prevClose!!, 1e-9)
        assertNull(q("", ChartRange.D1).prevClose)
        // Longer ranges: close before the range start, else the first point of the series.
        assertEquals(8.0, q(""","chartPreviousClose":8.0""", ChartRange.M1).prevClose!!, 1e-9)
        assertEquals(9.5, q("", ChartRange.M1).prevClose!!, 1e-9)
    }

    @Test fun requestUrlHasRangeIntervalAndEncodedSymbol() {
        val http = fixtureHttp("yahoo_chart_msft_1d.json")
        runBlocking { provider(http).quote("^GSPC", ChartRange.Y1) }
        val url = http.requests.single()
        assertEquals("https://query1.finance.yahoo.com/v8/finance/chart/%5EGSPC?range=1y&interval=1wk&includePrePost=false", url)
        runBlocking { provider(http).quote("USDCAD=X", ChartRange.D1) }
        assertTrue(http.requests.last().contains("/chart/USDCAD%3DX?range=1d&interval=5m"))
        assertEquals("application/json", http.headers.last()["Accept"])
    }

    @Test fun fallsBackToQuery2OnNetworkTimeoutAndServerErrors() {
        for (first in listOf(transportFail(ErrorKind.Network), transportFail(ErrorKind.Timeout), com.marketsboard.data.net.HttpResponse(503, "oops"))) {
            val http = FakeHttp { url -> if (url.contains("query1")) first else ok(Fixtures.text("yahoo_chart_msft_1d.json")) }
            val r = runBlocking { provider(http).quote("MSFT", ChartRange.D1) }
            assertTrue("fallback host should have answered after $first", r is FetchResult.Ok)
            assertEquals(2, http.requests.size)
            assertTrue(http.requests[1].startsWith("https://query2."))
        }
    }

    @Test fun doesNotTryQuery2ForNotFoundOrRateLimit() {
        val nf = FakeHttp { com.marketsboard.data.net.HttpResponse(404, Fixtures.text("yahoo_chart_not_found.json")) }
        val r1 = runBlocking { provider(nf).quote("NOPE", ChartRange.D1) }
        assertEquals(ErrorKind.NotFound, (r1 as FetchResult.Err).kind)
        assertEquals(1, nf.requests.size)

        val rl = FakeHttp { com.marketsboard.data.net.HttpResponse(429, "Too Many Requests", retryAfterMs = 30_000) }
        val r2 = runBlocking { provider(rl).quote("MSFT", ChartRange.D1) }
        assertEquals(ErrorKind.RateLimited(30_000), (r2 as FetchResult.Err).kind)
        assertEquals(1, rl.requests.size)
    }

    @Test fun bothHostsDownReturnsLastError() {
        val http = FakeHttp { transportFail(ErrorKind.Network, "refused") }
        val r = runBlocking { provider(http).quote("MSFT", ChartRange.D1) }
        assertEquals(ErrorKind.Network, (r as FetchResult.Err).kind)
        assertEquals(2, http.requests.size)
    }

    @Test fun otherHttpErrorsMapToHttpKind() {
        val r = runBlocking { provider(FakeHttp { com.marketsboard.data.net.HttpResponse(403, "<html>forbidden</html>") }).quote("MSFT", ChartRange.D1) }
        assertEquals(ErrorKind.Http(403), (r as FetchResult.Err).kind)
    }

    // ---- search ------------------------------------------------------------------------------------------------

    @Test fun searchKeepsOnlyAllowedTypesAndMapsFields() {
        val http = fixtureHttp("yahoo_search_msft.json")
        val r = runBlocking { provider(http).search("micro soft") } as FetchResult.Ok
        assertEquals(listOf("XEQT.TO", "MSFT", "^GSPC", "USDCAD=X"), r.value.map { it.symbol })
        val xeqt = r.value[0]
        assertEquals("iShares Core Equity ETF Portfolio", xeqt.name) // longname preferred
        assertEquals("Toronto", xeqt.exchange)
        assertEquals("ETF", xeqt.type)
        assertEquals("", xeqt.currency)
        assertEquals("S&P 500", r.value[2].name) // falls back to shortname
        assertEquals("https://query1.finance.yahoo.com/v1/finance/search?q=micro%20soft&quotesCount=8&newsCount=0", http.requests.single())
    }

    @Test fun searchEmptyAndBlankQuery() {
        val http = fixtureHttp("yahoo_search_empty.json")
        assertEquals(emptyList<Any>(), (runBlocking { provider(http).search("zzzz") } as FetchResult.Ok).value)
        assertEquals(emptyList<Any>(), (runBlocking { provider(http).search("   ") } as FetchResult.Ok).value)
        assertEquals(1, http.requests.size) // blank query never hits the network
    }

    @Test fun searchMalformedAndHttpErrors() {
        assertEquals(ErrorKind.Parse, (runBlocking { provider(FakeHttp { ok("nope") }).search("x") } as FetchResult.Err).kind)
        assertEquals(ErrorKind.Parse, (runBlocking { provider(FakeHttp { ok("""{"count":0}""") }).search("x") } as FetchResult.Err).kind)
        assertEquals(ErrorKind.Network, (runBlocking { provider(FakeHttp { transportFail(ErrorKind.Network) }).search("x") } as FetchResult.Err).kind)
    }

    @Test fun searchQueryIsLengthLimited() {
        val http = fixtureHttp("yahoo_search_empty.json")
        runBlocking { provider(http).search("a".repeat(500)) }
        assertTrue(http.requests.single().length < 200)
        assertNotNull(http.requests.single())
    }
}
