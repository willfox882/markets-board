package com.marketsboard.sandbox

import com.marketsboard.data.Clock
import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.Quote
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeMarketDataProviderTest {
    private val now = 1_714_750_000_000L
    private fun fake(b: FakeBehavior = FakeBehavior.Normal) = FakeMarketDataProvider(Clock { now }, b)
    private suspend fun FakeMarketDataProvider.q(s: String, r: ChartRange = ChartRange.D1): Quote = (quote(s, r) as FetchResult.Ok).value

    @Test fun anySymbolWorksAndIsStable() = runTest {
        val f = fake()
        for (s in listOf("XEQT.TO", "MSFT", "WHATEVER", "^GSPTSE", "USDCAD=X", "BTC-CAD", "CL=F")) {
            val a = f.q(s)
            assertEquals(a, f.q(s))
            assertTrue(a.price > 0 && a.series.isNotEmpty() && a.prevClose!! > 0)
            assertEquals(s, a.symbol)
        }
        assertEquals(fake().q("AAPL"), fake().q("AAPL")) // across instances too
    }

    @Test fun currencyInference() = runTest {
        val f = fake()
        assertEquals("CAD", f.q("XEQT.TO").currency)
        assertEquals("CAD", f.q("FOO.TO").currency)
        assertEquals("USD", f.q("MSFT").currency)
        assertEquals("USD", f.q("FOO").currency)
        assertEquals("CAD", f.q("DOGE-CAD").currency)
        assertEquals("CAD", f.q("USDCAD=X").currency)
        assertEquals("EUR", f.q("USDEUR=X").currency)
        assertEquals("TSX", f.q("XEQT.TO").exchange)
    }

    @Test fun defaultsLookLikeTheRealThing() = runTest {
        val x = fake().q("XEQT.TO")
        assertEquals("iShares Core Equity ETF Portfolio", x.name)
        assertTrue(x.price in 30.0..60.0)
        assertTrue(fake().q("MSFT").price in 300.0..550.0)
    }

    @Test fun seriesLengthAndOrderPerRange() = runTest {
        val f = fake()
        for (r in ChartRange.entries) {
            val q = f.q("MSFT", r)
            assertEquals(r, q.range)
            assertTrue(q.series.size > 10)
            assertTrue(q.series.zipWithNext().all { (a, b) -> a.tMs < b.tMs })
            assertEquals(q.series.last().tMs, q.asOfEpochMs)
            assertEquals(q.series.last().close, q.price, 0.0)
        }
    }

    @Test fun valuesMoveWithTimeButStayPlausible() = runTest {
        var t = now
        val f = FakeMarketDataProvider(Clock { t })
        val a = f.q("MSFT")
        t += 3_600_000
        val b = f.q("MSFT")
        assertTrue(b.asOfEpochMs > a.asOfEpochMs)
        assertTrue(Math.abs(b.price / a.price - 1) < 0.15)
    }

    @Test fun offlineAndRateLimited() = runTest {
        val f = fake(FakeBehavior.Offline)
        assertEquals(ErrorKind.Network, (f.quote("MSFT", ChartRange.D1) as FetchResult.Err).kind)
        assertEquals(ErrorKind.Network, (f.search("ms") as FetchResult.Err).kind)
        f.behavior = FakeBehavior.RateLimited
        f.retryAfterMs = 12_000
        assertEquals(ErrorKind.RateLimited(12_000), (f.quote("MSFT", ChartRange.D1) as FetchResult.Err).kind)
        f.behavior = FakeBehavior.Normal
        assertTrue(f.quote("MSFT", ChartRange.D1) is FetchResult.Ok)
        assertEquals(3, f.quoteCalls)
    }

    @Test fun slowDelaysByConfiguredTime() = runTest {
        val f = fake(FakeBehavior.Slow)
        f.slowDelayMs = 4_000
        f.q("MSFT")
        assertEquals(4_000L, currentTime)
    }

    @Test fun partialFailureFailsSomeSymbolsDeterministically() = runTest {
        val f = fake(FakeBehavior.PartialFailure)
        val symbols = listOf("A", "B", "C", "D", "E", "F", "G", "H")
        val results = symbols.map { f.quote(it, ChartRange.D1) is FetchResult.Ok }
        assertTrue(results.any { it } && results.any { !it })
        assertEquals(results, symbols.map { f.quote(it, ChartRange.D1) is FetchResult.Ok })
        val failed = symbols.filterIndexed { i, _ -> !results[i] }.first()
        assertEquals(ErrorKind.Http(503), (f.quote(failed, ChartRange.D1) as FetchResult.Err).kind)
    }

    @Test fun badDataIsRejectedByTheSharedValidator() = runTest {
        val r = fake(FakeBehavior.BadData).quote("MSFT", ChartRange.D1)
        assertEquals(ErrorKind.Parse, (r as FetchResult.Err).kind)
    }

    @Test fun unknownSymbolsAreNotFound() = runTest {
        assertEquals(ErrorKind.NotFound, (fake().quote("ZZZNOPE", ChartRange.D1) as FetchResult.Err).kind)
    }

    @Test fun searchFiltersTheCatalogue() = runTest {
        val f = fake()
        val r = (f.search("xeqt") as FetchResult.Ok).value
        assertEquals(listOf("XEQT.TO"), r.map { it.symbol })
        assertTrue((f.search("shop") as FetchResult.Ok).value.any { it.symbol == "SHOP.TO" })
        assertEquals(emptyList<Any>(), (f.search("  ") as FetchResult.Ok).value)
        assertEquals(emptyList<Any>(), (f.search("qqqqqqqq") as FetchResult.Ok).value)
    }

    @Test fun catalogueCoversTheOwnersSuggestions() {
        val syms = FakeMarketDataProvider.CATALOGUE.map { it.symbol }.toSet()
        for (s in listOf("^GSPTSE", "^GSPC", "^IXIC", "USDCAD=X", "CL=F", "GC=F", "BTC-CAD", "ETH-CAD", "VFV.TO", "XIU.TO", "SHOP.TO", "RY.TO", "TD.TO", "ENB.TO", "AAPL", "NVDA")) {
            assertTrue("$s missing", s in syms)
        }
    }
}
