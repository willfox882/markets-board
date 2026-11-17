package com.marketsboard.ui

import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.Quote
import com.marketsboard.data.model.SeriesPoint
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.quoteOf
import com.marketsboard.data.repo.QuoteRepository
import com.marketsboard.data.repo.QuoteState
import com.marketsboard.data.repo.QuoteStatus
import com.marketsboard.ui.markets.ChartLoad
import com.marketsboard.ui.markets.LiveKind
import com.marketsboard.ui.markets.RowStatus
import com.marketsboard.ui.markets.UiMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure mapping from data-layer state to what a row, the header and the detail screen show. */
class UiMapperTest {
    private val now = 1_794_851_100_000L // 2026-11-16 17:45 UTC = 12:45 PM ET
    private val xeqt = WatchItem("XEQT.TO", "iShares Core Equity ETF Portfolio", "CAD", "TSX")
    private val msft = WatchItem("MSFT", "Microsoft Corporation", "USD", "NASDAQ")

    private fun q(sym: String = "XEQT.TO", price: Double = 42.0, prev: Double? = 41.0, cur: String = "CAD", exch: String = "Toronto") =
        quoteOf(sym, price, asOf = now - 60_000, currency = cur, exchange = exch, prevClose = prev).copy(exchangeTimezoneName = "America/Toronto")

    private fun fresh(q: Quote) = QuoteState(q, QuoteStatus.Fresh, now, null, false, 0)
    private fun stale(q: Quote, age: Long, ageKnown: Boolean = true) = QuoteState(q, QuoteStatus.Stale(age, ageKnown), now, null, false, 0)
    private fun failed(q: Quote?, kind: ErrorKind, age: Long = 120_000) = QuoteState(
        q, if (q != null) QuoteStatus.Stale(age) else QuoteStatus.Error(kind), null, FetchResult.Err(kind, "x"), false, null,
    )

    // ---- row status ------------------------------------------------------------------------------------------

    @Test fun noStateOrLoadingIsLoadingWithNoNumbers() {
        for (st in listOf(null, QuoteState(null, QuoteStatus.Loading))) {
            val r = UiMapper.row(xeqt, st)
            assertEquals(RowStatus.Loading, r.status)
            assertNull(r.price)
            assertTrue(r.change.isNaN())
            assertEquals("XEQT.TO", r.symbol)
            assertEquals("CAD", r.currency) // known from the WatchItem before any quote
        }
    }

    @Test fun freshRowHasNumbersAndDerivedChange() {
        val r = UiMapper.row(xeqt, fresh(q(price = 42.0, prev = 40.0)))
        assertEquals(RowStatus.Fresh, r.status)
        assertEquals(42.0, r.price!!, 0.0)
        assertEquals(2.0, r.change, 1e-9)
        assertEquals(5.0, r.changePct, 1e-9)
        assertFalse(r.dimmed)
    }

    @Test fun staleRowShowsTheAge() {
        val r = UiMapper.row(xeqt, stale(q(), 5 * 60_000 + 10_000))
        assertEquals(RowStatus.Stale("5 min ago", 5 * 60_000 + 10_000L), r.status)
        assertEquals(42.0, r.price!!, 0.0)
        assertEquals(RowStatus.Stale("2 h ago", 2 * 3_600_000L + 5), UiMapper.row(xeqt, stale(q(), 2 * 3_600_000L + 5)).status)
        assertEquals(RowStatus.Stale("3 d ago", 3 * 86_400_000L), UiMapper.row(xeqt, stale(q(), 3 * 86_400_000L)).status)
    }

    @Test fun unknownAgeHasNoAgeText() {
        assertEquals(RowStatus.Stale(""), UiMapper.row(xeqt, stale(q(), 0, ageKnown = false)).status)
    }

    @Test fun failedRefreshKeepsLastNumbersAndDimsThem() {
        val r = UiMapper.row(xeqt, failed(q(), ErrorKind.Network, 9 * 60_000))
        assertEquals(RowStatus.Error("Can't reach the data service", "9 min ago", 9 * 60_000L), r.status)
        assertEquals(42.0, r.price!!, 0.0)
        assertTrue(r.dimmed)
    }

    @Test fun errorWithNothingHeldHasNoPrice() {
        val r = UiMapper.row(xeqt, failed(null, ErrorKind.NotFound))
        assertEquals(RowStatus.Error("Symbol not found"), r.status)
        assertNull(r.price)
    }

    @Test fun errorReasonsMapForEveryKind() {
        fun reason(k: ErrorKind, cd: Long = 0) = (UiMapper.row(xeqt, failed(null, k)).status as RowStatus.Error).let { it.reason }
        assertEquals("Can't reach the data service", reason(ErrorKind.Network))
        assertEquals("Check the date and time", reason(ErrorKind.ClockOrTls))
        assertEquals("Rate limited, retrying in 2 min", reason(ErrorKind.RateLimited(120_000)))
        assertEquals("Symbol not found", reason(ErrorKind.NotFound))
    }

    @Test fun rateLimitUsesLiveCooldown() {
        val r = UiMapper.row(xeqt, failed(q(), ErrorKind.RateLimited(30_000)), cooldownMs = 3 * 60_000)
        assertEquals("Rate limited, retrying in 3 min", (r.status as RowStatus.Error).reason)
    }

    // ---- numbers ---------------------------------------------------------------------------------------------

    @Test fun nullPreviousCloseRendersWithoutChange() {
        val r = UiMapper.row(xeqt, fresh(q(prev = null)))
        assertEquals(42.0, r.price!!, 0.0)
        assertNull(r.prevClose)
        assertTrue(r.change.isNaN())
        assertTrue(r.changePct.isNaN())
        assertEquals(NO_VALUE, formatPercent(r.changePct))
        assertEquals(Dash.dim, changeColor(r.changePct))
    }

    @Test fun fxUsesFourDecimalsAndOthersTwo() {
        val fx = WatchItem("USDCAD=X", "USD/CAD", "CAD", "CCY")
        val fxq = q("USDCAD=X", 1.3712, 1.3689).copy(instrumentType = "CURRENCY", priceHint = null)
        val r = UiMapper.row(fx, fresh(fxq))
        assertEquals(4, r.decimals)
        assertEquals("1.3712", formatPrice(r.price!!, r.decimals))
        assertEquals("+0.0023", formatSigned(r.change, r.decimals))
        assertEquals(2, UiMapper.row(xeqt, fresh(q())).decimals)
        // priceHint wins when the provider says so
        assertEquals(3, UiMapper.row(xeqt, fresh(q().copy(priceHint = 3))).decimals)
        // no quote yet but an FX symbol: still 4
        assertEquals(4, UiMapper.row(fx, null).decimals)
    }

    @Test fun pencePricesKeepTheGbxCodeAndNeverBecomePounds() {
        val vod = WatchItem("VOD.L", "Vodafone Group", "GBp", "LSE")
        val r = UiMapper.row(vod, fresh(q("VOD.L", 68.42, 67.9, cur = "GBp", exch = "LSE")))
        assertEquals("GBX", r.currency)
        assertEquals("68.42", formatPrice(r.price!!, r.decimals))
        // before the first quote the stored code is normalised the same way
        assertEquals("GBX", UiMapper.row(vod, null).currency)
    }

    @Test fun nativeCurrencyComesFromTheQuoteElseTheItem() {
        assertEquals("USD", UiMapper.row(msft, fresh(q("MSFT", cur = "USD"))).currency)
        assertEquals("CAD", UiMapper.row(xeqt, fresh(q(cur = ""))).currency)
    }

    @Test fun sixDigitPricesFormatWithGrouping() {
        assertEquals("123,456.78", formatPrice(123_456.78, 2))
        assertEquals("1,234,567.89", formatPrice(1_234_567.89, 2))
    }

    // ---- names and exchange ----------------------------------------------------------------------------------

    @Test fun exchangeLabelPrefersTheWatchItemSoDefaultsSayTsxAndNasdaq() {
        assertEquals("TSX", UiMapper.row(xeqt, fresh(q(exch = "Toronto"))).exchange)
        assertEquals("NASDAQ", UiMapper.row(msft, fresh(q("MSFT", exch = "NasdaqGS"))).exchange)
    }

    @Test fun exchangeFallsBackToTheQuoteAndIsShortened() {
        val added = WatchItem("SHOP.TO", "Shopify", "CAD", "")
        assertEquals("TSX", UiMapper.row(added, fresh(q("SHOP.TO", exch = "Toronto"))).exchange)
        assertEquals("", UiMapper.row(added, null).exchange)
        // an item stored from a provider result carries Yahoo's long name: shortened too
        assertEquals("NASDAQ", UiMapper.row(msft.copy(exchange = "NasdaqGS"), null).exchange)
    }

    @Test fun displayNameOverrideWinsThenStoredNameThenQuoteName() {
        assertEquals("My ETF", UiMapper.row(xeqt.copy(displayName = "My ETF"), fresh(q())).name)
        assertEquals("iShares Core Equity ETF Portfolio", UiMapper.row(xeqt, fresh(q())).name)
        val unverified = WatchItem("SHOP.TO", "SHOP.TO", "", "")
        assertEquals("SHOP.TO", UiMapper.row(unverified, null).name)
        assertEquals("Shopify Inc.", UiMapper.row(unverified, fresh(q("SHOP.TO").copy(name = "Shopify Inc."))).name)
    }

    // ---- header ----------------------------------------------------------------------------------------------

    private fun key(s: String) = QuoteRepository.keyFor(s, ChartRange.D1)

    @Test fun headerIsLiveOnlyWhenEverythingIsFreshAndAMarketIsOpen() {
        val h = UiMapper.header(listOf(xeqt, msft), mapOf(key("XEQT.TO") to fresh(q()), key("MSFT") to fresh(q("MSFT", cur = "USD"))), now)
        assertEquals(LiveKind.Live, h.kind)
        assertEquals("Live", h.label)
        assertEquals("Updated 12:44 PM ET", h.updated)
    }

    @Test fun headerIsClosedWhenEveryMarketIsClosed() {
        val closed = q().copy(marketState = "CLOSED")
        val h = UiMapper.header(listOf(xeqt), mapOf(key("XEQT.TO") to fresh(closed)), now)
        assertEquals(LiveKind.Closed, h.kind)
        assertEquals("Markets closed", h.label)
    }

    @Test fun headerIsDelayedWhenAnythingIsStaleOrFailed() {
        val st = mapOf(key("XEQT.TO") to fresh(q()), key("MSFT") to stale(q("MSFT"), 6 * 60_000))
        assertEquals(LiveKind.Delayed, UiMapper.header(listOf(xeqt, msft), st, now).kind)
        val err = mapOf(key("XEQT.TO") to fresh(q()), key("MSFT") to failed(q("MSFT"), ErrorKind.Http(503)))
        assertEquals(LiveKind.Delayed, UiMapper.header(listOf(xeqt, msft), err, now).kind)
    }

    @Test fun headerSaysOfflineWhenEverySymbolFailedWithNoNetwork() {
        val st = mapOf(key("XEQT.TO") to failed(q(), ErrorKind.Network), key("MSFT") to failed(null, ErrorKind.Network))
        val h = UiMapper.header(listOf(xeqt, msft), st, now)
        assertEquals(LiveKind.Offline, h.kind)
        assertEquals("Offline", h.label)
    }

    @Test fun oneTimedOutSymbolWhileAnotherIsLiveIsNotOfflineAndATimeoutAloneNeverIs() {
        val one = mapOf(key("XEQT.TO") to fresh(q()), key("MSFT") to failed(q("MSFT"), ErrorKind.Timeout))
        assertEquals(LiveKind.Delayed, UiMapper.header(listOf(xeqt, msft), one, now).kind)
        val oneNet = mapOf(key("XEQT.TO") to fresh(q()), key("MSFT") to failed(q("MSFT"), ErrorKind.Network))
        assertEquals("a single failing symbol is the ordinary Delayed state", LiveKind.Delayed, UiMapper.header(listOf(xeqt, msft), oneNet, now).kind)
        val allTimeout = mapOf(key("XEQT.TO") to failed(q(), ErrorKind.Timeout), key("MSFT") to failed(q("MSFT"), ErrorKind.Timeout))
        assertEquals(LiveKind.Delayed, UiMapper.header(listOf(xeqt, msft), allTimeout, now).kind)
        val none = mapOf(key("XEQT.TO") to failed(null, ErrorKind.Timeout), key("MSFT") to failed(null, ErrorKind.Timeout))
        assertEquals("Delayed", UiMapper.header(listOf(xeqt, msft), none, now).label)
    }

    @Test fun theHeaderAndTheShellBannerUseTheSameRule() {
        val items = listOf(xeqt, msft)
        val cases = listOf(
            mapOf(key("XEQT.TO") to failed(q(), ErrorKind.Network), key("MSFT") to failed(q("MSFT"), ErrorKind.Network)),
            mapOf(key("XEQT.TO") to fresh(q()), key("MSFT") to failed(q("MSFT"), ErrorKind.Network)),
            mapOf(key("XEQT.TO") to failed(q(), ErrorKind.Timeout), key("MSFT") to failed(q("MSFT"), ErrorKind.Timeout)),
            mapOf(key("XEQT.TO") to fresh(q()), key("MSFT") to failed(q("MSFT"), ErrorKind.Timeout)),
            mapOf(key("XEQT.TO") to failed(null, ErrorKind.Network), key("MSFT") to failed(null, ErrorKind.Network)),
        )
        for (st in cases) {
            val banner = com.marketsboard.refresh.ConnectivityRules.marketsOffline(com.marketsboard.refresh.NetworkStatus.Online, st, items.map { it.symbol })
            assertEquals("header and banner disagree for $st", banner, UiMapper.header(items, st, now).kind == LiveKind.Offline)
        }
    }

    @Test fun headerWithNothingLoadedYetIsLoadingAndNeverLive() {
        assertEquals(LiveKind.Loading, UiMapper.header(listOf(xeqt, msft), emptyMap(), now).kind)
        assertEquals(LiveKind.None, UiMapper.header(emptyList(), emptyMap(), now).kind)
        assertEquals("", UiMapper.header(listOf(xeqt), emptyMap(), now).updated)
    }

    @Test fun aLoadingRowDoesNotMakeTheHeaderDelayed() {
        val st = mapOf(key("XEQT.TO") to fresh(q()), key("MSFT") to QuoteState(null, QuoteStatus.Loading))
        assertEquals(LiveKind.Live, UiMapper.header(listOf(xeqt, msft), st, now).kind)
    }

    @Test fun headerUpdatedUsesTheNewestQuoteInItsOwnZone() {
        val older = q("XEQT.TO").copy(asOfEpochMs = now - 600_000, exchangeTimezoneName = "America/Toronto")
        val newer = q("MSFT", cur = "USD").copy(asOfEpochMs = now - 60_000, exchangeTimezoneName = "Europe/London")
        val h = UiMapper.header(listOf(xeqt, msft), mapOf(key("XEQT.TO") to fresh(older), key("MSFT") to fresh(newer)), now)
        assertEquals("Updated 5:44 PM GMT", h.updated)
    }

    @Test fun clockSkewComesFromTheStateOrATlsDateFailure() {
        assertFalse(UiMapper.clockSkew(mapOf("A" to fresh(q()))))
        assertTrue(UiMapper.clockSkew(mapOf("A" to fresh(q()).copy(clockSkew = true))))
        assertTrue(UiMapper.clockSkew(mapOf("A" to failed(null, ErrorKind.ClockOrTls))))
        assertEquals("Check the date and time", (UiMapper.row(xeqt, failed(null, ErrorKind.ClockOrTls)).status as RowStatus.Error).reason)
    }

    // ---- detail ----------------------------------------------------------------------------------------------

    private fun seriesQuote(range: ChartRange, price: Double = 50.0, prev: Double? = 40.0) = q(price = price, prev = prev).copy(
        range = range, series = List(10) { SeriesPoint(1_000L + it, 40.0 + it) },
    )

    @Test fun detailOneDayUsesBaselineAndPreviousClose() {
        val st = mapOf(key("XEQT.TO") to fresh(seriesQuote(ChartRange.D1, 42.0, 41.0).copy(dayHigh = 43.0, dayLow = 40.5, source = "yahoo")))
        val d = UiMapper.detail(xeqt, ChartRange.D1, st, 0, now)
        assertEquals(ChartLoad.Ready, d.chart)
        assertEquals(41f, d.baseline)
        assertEquals(41.0, d.refClose!!, 0.0)
        assertEquals("today", d.rangeLabel)
        val stats = d.stats.associate { it.label to it.value }
        assertEquals("41.00", stats["Prev. session close"])
        assertEquals("43.00", stats["Day high"])
        assertEquals("40.50", stats["Day low"])
        assertEquals("TSX", stats["Exchange"])
        assertEquals("CAD", stats["Currency"])
        assertEquals("yahoo", stats["Source"])
        assertEquals("Open", stats["Market"])
        assertEquals("12:44 PM ET", stats["As of"])
    }

    @Test fun detailLongerRangeHasNoBaselineAndUsesTheRangeReference() {
        val st = mapOf(
            key("XEQT.TO") to fresh(seriesQuote(ChartRange.D1, 50.0, 49.0)),
            QuoteRepository.keyFor("XEQT.TO", ChartRange.Y1) to fresh(seriesQuote(ChartRange.Y1, 50.0, 40.0)),
        )
        val d = UiMapper.detail(xeqt, ChartRange.Y1, st, 0, now)
        assertNull(d.baseline)
        assertEquals(40.0, d.refClose!!, 0.0)
        assertEquals(25.0, d.changePct, 1e-9)
        assertEquals(10, d.series.size)
        assertEquals("1 year", d.rangeLabel)
    }

    @Test fun detailChartStatesLoadingErrorAndNoData() {
        val d1 = mapOf(key("XEQT.TO") to fresh(seriesQuote(ChartRange.D1)))
        assertEquals(ChartLoad.Loading, UiMapper.detail(xeqt, ChartRange.M3, d1, 0, now).chart)
        val err = d1 + (QuoteRepository.keyFor("XEQT.TO", ChartRange.M3) to failed(null, ErrorKind.Network))
        val e = UiMapper.detail(xeqt, ChartRange.M3, err, 0, now)
        assertEquals(ChartLoad.Error, e.chart)
        assertEquals("Can't reach the data service", e.chartError)
        // a quote without a series (fallback provider) is an error for the chart, not a crash
        val flat = d1 + (QuoteRepository.keyFor("XEQT.TO", ChartRange.M3) to fresh(q().copy(range = ChartRange.M3, series = emptyList())))
        assertEquals(ChartLoad.Error, UiMapper.detail(xeqt, ChartRange.M3, flat, 0, now).chart)
    }

    @Test fun detailWithoutAnyDataStillMaps() {
        val d = UiMapper.detail(xeqt, ChartRange.D1, emptyMap(), 0, now)
        assertNull(d.price)
        assertTrue(d.change.isNaN())
        assertEquals(NO_VALUE, d.stats.first { it.label == "Prev. session close" }.value)
        assertEquals(NO_VALUE, d.stats.first { it.label == "As of" }.value)
    }

    // ---- audit round: chart context --------------------------------------------------------------------------

    @Test fun detailChartHasStartEndHighLowAndAnAsOfLabel() {
        val pts = List(5) { SeriesPoint(1_794_800_000_000L + it * 300_000L, 40.0 + it * 2) }
        val qr = q(price = 48.0, prev = 40.0).copy(range = ChartRange.D1, series = pts)
        val st = mapOf(key("XEQT.TO") to fresh(qr))
        val d = UiMapper.detail(xeqt, ChartRange.D1, st, 0, now)
        assertEquals(48.0, d.rangeHigh!!, 0.0)
        assertEquals(40.0, d.rangeLow!!, 0.0)
        assertTrue(d.chartStart.endsWith("ET"))
        assertTrue(d.chartEnd.startsWith("as of "))
        assertEquals("", d.chartNote) // 1D failures are shown by the row status instead
        val y = UiMapper.detail(xeqt, ChartRange.Y1, st + (QuoteRepository.keyFor("XEQT.TO", ChartRange.Y1) to fresh(qr.copy(range = ChartRange.Y1))), 0, now)
        assertTrue("1Y labels are dates: ${y.chartStart}", Regex("[A-Z][a-z]{2} \\d{1,2}, \\d{4}").matches(y.chartStart))
    }

    @Test fun aHeldRangeThatFailedToRefreshCarriesAnAmberNote() {
        val d1 = fresh(seriesQuote(ChartRange.D1))
        val m3 = failed(seriesQuote(ChartRange.M3), ErrorKind.Network, 40 * 60_000)
        val d = UiMapper.detail(xeqt, ChartRange.M3, mapOf(key("XEQT.TO") to d1, QuoteRepository.keyFor("XEQT.TO", ChartRange.M3) to m3), 0, now)
        assertEquals(ChartLoad.Ready, d.chart) // the held chart stays visible
        assertEquals("Chart not refreshed: can't reach the data service", d.chartNote)
        val stale = d1.let { mapOf(key("XEQT.TO") to it, QuoteRepository.keyFor("XEQT.TO", ChartRange.M3) to stale(seriesQuote(ChartRange.M3), 7 * 3_600_000L)) }
        assertEquals("Chart updated 7 h ago", UiMapper.detail(xeqt, ChartRange.M3, stale, 0, now).chartNote)
    }
}
