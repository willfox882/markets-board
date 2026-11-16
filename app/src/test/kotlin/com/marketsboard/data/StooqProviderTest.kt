package com.marketsboard.data

import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.net.HttpResponse
import com.marketsboard.data.provider.StooqProvider
import com.marketsboard.data.provider.YahooToStooq
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StooqProviderTest {
    private fun provider(http: FakeHttp) = StooqProvider(http)
    private fun csv(name: String) = FakeHttp { ok(Fixtures.text(name)) }

    @Test fun parsesCsvQuote() {
        val http = csv("stooq_msft.csv")
        val q = (runBlocking { provider(http).quote("MSFT", ChartRange.D1) } as FetchResult.Ok).value
        assertEquals("MSFT", q.symbol)
        assertEquals(413.54, q.price, 1e-9)
        assertEquals(414.2, q.dayHigh!!, 1e-9)
        assertEquals(408.5, q.dayLow!!, 1e-9)
        assertNull(q.prevClose) // Stooq's Open is not the previous close
        assertTrue(q.series.isEmpty())
        assertEquals("", q.currency) // the caller's WatchItem supplies it
        // 2024-05-03 22:00:14 Warsaw time (CEST, UTC+2) = 20:00:14 UTC
        assertEquals(Instant.parse("2024-05-03T20:00:14Z").toEpochMilli(), q.asOfEpochMs)
        assertEquals("https://stooq.com/q/l/?s=msft.us&f=sd2t2ohlcv&h&e=csv", http.requests.single())
    }

    @Test fun noDataMarkerIsNotFound() {
        val r = runBlocking { provider(csv("stooq_nd.csv")).quote("ZZZZ", ChartRange.D1) }
        assertEquals(ErrorKind.NotFound, (r as FetchResult.Err).kind)
    }

    @Test fun wrongColumnsAreParseError() {
        val r = runBlocking { provider(csv("stooq_wrong_columns.csv")).quote("MSFT", ChartRange.D1) }
        assertEquals(ErrorKind.Parse, (r as FetchResult.Err).kind)
    }

    @Test fun emptyOrHeaderOnlyBodyIsParseError() {
        for (body in listOf("", "Symbol,Date,Time,Open,High,Low,Close,Volume\n", "<html></html>")) {
            val r = runBlocking { provider(FakeHttp { ok(body) }).quote("MSFT", ChartRange.D1) }
            assertEquals("body: $body", ErrorKind.Parse, (r as FetchResult.Err).kind)
        }
    }

    @Test fun fxRowWithEmptyVolumeParses() {
        val q = (runBlocking { provider(csv("stooq_fx.csv")).quote("USDCAD=X", ChartRange.D1) } as FetchResult.Ok).value
        assertEquals(1.3685, q.price, 1e-9)
    }

    @Test fun invalidCloseValuesRejected() {
        val bad = "Symbol,Date,Time,Open,High,Low,Close,Volume\nMSFT.US,2024-05-03,22:00:14,1,2,3,-5,1\n"
        assertTrue(runBlocking { provider(FakeHttp { ok(bad) }).quote("MSFT", ChartRange.D1) } is FetchResult.Err)
    }

    @Test fun noMappingMeansNoFallbackAndNoNetworkCall() {
        val http = csv("stooq_msft.csv")
        val p = provider(http)
        assertFalse(p.supports("XEQT.TO"))
        val r = runBlocking { p.quote("XEQT.TO", ChartRange.D1) } as FetchResult.Err
        assertEquals(ErrorKind.NotFound, r.kind)
        assertTrue(r.message.contains("no fallback"))
        assertTrue(http.requests.isEmpty())
    }

    @Test fun httpErrorsPropagateTyped() {
        val r = runBlocking { provider(FakeHttp { HttpResponse(429, "", retryAfterMs = 5000) }).quote("MSFT", ChartRange.D1) }
        assertEquals(ErrorKind.RateLimited(5000), (r as FetchResult.Err).kind)
    }

    @Test fun searchIsEmptyNotSupported() {
        assertEquals(emptyList<Any>(), (runBlocking { provider(csv("stooq_msft.csv")).search("msft") } as FetchResult.Ok).value)
    }

    @Test fun symbolMappingTable() {
        val cases = mapOf(
            "MSFT" to "msft.us", "aapl" to "aapl.us", "BRK-B" to "brk-b.us",
            "^GSPC" to "^spx", "^IXIC" to "^ndq", "^DJI" to "^dji",
            "USDCAD=X" to "usdcad", "EURUSD=X" to "eurusd",
            "CL=F" to "cl.f", "GC=F" to "gc.f",
        )
        cases.forEach { (y, s) -> assertEquals(y, s, YahooToStooq.map(y)) }
    }

    @Test fun unmappableSymbolsReturnNull() {
        for (s in listOf("XEQT.TO", "SHOP.TO", "^GSPTSE", "BTC-CAD", "ETH-CAD", "VOD.L", "", "TOOLONGTICKER", "A B")) {
            assertNull("$s should have no mapping", YahooToStooq.map(s))
        }
    }
}
