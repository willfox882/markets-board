package com.marketsboard.ui

import com.marketsboard.data.FakeClock
import com.marketsboard.data.news.DefaultFeeds
import com.marketsboard.data.news.FeedKind
import com.marketsboard.data.news.FeedStore
import com.marketsboard.data.store.WatchlistStore
import com.marketsboard.data.tempDir
import com.marketsboard.ui.tools.DiagnosticsPresenter
import com.marketsboard.ui.tools.DiagnosticsSources
import com.marketsboard.ui.tools.FeedsPresenter
import com.marketsboard.ui.tools.ToolsPage
import com.marketsboard.ui.tools.ToolsPresenter
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The feed editor and the Settings sub-page navigation. M5 actions have no driving restriction, so none of this involves the gate. */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedsPresenterTest {
    private class Rig(private val scope: TestScope, val dir: File = tempDir()) {
        val clock = FakeClock()
        val store = FeedStore(File(dir, "feeds.json"), clock)
        val watch = MutableStateFlow(WatchlistStore.DEFAULTS)
        val feeds = FeedsPresenter(scope.backgroundScope, store, watch, UnconfinedTestDispatcher(scope.testScheduler))
        /** The state after the dispatcher has caught up with whatever the test just did. */
        val state get() = run { scope.runCurrent(); feeds.state.value }
    }

    private fun TestScope.rig() = Rig(this).also { runCurrent() }

    @Test fun listsTheDefaultFeedsWithHostsAndTheSymbolFeedCount() = runTest {
        val r = rig()
        assertEquals(DefaultFeeds.ALL.map { it.id }, r.state.rows.map { it.id })
        assertEquals("cbc.ca", r.state.rows[0].host)
        assertTrue(r.state.rows.all { it.enabled })
        assertFalse(r.state.rows[0].canMoveUp)
        assertTrue(r.state.rows[0].canMoveDown)
        assertFalse(r.state.rows.last().canMoveDown)
        assertTrue(r.state.symbolFeeds)
        assertEquals(2, r.state.symbolFeedCount) // XEQT.TO and MSFT
        assertEquals("", r.state.notice)
    }

    @Test fun toggleDisablesAndPersists() = runTest {
        val r = rig()
        r.feeds.onToggle("yahoo-top", false)
        runCurrent()
        assertFalse(r.state.rows.first { it.id == "yahoo-top" }.enabled)
        assertFalse(FeedStore(File(r.dir, "feeds.json"), r.clock).feeds.value.first { it.id == "yahoo-top" }.enabled)
        r.feeds.onToggle("yahoo-top", true)
        runCurrent()
        assertTrue(r.state.rows.first { it.id == "yahoo-top" }.enabled)
    }

    @Test fun moveUpAndDownKeepsOrderAndTheEndsCannotMove() = runTest {
        val r = rig()
        r.feeds.onMove("financial-post", -1)
        runCurrent()
        assertEquals(listOf("cbc-business", "financial-post", "globe-business", "yahoo-top", "marketwatch-top"), r.state.rows.map { it.id })
        r.feeds.onMove("cbc-business", -1) // already first: nothing
        r.feeds.onMove("marketwatch-top", 1) // already last: nothing
        runCurrent()
        assertEquals("cbc-business", r.state.rows.first().id)
        assertEquals("marketwatch-top", r.state.rows.last().id)
    }

    @Test fun rapidTapsKeepTheirOrder() = runTest {
        val r = rig()
        repeat(3) { r.feeds.onMove("marketwatch-top", -1) }
        runCurrent()
        assertEquals(listOf("cbc-business", "marketwatch-top", "globe-business", "financial-post", "yahoo-top"), r.state.rows.map { it.id })
    }

    @Test fun renameDialogCleansTheNameAndEmptyKeepsTheOldOne() = runTest {
        val r = rig()
        r.feeds.onAskRename("cbc-business")
        assertEquals("CBC Business", r.state.rename?.current)
        r.feeds.onRenameChange("  CBC   News ")
        r.feeds.onRenameConfirm()
        runCurrent()
        assertNull(r.state.rename)
        assertEquals("CBC News", r.state.rows[0].name)
        r.feeds.onAskRename("cbc-business")
        r.feeds.onRenameChange("   ")
        r.feeds.onRenameConfirm()
        runCurrent()
        assertEquals("CBC News", r.state.rows[0].name)
    }

    @Test fun removeAsksFirstAndCancelKeepsTheFeed() = runTest {
        val r = rig()
        r.feeds.onAskRemove("yahoo-top")
        assertEquals("Yahoo Finance", r.state.confirmRemove?.name)
        r.feeds.onCancelRemove()
        runCurrent()
        assertEquals(5, r.state.rows.size)
        r.feeds.onAskRemove("yahoo-top")
        r.feeds.onConfirmRemove()
        runCurrent()
        assertEquals(listOf("cbc-business", "globe-business", "financial-post", "marketwatch-top"), r.state.rows.map { it.id })
        assertNull(r.state.confirmRemove)
    }

    @Test fun addValidatesHttpsAndShowsPlainReasons() = runTest {
        val r = rig()
        r.feeds.onAddOpen()
        r.feeds.onAddConfirm()
        assertEquals("Type the feed address first", r.state.add?.message)
        r.feeds.onAddUrlChange("http://example.com/feed.xml")
        r.feeds.onAddConfirm()
        runCurrent()
        assertEquals("Feeds must use an https address", r.state.add?.message)
        assertEquals(5, r.state.rows.size)
        r.feeds.onAddUrlChange("not a url")
        r.feeds.onAddConfirm()
        runCurrent()
        assertEquals("Use a full https address", r.state.add?.message)
        r.feeds.onAddUrlChange("https://financialpost.com/feed")
        r.feeds.onAddConfirm()
        runCurrent()
        assertEquals("Already in your list as Financial Post", r.state.add?.message)
        // typing again clears the message; a bare host path is taken as https
        r.feeds.onAddUrlChange("example.com/feed.xml")
        assertEquals("", r.state.add?.message)
        r.feeds.onAddNameChange("  My   blog ")
        r.feeds.onAddConfirm()
        runCurrent()
        assertNull(r.state.add)
        assertEquals("My blog", r.state.rows.last().name)
        assertEquals("example.com", r.state.rows.last().host)
        assertTrue(FeedStore(File(r.dir, "feeds.json"), r.clock).feeds.value.last().url.startsWith("https://example.com/"))
    }

    @Test fun addWithoutANameUsesTheHostAndTheListHasALimit() = runTest {
        val r = rig()
        r.feeds.onAddOpen()
        r.feeds.onAddUrlChange("https://news.example.org/rss")
        r.feeds.onAddConfirm()
        runCurrent()
        assertEquals("news.example.org", r.state.rows.last().name)
        for (i in 0 until FeedStore.MAX_FEEDS) r.store.add("f$i", "https://e$i.example.com/rss")
        runCurrent()
        assertTrue(r.state.atLimit)
        r.feeds.onAddOpen()
        r.feeds.onAddUrlChange("https://one-more.example.com/rss")
        r.feeds.onAddConfirm()
        runCurrent()
        assertEquals("The list is full (20 feeds). Remove one first", r.state.add?.message)
    }

    @Test fun resetAsksFirstThenRestoresTheDefaults() = runTest {
        val r = rig()
        r.feeds.onToggle("cbc-business", false)
        r.feeds.onSymbolFeeds(false)
        r.feeds.onAskRemove("yahoo-top")
        r.feeds.onConfirmRemove()
        runCurrent()
        r.feeds.onAskReset()
        assertTrue(r.state.confirmReset)
        r.feeds.onCancelReset()
        runCurrent()
        assertEquals(4, r.state.rows.size)
        r.feeds.onAskReset()
        r.feeds.onConfirmReset()
        runCurrent()
        assertEquals(DefaultFeeds.ALL, r.state.rows.map { r.store.feeds.value.first { f -> f.id == it.id } })
        assertTrue(r.state.rows.all { it.enabled })
        assertTrue(r.state.symbolFeeds)
        assertFalse(r.state.confirmReset)
    }

    @Test fun symbolFeedSwitchPersistsAndTheCountFollowsTheWatchlist() = runTest {
        val r = rig()
        r.feeds.onSymbolFeeds(false)
        runCurrent()
        assertFalse(r.state.symbolFeeds)
        assertFalse(FeedStore(File(r.dir, "feeds.json"), r.clock).symbolFeeds.value)
        r.feeds.onSymbolFeeds(true)
        runCurrent()
        r.watch.value = WatchlistStore.DEFAULTS.take(1)
        runCurrent()
        assertEquals(1, r.state.symbolFeedCount)
    }

    @Test fun aSymbolFeedRowShowsItsSymbol() = runTest {
        val r = rig()
        r.store.add("XEQT news", "https://example.com/x.xml", FeedKind.Symbol, "XEQT.TO")
        runCurrent()
        val row = r.state.rows.last()
        assertEquals(FeedKind.Symbol, row.kind)
        assertEquals("XEQT.TO", row.symbol)
    }

    @Test fun aFileFromANewerVersionIsShownButNeverChanged() = runTest {
        val dir = tempDir()
        val f = File(dir, "feeds.json")
        f.writeText("""{"version":99,"symbolFeeds":true,"feeds":[{"id":"x","name":"Future feed","url":"https://example.com/f.xml","enabled":true,"kind":"general"}]}""")
        val before = f.readText()
        val r = Rig(this, dir)
        runCurrent()
        assertTrue(r.state.readOnly)
        assertTrue(r.state.notice.contains("newer version"))
        assertEquals("Future feed", r.state.rows.single().name)
        r.feeds.onToggle("x", false)
        r.feeds.onAddOpen()
        r.feeds.onAddUrlChange("https://example.com/other.xml")
        r.feeds.onAddConfirm()
        runCurrent()
        assertTrue(r.state.rows.single().enabled)
        assertTrue(r.state.add?.message?.contains("newer version") == true)
        assertEquals(before, f.readText())
    }

    @Test fun selectingARowOpensItsActionsOneAtATime() = runTest {
        val r = rig()
        r.feeds.onSelect("globe-business")
        assertEquals(listOf("globe-business"), r.state.rows.filter { it.selected }.map { it.id })
        r.feeds.onSelect("yahoo-top")
        assertEquals(listOf("yahoo-top"), r.state.rows.filter { it.selected }.map { it.id })
        r.feeds.onSelect("yahoo-top")
        assertTrue(r.state.rows.none { it.selected })
    }

    @Test fun withSchemeOnlyAddsHttpsWhenThereIsNoScheme() {
        assertEquals("https://a.com/x", FeedsPresenter.withScheme(" a.com/x "))
        assertEquals("http://a.com/x", FeedsPresenter.withScheme("http://a.com/x"))
        assertEquals("https://a.com/x", FeedsPresenter.withScheme("https://a.com/x"))
    }

    // ---- navigation (ToolsPresenter) -------------------------------------------------------------------------------------

    private fun TestScope.tools(r: Rig): ToolsPresenter {
        val sources = DiagnosticsSources(
            news = MutableStateFlow(com.marketsboard.data.news.NewsState()), feeds = r.store.feeds,
            quotes = com.marketsboard.FakeQuotesSource(r.clock), watch = r.watch, fetches = MutableStateFlow(emptyList()),
            signal = MutableStateFlow(com.marketsboard.refresh.NetworkStatus.Unknown), offline = MutableStateFlow(false),
            driving = MutableStateFlow(com.marketsboard.core.DrivingStatus()), locationGranted = MutableStateFlow(true),
        )
        return ToolsPresenter(backgroundScope, r.feeds, DiagnosticsPresenter(sources, r.clock, tick = kotlinx.coroutines.flow.emptyFlow()))
    }

    @Test fun backClosesTheSheetThenTheDialogThenThePage() = runTest {
        val r = rig()
        val t = tools(r)
        runCurrent()
        assertEquals(ToolsPage.Main, t.state.value.page)
        t.onOpenFeeds()
        t.onAddOpen()
        t.onToolsBack()
        runCurrent()
        assertNull(t.state.value.feeds.add)
        assertEquals(ToolsPage.Feeds, t.state.value.page)
        t.onAskRemove("cbc-business")
        t.onToolsBack()
        runCurrent()
        assertNull(t.state.value.feeds.confirmRemove)
        assertEquals(ToolsPage.Feeds, t.state.value.page)
        t.onSelect("cbc-business")
        t.onToolsBack()
        runCurrent()
        assertEquals(ToolsPage.Main, t.state.value.page)
        assertTrue(t.state.value.feeds.rows.none { it.selected }) // leaving drops the open row
    }

    @Test fun diagnosticsRunsOnlyWhileItsPageIsOpenAndBackLeavesIt() = runTest {
        val r = rig()
        val t = tools(r)
        runCurrent()
        assertTrue(t.state.value.diagnostics.sections.isEmpty())
        t.onOpenDiagnostics()
        runCurrent()
        assertEquals(ToolsPage.Diagnostics, t.state.value.page)
        assertEquals(listOf("Connection", "Prices", "Watchlist cache", "News feeds", "Speed source"), t.state.value.diagnostics.sections.map { it.title })
        t.onToolsBack()
        runCurrent()
        assertEquals(ToolsPage.Main, t.state.value.page)
        assertTrue(t.state.value.diagnostics.sections.isEmpty())
    }

    @Test fun editsWorkWhileTheTruckMovesBecauseNothingHereIsGated() = runTest {
        // The owner decided that M5 actions have no driving restriction: the presenter does not even know about the gate.
        val r = rig()
        val t = tools(r)
        t.onOpenFeeds()
        t.onToggle("cbc-business", false)
        t.onSymbolFeeds(false)
        runCurrent()
        assertFalse(t.state.value.feeds.rows[0].enabled)
        assertFalse(t.state.value.feeds.symbolFeeds)
    }
}
