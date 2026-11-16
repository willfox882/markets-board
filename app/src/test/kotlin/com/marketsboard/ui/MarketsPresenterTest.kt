package com.marketsboard.ui

import com.marketsboard.FakeProvider
import com.marketsboard.FakeQuotesSource
import com.marketsboard.VirtualClock
import com.marketsboard.data.MarketsData
import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.Instrument
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.quoteOf
import com.marketsboard.data.store.WatchlistStore
import com.marketsboard.data.tempDir
import com.marketsboard.err
import com.marketsboard.freshState
import com.marketsboard.refresh.Refresher
import com.marketsboard.ui.markets.ChartLoad
import com.marketsboard.ui.markets.MarketsPresenter
import com.marketsboard.ui.markets.RowStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Editor, add / replace flow, search debounce and detail navigation, through the real WatchlistStore on a temp file. */
@OptIn(ExperimentalCoroutinesApi::class)
class MarketsPresenterTest {
    private class RecordingRefresher : Refresher {
        val all = mutableListOf<String>()
        override fun refreshAll() { all += "ALL" }
        override fun refresh(symbol: String) { all += symbol }
    }

    private class Rig(scope: TestScope, file: File = File(tempDir(), "watchlist.json")) {
        val clock = VirtualClock(scope.testScheduler)
        val store = WatchlistStore(file, clock)
        val quotes = FakeQuotesSource(clock)
        val provider = FakeProvider(clock)
        val refresher = RecordingRefresher()
        val data = MarketsData(store, quotes, provider)
        val p = MarketsPresenter(scope.backgroundScope, data, refresher, clock, Dispatchers.Unconfined, searchDebounceMs = 400)
        val symbols get() = store.items.value.map { it.symbol }
        val ui get() = p.state.value
    }

    private val shop = Instrument("SHOP.TO", "Shopify Inc.", "CAD", "Toronto", "EQUITY")
    private val aapl = Instrument("AAPL", "Apple Inc.", "USD", "NasdaqGS", "EQUITY")

    // ---- list ------------------------------------------------------------------------------------------------

    @Test fun startsWithTheTwoDefaultsLoading() = runTest {
        val r = Rig(this)
        runCurrent()
        assertEquals(listOf("XEQT.TO", "MSFT"), r.ui.quotes.map { it.symbol })
        assertEquals(listOf("TSX", "NASDAQ"), r.ui.quotes.map { it.exchange })
        assertTrue(r.ui.quotes.all { it.status == RowStatus.Loading })
        assertFalse(r.ui.editMode)
        assertNull(r.ui.detail)
        assertNull(r.ui.add)
    }

    @Test fun rowsFollowQuoteStatesLive() = runTest {
        val r = Rig(this)
        r.quotes.put("MSFT", freshState(quoteOf("MSFT", 410.5, currency = "USD", prevClose = 400.0)))
        runCurrent()
        val row = r.ui.quotes.first { it.symbol == "MSFT" }
        assertEquals(RowStatus.Fresh, row.status)
        assertEquals(410.5, row.price!!, 0.0)
        r.quotes.put("MSFT", freshState(quoteOf("MSFT", 420.0, currency = "USD", prevClose = 400.0)))
        runCurrent()
        assertEquals(420.0, r.ui.quotes.first { it.symbol == "MSFT" }.price!!, 0.0)
    }

    @Test fun refreshAndRetryGoToTheRefresher() = runTest {
        val r = Rig(this)
        r.p.onRefresh()
        r.p.onRetry("MSFT")
        assertEquals(listOf("ALL", "MSFT"), r.refresher.all)
    }

    // ---- editor: move, rename, remove ------------------------------------------------------------------------

    @Test fun moveSwapsNeighboursAndIgnoresTheEnds() = runTest {
        val r = Rig(this)
        runCurrent()
        r.p.onMove("MSFT", -1)
        runCurrent()
        assertEquals(listOf("MSFT", "XEQT.TO"), r.symbols)
        r.p.onMove("MSFT", -1) // already first
        r.p.onMove("XEQT.TO", 1) // already last
        r.p.onMove("NOPE", 1)
        runCurrent()
        assertEquals(listOf("MSFT", "XEQT.TO"), r.symbols)
        assertEquals(listOf("MSFT", "XEQT.TO"), r.ui.quotes.map { it.symbol })
    }

    @Test fun moveIsPersisted() = runTest {
        val f = File(tempDir(), "watchlist.json")
        val r = Rig(this, f)
        r.p.onMove("XEQT.TO", 1)
        runCurrent()
        assertEquals(listOf("MSFT", "XEQT.TO"), WatchlistStore(f).items.value.map { it.symbol })
    }

    @Test fun renameSetsAndClearsTheDisplayName() = runTest {
        val r = Rig(this)
        runCurrent()
        r.p.onAskRename("XEQT.TO")
        runCurrent()
        assertEquals("iShares Core Equity ETF Portfolio", r.ui.rename!!.current)
        r.p.onRenameChange("My ETF")
        r.p.onRenameConfirm()
        runCurrent()
        assertNull(r.ui.rename)
        assertEquals("My ETF", r.ui.quotes.first { it.symbol == "XEQT.TO" }.name)
        assertEquals("My ETF", r.store.items.value.first { it.symbol == "XEQT.TO" }.displayName)
        // blank clears the override
        r.p.onAskRename("XEQT.TO")
        r.p.onRenameChange("   ")
        r.p.onRenameConfirm()
        runCurrent()
        assertNull(r.store.items.value.first { it.symbol == "XEQT.TO" }.displayName)
        // typing the original name back also clears it
        r.p.onAskRename("MSFT")
        r.p.onRenameChange("Microsoft Corporation")
        r.p.onRenameConfirm()
        runCurrent()
        assertNull(r.store.items.value.first { it.symbol == "MSFT" }.displayName)
    }

    @Test fun renameCanBeCancelled() = runTest {
        val r = Rig(this)
        runCurrent()
        r.p.onAskRename("MSFT")
        r.p.onRenameChange("Nope")
        r.p.onRenameCancel()
        runCurrent()
        assertNull(r.ui.rename)
        assertNull(r.store.items.value.first { it.symbol == "MSFT" }.displayName)
    }

    @Test fun removeAsksFirstThenRemovesAndForgetsTheData() = runTest {
        val r = Rig(this)
        runCurrent()
        r.p.onAskRemove("MSFT")
        runCurrent()
        assertEquals("MSFT", r.ui.confirmRemove!!.symbol)
        assertEquals(listOf("XEQT.TO", "MSFT"), r.symbols) // nothing happened yet
        r.p.onCancelRemove()
        runCurrent()
        assertEquals(listOf("XEQT.TO", "MSFT"), r.symbols)
        r.p.onAskRemove("MSFT")
        r.p.onConfirmRemove()
        runCurrent()
        assertEquals(listOf("XEQT.TO"), r.symbols)
        assertEquals(listOf("MSFT"), r.quotes.forgotten)
        assertNull(r.ui.confirmRemove)
    }

    @Test fun removingTheLastItemIsAllowedAndGivesAnEmptyList() = runTest {
        val r = Rig(this)
        runCurrent()
        for (s in listOf("XEQT.TO", "MSFT")) { r.p.onAskRemove(s); r.p.onConfirmRemove(); runCurrent() }
        assertTrue(r.ui.quotes.isEmpty())
        assertTrue(r.symbols.isEmpty())
        assertEquals(com.marketsboard.ui.markets.LiveKind.None, r.ui.header.kind)
    }

    @Test fun toggleEditAndBackWalkOutOfModes() = runTest {
        val r = Rig(this)
        runCurrent()
        r.p.onToggleEdit()
        runCurrent()
        assertTrue(r.ui.editMode)
        assertTrue(r.ui.canGoBack)
        r.p.onOpen("MSFT")
        r.p.onAskRemove("MSFT")
        runCurrent()
        // innermost first: dialog, then detail, then edit mode
        r.p.onBack(); runCurrent(); assertNull(r.ui.confirmRemove); assertNotNull(r.ui.detail)
        r.p.onBack(); runCurrent(); assertNull(r.ui.detail); assertTrue(r.ui.editMode)
        r.p.onBack(); runCurrent(); assertFalse(r.ui.editMode)
        assertFalse(r.ui.canGoBack)
    }

    // ---- add: search ------------------------------------------------------------------------------------------

    @Test fun searchIsDebouncedAndOnlyTheLastTermIsSent() = runTest {
        val r = Rig(this)
        r.provider.searchFn = { FetchResult.Ok(listOf(shop)) }
        r.p.onAddOpen()
        runCurrent()
        for (t in listOf("s", "sh", "sho", "shop")) {
            r.p.onQueryChange(t)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
        }
        assertTrue("no search before the pause", r.provider.searches.isEmpty())
        advanceTimeBy(299) // 399 ms after the last keystroke
        runCurrent()
        assertTrue(r.provider.searches.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("shop"), r.provider.searches.map { it.second })
        assertEquals(listOf("SHOP.TO"), r.ui.add!!.results.map { it.symbol })
        val row = r.ui.add!!.results.single()
        assertEquals("TSX", row.exchange) // shortened
        assertEquals("Stock", row.type)
        assertFalse(row.added)
    }

    @Test fun aSlowSearchIsCancelledWhenTheTextChanges() = runTest {
        val r = Rig(this)
        r.provider.searchDelayMs = 2_000
        r.provider.searchFn = { q -> FetchResult.Ok(if (q == "app") listOf(aapl) else listOf(shop)) }
        r.p.onAddOpen()
        r.p.onQueryChange("shop")
        runCurrent()
        advanceTimeBy(500); runCurrent() // search for "shop" is now in flight
        r.p.onQueryChange("app")
        runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf("shop", "app"), r.provider.searches.map { it.second })
        assertEquals(listOf("AAPL"), r.ui.add!!.results.map { it.symbol }) // the late "shop" answer never shows
        assertFalse(r.ui.add!!.searching)
    }

    @Test fun clearingTheFieldDropsResultsAndShowsSuggestions() = runTest {
        val r = Rig(this)
        r.provider.searchFn = { FetchResult.Ok(listOf(shop)) }
        r.p.onAddOpen()
        r.p.onQueryChange("shop")
        advanceTimeBy(500); runCurrent()
        assertEquals(1, r.ui.add!!.results.size)
        r.p.onQueryChange("")
        runCurrent()
        assertTrue(r.ui.add!!.results.isEmpty())
        assertEquals(16, r.ui.add!!.suggestions.size)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, r.provider.searches.size) // an empty field is never searched
    }

    @Test fun resultsMarkSymbolsAlreadyInTheList() = runTest {
        val r = Rig(this)
        r.provider.searchFn = { FetchResult.Ok(listOf(Instrument("MSFT", "Microsoft", "USD", "NasdaqGS", "EQUITY"), shop)) }
        r.p.onAddOpen()
        r.p.onQueryChange("m")
        advanceTimeBy(500); runCurrent()
        assertEquals(listOf(true, false), r.ui.add!!.results.map { it.added })
    }

    @Test fun suggestionsNeverIncludeTheDefaultsAndMarkTheOnesInTheList() = runTest {
        val r = Rig(this)
        r.p.onAddOpen()
        runCurrent()
        val syms = r.ui.add!!.suggestions.map { it.symbol }
        assertEquals(
            listOf("^GSPTSE", "^GSPC", "^IXIC", "USDCAD=X", "CL=F", "GC=F", "BTC-CAD", "ETH-CAD", "VFV.TO", "XIU.TO", "SHOP.TO", "RY.TO", "TD.TO", "ENB.TO", "AAPL", "NVDA"),
            syms,
        )
        assertTrue(r.ui.add!!.suggestions.none { it.added })
        r.store.addUnverified("AAPL")
        runCurrent()
        assertTrue(r.ui.add!!.suggestions.first { it.symbol == "AAPL" }.added)
    }

    @Test fun searchFailuresAreExplainedInPlainLanguage() = runTest {
        val r = Rig(this)
        r.p.onAddOpen()
        for ((kind, text) in listOf(
            ErrorKind.Network to "offline",
            ErrorKind.RateLimited(60_000) to "busy",
            ErrorKind.Http(500) to "unavailable",
        )) {
            r.provider.searchFn = { err(kind) }
            r.p.onQueryChange("x$text")
            advanceTimeBy(500); runCurrent()
            val note = r.ui.add!!.searchNote
            assertTrue("$kind: $note", note.contains(text))
            assertTrue(r.ui.add!!.results.isEmpty())
        }
    }

    @Test fun noMatchesPointsToDone() = runTest {
        val r = Rig(this)
        r.p.onAddOpen()
        r.p.onQueryChange("qqq")
        advanceTimeBy(500); runCurrent()
        assertTrue(r.ui.add!!.searchNote.contains("No matches"))
    }

    // ---- add: outcomes ----------------------------------------------------------------------------------------

    @Test fun pickingAResultValidatesAndAddsKeepingTheSheetOpen() = runTest {
        val r = Rig(this)
        r.provider.quoteFn = { s, rg -> FetchResult.Ok(quoteOf(s, 150.0, range = rg, currency = "CAD", name = "Shopify Inc.", exchange = "Toronto")) }
        r.provider.searchFn = { FetchResult.Ok(listOf(shop)) }
        r.p.onAddOpen()
        r.p.onQueryChange("shop")
        advanceTimeBy(500); runCurrent()
        r.p.onPickResult("SHOP.TO")
        runCurrent()
        assertEquals(listOf("XEQT.TO", "MSFT", "SHOP.TO"), r.symbols)
        val added = r.store.items.value.last()
        assertEquals("CAD", added.currency)
        assertEquals("Shopify Inc.", added.name)
        val add = r.ui.add!!
        assertEquals("Added Shopify Inc.", add.message!!.text)
        assertFalse(add.message!!.warn)
        assertEquals("", add.query)
        assertFalse(add.busy)
    }

    @Test fun duplicatesAreRefusedPolitely() = runTest {
        val r = Rig(this)
        r.p.onAddOpen()
        r.p.onPickResult("MSFT")
        runCurrent()
        assertEquals(listOf("XEQT.TO", "MSFT"), r.symbols)
        assertEquals("MSFT is already in your list", r.ui.add!!.message!!.text)
        assertTrue(r.provider.quotes.isEmpty()) // no fetch needed
    }

    @Test fun invalidSymbolIsExplained() = runTest {
        val r = Rig(this)
        r.p.onAddOpen()
        r.p.onQueryChange("bad symbol!")
        r.p.onSubmitTyped()
        runCurrent()
        assertEquals(listOf("XEQT.TO", "MSFT"), r.symbols)
        assertTrue(r.ui.add!!.message!!.text.contains("not a valid symbol"))
        assertTrue(r.ui.add!!.message!!.warn)
        assertNull(r.ui.add!!.message!!.addAnywaySymbol)
    }

    @Test fun notFoundIsExplained() = runTest {
        val r = Rig(this)
        r.provider.quoteFn = { _, _ -> err(ErrorKind.NotFound) }
        r.p.onAddOpen()
        r.p.onQueryChange("ZZZZ")
        r.p.onSubmitTyped()
        runCurrent()
        assertTrue(r.ui.add!!.message!!.text.contains("not found"))
        assertEquals(2, r.symbols.size)
        assertNull(r.ui.add!!.message!!.addAnywaySymbol)
    }

    @Test fun offlineOffersAddAnywayAndWarnsThatPricesComeLater() = runTest {
        val r = Rig(this)
        r.provider.quoteFn = { _, _ -> err(ErrorKind.Network) }
        r.p.onAddOpen()
        r.p.onQueryChange("shop.to")
        r.p.onSubmitTyped()
        runCurrent()
        val m = r.ui.add!!.message!!
        assertEquals("SHOP.TO", m.addAnywaySymbol)
        assertTrue(m.warn)
        assertTrue(m.text.contains("once you are online"))
        assertEquals(2, r.symbols.size) // nothing added yet
        r.p.onAddAnyway()
        runCurrent()
        assertEquals(listOf("XEQT.TO", "MSFT", "SHOP.TO"), r.symbols)
        val item = r.store.items.value.last()
        assertEquals("", item.currency) // unknown until the first quote
        assertNull(r.ui.add) // sheet closes
        // the new row exists and shows as loading until prices arrive
        assertEquals(RowStatus.Loading, r.ui.quotes.last().status)
    }

    @Test fun rateLimitedVerificationAlsoOffersAddAnyway() = runTest {
        val r = Rig(this)
        r.provider.quoteFn = { _, _ -> err(ErrorKind.RateLimited(60_000)) }
        r.p.onAddOpen()
        r.p.onQueryChange("AAPL")
        r.p.onSubmitTyped()
        runCurrent()
        assertTrue(r.ui.add!!.message!!.text.contains("busy"))
        assertEquals("AAPL", r.ui.add!!.message!!.addAnywaySymbol)
    }

    @Test fun doneWithATypedSymbolAndNoResultsTriesItDirectlyAndCloses() = runTest {
        val r = Rig(this)
        r.p.onAddOpen()
        r.p.onQueryChange("nvda")
        r.p.onSubmitTyped() // before any search answered
        runCurrent()
        assertEquals(listOf("XEQT.TO", "MSFT", "NVDA"), r.symbols)
        assertNull(r.ui.add)
        assertTrue(r.provider.searches.isEmpty() || r.ui.add == null)
    }

    @Test fun doneWithAnEmptyFieldJustCloses() = runTest {
        val r = Rig(this)
        r.p.onAddOpen()
        runCurrent()
        r.p.onSubmitTyped()
        runCurrent()
        assertNull(r.ui.add)
        assertEquals(2, r.symbols.size)
    }

    @Test fun addingBeyondTheLimitIsExplained() = runTest {
        val r = Rig(this)
        for (i in 1..28) r.store.addUnverified("S$i")
        r.p.onAddOpen()
        r.p.onQueryChange("OVER")
        r.p.onSubmitTyped()
        runCurrent()
        assertEquals(30, r.symbols.size)
        assertTrue(r.ui.add!!.message!!.text.contains("list is full"))
    }

    @Test fun readOnlyListRefusesEditsAndShowsANotice() = runTest {
        val f = File(tempDir(), "watchlist.json")
        f.writeText("""{"version": 99, "items": [{"symbol":"MSFT","name":"Microsoft","currency":"USD","exchange":"NASDAQ"}]}""")
        val r = Rig(this, f)
        runCurrent()
        assertTrue(r.ui.notice.contains("newer version"))
        r.p.onAddOpen()
        r.p.onQueryChange("AAPL")
        r.p.onSubmitTyped()
        runCurrent()
        assertTrue(r.ui.add!!.message!!.text.contains("newer version"))
        assertEquals(listOf("MSFT"), r.symbols)
    }

    // ---- replace ----------------------------------------------------------------------------------------------

    @Test fun replaceKeepsThePositionAndForgetsTheOldSymbol() = runTest {
        val r = Rig(this)
        r.p.onReplaceOpen("XEQT.TO")
        runCurrent()
        assertEquals("XEQT.TO", r.ui.add!!.replacing)
        assertTrue(r.ui.add!!.replacingName.contains("iShares"))
        r.p.onPickResult("AAPL")
        runCurrent()
        assertEquals(listOf("AAPL", "MSFT"), r.symbols)
        assertEquals(listOf("XEQT.TO"), r.quotes.forgotten)
        assertNull(r.ui.add)
    }

    @Test fun replaceWithAnExistingSymbolIsADuplicate() = runTest {
        val r = Rig(this)
        r.p.onReplaceOpen("XEQT.TO")
        r.p.onPickResult("MSFT")
        runCurrent()
        assertEquals(listOf("XEQT.TO", "MSFT"), r.symbols)
        assertEquals("MSFT is already in your list", r.ui.add!!.message!!.text)
        assertTrue(r.quotes.forgotten.isEmpty())
    }

    @Test fun replaceOfferedAddAnywayWhenOffline() = runTest {
        val r = Rig(this)
        r.provider.quoteFn = { _, _ -> err(ErrorKind.Timeout) }
        r.p.onReplaceOpen("MSFT")
        r.p.onQueryChange("AAPL")
        r.p.onSubmitTyped()
        runCurrent()
        assertEquals("AAPL", r.ui.add!!.message!!.addAnywaySymbol)
        r.p.onAddAnyway()
        runCurrent()
        assertEquals(listOf("XEQT.TO", "AAPL"), r.symbols)
        assertEquals(listOf("MSFT"), r.quotes.forgotten)
    }

    @Test fun cancelClosesTheSheetWithoutChanges() = runTest {
        val r = Rig(this)
        r.p.onAddOpen()
        r.p.onQueryChange("abc")
        r.p.onAddClose()
        runCurrent()
        assertNull(r.ui.add)
        assertEquals(2, r.symbols.size)
        advanceTimeBy(1_000); runCurrent()
        assertTrue("a closed sheet must not search", r.provider.searches.isEmpty())
    }

    // ---- detail -----------------------------------------------------------------------------------------------

    @Test fun openingTheDetailRefreshesAndSelectingARangeFetchesThatRange() = runTest {
        val r = Rig(this)
        r.quotes.put("MSFT", freshState(quoteOf("MSFT", 410.0, currency = "USD")))
        runCurrent()
        r.p.onOpen("MSFT")
        runCurrent()
        assertEquals("MSFT", r.ui.detail!!.symbol)
        assertEquals(ChartRange.D1, r.ui.detail!!.range)
        assertEquals(ChartRange.D1, r.quotes.calls.last().range)
        r.p.onSelectRange(ChartRange.Y1)
        runCurrent()
        assertEquals(ChartRange.Y1, r.ui.detail!!.range)
        assertEquals(ChartRange.Y1, r.quotes.calls.last().range)
        assertEquals(listOf("MSFT"), r.quotes.calls.last().symbols)
        assertEquals(ChartLoad.Loading, r.ui.detail!!.chart) // nothing for 1Y yet
        r.quotes.put("MSFT", freshState(quoteOf("MSFT", 410.0, currency = "USD", range = ChartRange.Y1)), ChartRange.Y1)
        runCurrent()
        assertEquals(ChartLoad.Ready, r.ui.detail!!.chart)
    }

    @Test fun retryRangeForcesAFetch() = runTest {
        val r = Rig(this)
        runCurrent()
        r.p.onOpen("MSFT")
        r.p.onSelectRange(ChartRange.M3)
        runCurrent()
        r.p.onRetryRange()
        runCurrent()
        assertTrue(r.quotes.calls.last().force)
        assertEquals(ChartRange.M3, r.quotes.calls.last().range)
    }

    @Test fun detailClosesWhenItsInstrumentLeavesTheList() = runTest {
        val r = Rig(this)
        runCurrent()
        r.p.onOpen("MSFT")
        runCurrent()
        assertNotNull(r.ui.detail)
        r.p.onAskRemove("MSFT")
        r.p.onConfirmRemove()
        runCurrent()
        assertNull(r.ui.detail)
        r.p.onOpen("NOPE") // unknown symbol never opens
        runCurrent()
        assertNull(r.ui.detail)
    }

    @Test fun replaceFromTheDetailClosesTheDetailOnSuccess() = runTest {
        val r = Rig(this)
        runCurrent()
        r.p.onOpen("MSFT")
        r.p.onReplaceOpen("MSFT")
        r.p.onPickResult("AAPL")
        runCurrent()
        assertNull(r.ui.detail)
        assertNull(r.ui.add)
        assertEquals(listOf("XEQT.TO", "AAPL"), r.symbols)
    }
}
