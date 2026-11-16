package com.marketsboard

import com.marketsboard.data.FakeClock
import com.marketsboard.data.FakeHttp
import com.marketsboard.data.Fixtures
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.net.HttpResponse
import com.marketsboard.data.repo.QuoteStatus
import com.marketsboard.data.store.AddOutcome
import com.marketsboard.data.tempDir
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real wiring (Yahoo and Stooq providers, logging, cache, store, repository) against recorded fixtures. */
class AppGraphTest {
    private val clock = FakeClock(1_714_750_000_000L)

    private fun serve(url: String): HttpResponse = when {
        "/chart/XEQT.TO" in url -> HttpResponse(200, Fixtures.text("yahoo_chart_xeqt_1d.json"))
        "/chart/MSFT" in url -> HttpResponse(200, Fixtures.text("yahoo_chart_msft_1d.json"))
        "/chart/" in url -> HttpResponse(404, Fixtures.text("yahoo_chart_not_found.json"))
        "stooq.com" in url -> HttpResponse(200, Fixtures.text("stooq_msft.csv"))
        else -> HttpResponse(500, "")
    }

    @Test fun defaultWatchlistRefreshesInOwnCurrencies() = runBlocking {
        val g = AppGraph(tempDir(), clock, FakeHttp(::serve))
        assertEquals(listOf("XEQT.TO", "MSFT"), g.watchlist.items.value.map { it.symbol })
        g.quotes.refresh(g.watchlist.items.value.map { it.symbol })
        val x = g.quotes.stateOf("XEQT.TO")!!
        val m = g.quotes.stateOf("MSFT")!!
        assertEquals("CAD", x.quote!!.currency)
        assertEquals("USD", m.quote!!.currency)
        assertEquals(QuoteStatus.Fresh, x.status)
        assertEquals(41.87, x.quote!!.price, 1e-9)
        assertEquals(413.54, m.quote!!.price, 1e-9)
    }

    @Test fun fetchLogRecordsHostsNotUrls() = runBlocking {
        val g = AppGraph(tempDir(), clock, FakeHttp(::serve))
        g.quotes.refresh(listOf("MSFT"))
        val e = g.fetchLog.events.value
        assertEquals(1, e.size)
        assertEquals("yahoo", e[0].provider)
        assertEquals("query1.finance.yahoo.com", e[0].host)
        assertFalse(e.joinToString().contains("MSFT"))
    }

    @Test fun yahooDownFallsBackToStooqForMappedSymbols() = runBlocking {
        val http = FakeHttp { url -> if ("stooq.com" in url) serve(url) else HttpResponse(0, failure = com.marketsboard.data.model.FetchResult.Err(ErrorKind.Network, "down")) }
        val g = AppGraph(tempDir(), clock, http)
        g.quotes.refresh(listOf("MSFT", "XEQT.TO"))
        val m = g.quotes.stateOf("MSFT")!!
        assertEquals(413.54, m.quote!!.price, 1e-9)
        assertEquals("USD", m.quote!!.currency) // from the WatchItem hint
        assertEquals("NASDAQ", m.quote!!.exchange)
        assertEquals("Microsoft Corporation", m.quote!!.name)
        val x = g.quotes.stateOf("XEQT.TO")!!
        assertEquals(QuoteStatus.Error(ErrorKind.Network), x.status) // no Stooq mapping: graceful, no crash
        assertTrue(http.requests.none { "XEQT" in it && "stooq" in it })
    }

    @Test fun validateAndAddThroughRealYahooProvider() = runBlocking {
        val g = AppGraph(tempDir(), clock, FakeHttp(::serve))
        val r = g.watchlist.validateAndAdd("zzzz", g.yahoo)
        assertEquals(AddOutcome.NotFound, r)
        val ok = g.watchlist.validateAndAdd("msft", g.yahoo)
        assertTrue(ok is AddOutcome.Duplicate)
    }

    @Test fun graphSurvivesRestartWithUserEditsAndCache() = runBlocking {
        val dir: File = tempDir()
        val g1 = AppGraph(dir, clock, FakeHttp(::serve))
        g1.watchlist.remove("XEQT.TO")
        g1.quotes.refresh(listOf("MSFT"))
        val g2 = AppGraph(dir, clock, FakeHttp { HttpResponse(500, "") })
        assertEquals(listOf("MSFT"), g2.watchlist.items.value.map { it.symbol })
        g2.quotes.hydrate(listOf("MSFT"))
        assertEquals(413.54, g2.quotes.stateOf("MSFT")!!.quote!!.price, 1e-9)
    }

    @Test fun removeHookDropsWatchlistItemAndEverythingHeldForIt() = runBlocking {
        val dir = tempDir()
        val g = AppGraph(dir, clock, FakeHttp(::serve))
        g.quotes.refresh(listOf("MSFT", "XEQT.TO"))
        assertTrue(g.cache.get("quote/MSFT/1D") != null)
        assertTrue(g.removeFromWatchlist("MSFT") is com.marketsboard.data.store.EditResult.Done)
        assertEquals(listOf("XEQT.TO"), g.watchlist.items.value.map { it.symbol })
        assertEquals(null, g.quotes.stateOf("MSFT"))
        assertEquals(null, g.cache.get("quote/MSFT/1D", allowExpired = true))
        assertTrue(g.quotes.stateOf("XEQT.TO") != null)
        assertTrue(g.removeFromWatchlist("MSFT") is com.marketsboard.data.store.EditResult.NotFound)
    }
}
