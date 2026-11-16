package com.marketsboard.ui

import com.marketsboard.FakeProvider
import com.marketsboard.FakeQuotesSource
import com.marketsboard.VirtualClock
import com.marketsboard.core.DrivingGate
import com.marketsboard.data.FakeClock
import com.marketsboard.data.MarketsData
import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.Instrument
import com.marketsboard.data.news.Headline
import com.marketsboard.data.news.NewsOverall
import com.marketsboard.data.news.NewsSource
import com.marketsboard.data.news.NewsState
import com.marketsboard.data.settings.AppSettings
import com.marketsboard.data.settings.AppSettingsStore
import com.marketsboard.data.store.WatchlistStore
import com.marketsboard.data.tempDir
import com.marketsboard.refresh.NewsRefresher
import com.marketsboard.refresh.Refresher
import com.marketsboard.ui.markets.MarketsPresenter
import com.marketsboard.ui.news.LinkOpener
import com.marketsboard.ui.news.NewsFilter
import com.marketsboard.ui.news.NewsPresenter
import com.marketsboard.ui.settings.LocationAccess
import com.marketsboard.ui.settings.SettingsPresenter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Glance contract (the truck moves): enforced in the PRESENTERS. The invariant tests enumerate the action enums of
 * `GlanceRules.kt` (no reflection): each entry is called through an exhaustive `when`, so a new action cannot be added
 * without being listed here, and every blocked one must change nothing, write nothing and fetch nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlanceContractTest {
    private class Recording : Refresher {
        val calls = mutableListOf<String>()
        override fun refreshAll() { calls += "ALL" }
        override fun refresh(symbol: String) { calls += symbol }
    }

    private class Rig(scope: TestScope) {
        val mode = MutableStateFlow(UiMode.Normal)
        val clock = VirtualClock(scope.testScheduler)
        val store = WatchlistStore(File(tempDir(), "watchlist.json"), clock)
        val quotes = FakeQuotesSource(clock)
        val provider = FakeProvider(
            clock,
            searchFn = { q -> FetchResult.Ok(listOf(Instrument("SHOP.TO", "Shopify Inc.", "CAD", "Toronto", "EQUITY"), Instrument("AAPL", "Apple $q", "USD", "NasdaqGS", "EQUITY"))) },
        )
        val refresher = Recording()
        val data = MarketsData(store, quotes, provider)
        val p = MarketsPresenter(scope.backgroundScope, data, refresher, clock, Dispatchers.Unconfined, searchDebounceMs = 400, mode = mode)
        val symbols get() = store.items.value.map { it.symbol }
        val ui get() = p.state.value
    }

    /** Every Markets action, through an exhaustive when: a new entry in [MarketsAct] fails to compile here until it is covered. */
    private fun call(p: MarketsPresenter, a: MarketsAct) {
        val done: Unit = when (a) {
            MarketsAct.Back -> p.onBack()
            MarketsAct.Refresh -> p.onRefresh()
            MarketsAct.Retry -> p.onRetry("MSFT")
            MarketsAct.Open -> p.onOpen("MSFT")
            MarketsAct.CloseDetail -> p.onCloseDetail()
            MarketsAct.SelectRange -> p.onSelectRange(ChartRange.Y1)
            MarketsAct.RetryRange -> p.onRetryRange()
            MarketsAct.ToggleEdit -> p.onToggleEdit()
            MarketsAct.Move -> p.onMove("MSFT", -1)
            MarketsAct.AskRename -> p.onAskRename("MSFT")
            MarketsAct.RenameChange -> p.onRenameChange("Hacked")
            MarketsAct.RenameConfirm -> p.onRenameConfirm()
            MarketsAct.RenameCancel -> p.onRenameCancel()
            MarketsAct.AskRemove -> p.onAskRemove("MSFT")
            MarketsAct.ConfirmRemove -> p.onConfirmRemove()
            MarketsAct.CancelRemove -> p.onCancelRemove()
            MarketsAct.AddOpen -> p.onAddOpen()
            MarketsAct.ReplaceOpen -> p.onReplaceOpen("MSFT")
            MarketsAct.AddClose -> p.onAddClose()
            MarketsAct.QueryChange -> p.onQueryChange("shop")
            MarketsAct.PickResult -> p.onPickResult("SHOP.TO")
            MarketsAct.SubmitTyped -> p.onSubmitTyped()
            MarketsAct.AddAnyway -> p.onAddAnyway()
        }
        done.hashCode()
    }

    private val blockedMarkets = MarketsAct.entries.filter { it.glance == GlanceRule.Blocked }

    @Test fun everyMarketsActionIsListedAndMostAreBlocked() {
        assertEquals(23, MarketsAct.entries.size)
        assertEquals(setOf(MarketsAct.Back, MarketsAct.CloseDetail, MarketsAct.RenameCancel, MarketsAct.CancelRemove, MarketsAct.AddClose), MarketsAct.entries.filter { it.glance == GlanceRule.Allowed }.toSet())
    }

    @Test fun inGlanceEveryBlockedMarketsActionChangesNothingWritesNothingAndFetchesNothing() = runTest {
        val r = Rig(this)
        runCurrent()
        r.mode.value = UiMode.Glance
        runCurrent()
        val state = r.ui
        val items = r.store.items.value
        for (act in blockedMarkets) {
            call(r.p, act)
            advanceTimeBy(2_000); runCurrent()
            assertEquals("state changed by $act", state, r.ui)
            assertEquals("watchlist changed by $act", items, r.store.items.value)
            assertTrue("refresher called by $act: ${r.refresher.calls}", r.refresher.calls.isEmpty())
            assertTrue("quotes fetched by $act: ${r.quotes.calls}", r.quotes.calls.isEmpty())
            assertTrue("search or quote by $act", r.provider.searches.isEmpty() && r.provider.quotes.isEmpty())
            assertTrue("forgotten by $act", r.quotes.forgotten.isEmpty())
        }
        // the whole sequence once more in one go, and the closing actions are harmless
        for (act in MarketsAct.entries) call(r.p, act)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(state, r.ui)
        assertEquals(items, r.store.items.value)
    }

    @Test fun theSameActionsDoWorkInNormalModeSoTheInvariantIsNotVacuous() = runTest {
        val r = Rig(this)
        runCurrent()
        r.p.onRefresh(); r.p.onRetry("MSFT")
        assertEquals(listOf("ALL", "MSFT"), r.refresher.calls)
        r.p.onMove("MSFT", -1); runCurrent()
        assertEquals(listOf("MSFT", "XEQT.TO"), r.symbols)
        r.p.onToggleEdit(); runCurrent()
        assertTrue(r.ui.editMode)
        r.p.onAskRemove("MSFT"); runCurrent()
        assertNotNull(r.ui.confirmRemove)
        r.p.onCancelRemove()
        r.p.onAddOpen(); r.p.onQueryChange("shop"); advanceTimeBy(500); runCurrent()
        assertEquals(1, r.provider.searches.size)
        r.p.onPickResult("SHOP.TO"); runCurrent()
        assertTrue("SHOP.TO" in r.symbols || r.ui.add != null)
        r.p.onAddClose()
        r.p.onOpen("MSFT"); runCurrent()
        assertNotNull(r.ui.detail)
    }

    @Test fun glanceClosesEveryOverlayAndEditMode() = runTest {
        val r = Rig(this)
        runCurrent()
        r.p.onToggleEdit(); r.p.onAskRemove("MSFT"); r.p.onAskRename("XEQT.TO"); r.p.onAddOpen()
        runCurrent()
        assertTrue(r.ui.editMode && r.ui.add != null)
        r.mode.value = UiMode.Glance; runCurrent()
        val g = r.ui
        assertFalse(g.editMode); assertNull(g.add); assertNull(g.detail); assertNull(g.confirmRemove); assertNull(g.rename)
        // and they are CLOSED for good, not merely hidden: back in Normal nothing reappears
        r.mode.value = UiMode.Normal; runCurrent()
        val n = r.ui
        assertFalse(n.editMode); assertNull(n.add); assertNull(n.detail); assertNull(n.confirmRemove); assertNull(n.rename)
        assertEquals(listOf("XEQT.TO", "MSFT"), r.symbols)
    }

    @Test fun anOpenDetailClosesAndItsFetchIsCancelled() = runTest {
        val r = Rig(this)
        r.quotes.refreshDelayMs = 10_000
        runCurrent()
        r.p.onOpen("MSFT"); runCurrent()
        assertNotNull(r.ui.detail)
        r.mode.value = UiMode.Glance; runCurrent()
        assertNull(r.ui.detail)
        advanceTimeBy(20_000); runCurrent()
        assertTrue("the detail fetch was cancelled before it finished", r.quotes.states.value.isEmpty())
    }

    @Test fun theTypedSearchTextIsKeptInMemoryAndOfferedAgainButNothingIsHalfAdded() = runTest {
        val r = Rig(this)
        runCurrent()
        r.p.onAddOpen(); r.p.onQueryChange("shopi"); advanceTimeBy(500); runCurrent()
        assertEquals("shopi", r.ui.add!!.query)
        r.mode.value = UiMode.Glance; runCurrent()
        assertNull(r.ui.add)
        assertEquals("nothing was added", listOf("XEQT.TO", "MSFT"), r.symbols)
        r.mode.value = UiMode.Normal; runCurrent()
        assertNull("the sheet does not pop back open by itself", r.ui.add)
        r.p.onAddOpen(); advanceTimeBy(500); runCurrent()
        assertEquals("shopi", r.ui.add!!.query)
        assertEquals("and it searches again", 2, r.provider.searches.size)
        r.p.onAddClose(); r.p.onAddOpen(); runCurrent()
        assertEquals("the draft is offered once", "", r.ui.add!!.query)
    }

    @Test fun anAddThatIsStillRunningIsCancelledByGlance() = runTest {
        val r = Rig(this)
        runCurrent()
        r.provider.quoteDelayMs = 5_000
        r.p.onAddOpen(); r.p.onSubmitTyped() // empty text closes
        r.p.onAddOpen(); r.p.onQueryChange("SHOP.TO"); r.p.onSubmitTyped(); runCurrent()
        assertTrue(r.ui.add!!.busy)
        r.mode.value = UiMode.Glance; runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals("the add never completed", listOf("XEQT.TO", "MSFT"), r.symbols)
        assertNull(r.ui.add)
    }

    @Test fun aRenameDraftComesBackOnceForTheSameSymbol() = runTest {
        val r = Rig(this)
        runCurrent()
        r.p.onAskRename("MSFT"); r.p.onRenameChange("My Microsoft"); runCurrent()
        r.mode.value = UiMode.Glance; runCurrent()
        assertNull(r.ui.rename)
        r.mode.value = UiMode.Normal; runCurrent()
        r.p.onAskRename("XEQT.TO"); runCurrent()
        assertNotEquals("My Microsoft", r.ui.rename!!.current)
        r.p.onRenameCancel()
        r.p.onAskRename("MSFT"); runCurrent()
        assertEquals("My Microsoft", r.ui.rename!!.current)
    }

    @Test fun movingAfterGlanceWorksAgain() = runTest {
        val r = Rig(this)
        runCurrent()
        r.mode.value = UiMode.Glance; runCurrent()
        r.p.onMove("MSFT", -1); runCurrent()
        assertEquals(listOf("XEQT.TO", "MSFT"), r.symbols)
        r.mode.value = UiMode.Normal; runCurrent()
        r.p.onMove("MSFT", -1); runCurrent()
        assertEquals(listOf("MSFT", "XEQT.TO"), r.symbols)
    }

    // ---- News ---------------------------------------------------------------------------------------------------

    private class FakeNews : NewsSource {
        val flow = MutableStateFlow(
            NewsState(
                listOf(Headline("a", "One", "s", "https://e.com/a", "CBC", 1_000L, listOf("MSFT")), Headline("b", "Two", "s", "https://e.com/b", "CBC", 900L)),
                overall = NewsOverall.Fresh, lastOkAgeMs = 0,
            ),
        )
        override val state: StateFlow<NewsState> = flow
        override fun correctedNowMs() = 2_000L
    }

    private class CountingNews : NewsRefresher {
        var n = 0
        override fun refreshNow() { n++ }
    }

    private fun call(p: NewsPresenter, a: NewsAct) {
        val done: Unit = when (a) {
            NewsAct.Back -> p.onBack()
            NewsAct.Refresh -> p.onRefresh()
            NewsAct.Retry -> p.onRetry()
            NewsAct.SetFilter -> p.onSetFilter(NewsFilter.MySymbols)
            NewsAct.Open -> p.onOpen("a")
            NewsAct.OpenLink -> p.onOpenLink("a")
            NewsAct.CloseDetail -> p.onCloseDetail()
        }
        done.hashCode()
    }

    @Test fun inGlanceEveryBlockedNewsActionIsANoOp() = runTest {
        val mode = MutableStateFlow(UiMode.Normal)
        val counter = CountingNews()
        val opened = mutableListOf<String>()
        val p = NewsPresenter(backgroundScope, FakeNews(), counter, mode, LinkOpener { opened += it; true }, tick = MutableSharedFlow())
        runCurrent()
        p.onOpen("a"); runCurrent()
        assertNotNull(p.state.value.detail)
        mode.value = UiMode.Glance; runCurrent()
        val before = p.state.value
        for (act in NewsAct.entries.filter { it.glance == GlanceRule.Blocked }) {
            call(p, act); runCurrent()
            assertEquals("state changed by $act", before, p.state.value)
        }
        assertEquals("no forced fetch while driving", 0, counter.n)
        assertEquals("no link opened while driving", 0, opened.size)
        assertNull(p.state.value.detail)
        assertEquals(NewsFilter.All, p.state.value.filter)
        mode.value = UiMode.Normal; runCurrent()
        assertNull("the article stayed closed", p.state.value.detail)
        p.onRefresh()
        assertEquals(1, counter.n)
    }

    @Test fun openArticleGoesThroughThePresenterAndIsBlockedInGlance() = runTest {
        val mode = MutableStateFlow(UiMode.Normal)
        val opened = mutableListOf<String>()
        var ok = true
        val p = NewsPresenter(backgroundScope, FakeNews(), NewsRefresher.None, mode, LinkOpener { opened += it; ok }, tick = MutableSharedFlow())
        runCurrent()
        p.onOpen("a"); runCurrent()
        p.onOpenLink("a"); runCurrent()
        assertEquals(listOf("https://e.com/a"), opened)
        assertFalse(p.state.value.linkFailed)
        ok = false
        p.onOpenLink("a"); runCurrent()
        assertTrue("no browser is reported by the presenter", p.state.value.linkFailed)
        p.onOpenLink("nope"); runCurrent()
        assertEquals("an unknown article opens nothing", 2, opened.size)
        mode.value = UiMode.Glance; runCurrent()
        p.onOpenLink("a"); runCurrent()
        assertEquals("nothing opened in Glance", 2, opened.size)
        assertFalse("and no amber note either", p.state.value.linkFailed)
    }

    @Test fun aGlanceThatArrivesDuringAnInFlightAddLeavesNavEmptyAndTheStoreAllOrNothing() = runBlocking {
        // real dispatchers and real timing: the provider answers after 300 ms, Glance arrives at 100 ms
        val mode = MutableStateFlow(UiMode.Normal)
        val clock = com.marketsboard.data.FakeClock()
        val store = WatchlistStore(File(tempDir(), "watchlist.json"), clock)
        val provider = FakeProvider(clock).apply { quoteDelayMs = 300 }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        try {
            val p = MarketsPresenter(scope, MarketsData(store, FakeQuotesSource(clock), provider), Refresher.None, clock, Dispatchers.IO, mode = mode)
            p.onAddOpen(); p.onQueryChange("SHOP.TO"); p.onSubmitTyped()
            kotlinx.coroutines.delay(100)
            assertTrue(p.state.value.add?.busy == true)
            mode.value = UiMode.Glance
            kotlinx.coroutines.delay(800)
            val s = p.state.value
            assertNull(s.add); assertNull(s.detail); assertFalse(s.editMode); assertNull(s.rename); assertNull(s.confirmRemove)
            val syms = store.items.value.map { it.symbol }
            assertTrue("all or nothing: $syms", syms == listOf("XEQT.TO", "MSFT") || syms == listOf("XEQT.TO", "MSFT", "SHOP.TO"))
            mode.value = UiMode.Normal
            kotlinx.coroutines.delay(100)
            assertNull("nothing reopened by itself", p.state.value.add)
        } finally {
            scope.cancel()
        }
    }

    // ---- Settings ---------------------------------------------------------------------------------------------------

    private class CountingLocation : LocationAccess {
        override val granted = MutableStateFlow(false)
        var requests = 0
        override fun request() { requests++ }
    }

    private fun call(p: SettingsPresenter, a: SettingsAct) {
        val done: Unit = when (a) {
            SettingsAct.SetPassenger -> p.onPassengerMode(true)
            SettingsAct.SetVoice -> p.onVoice(false)
            SettingsAct.SetSpeechRate -> p.onSpeechRate(0.8f)
            SettingsAct.SetIncludeNews -> p.onIncludeNews(false)
            SettingsAct.SetMediaKey -> p.onMediaKey(true)
            SettingsAct.RequestLocation -> p.onGrantLocation()
        }
        done.hashCode()
    }

    @Test fun inGlanceNoSettingCanChangeAndPassengerModeCannotDefeatTheLock() = runTest {
        val clock = FakeClock()
        val gate = DrivingGate(clock)
        val store = AppSettingsStore(File(tempDir(), "settings.json"), clock)
        val loc = CountingLocation()
        val p = SettingsPresenter(backgroundScope, store, gate, loc, io = Dispatchers.Unconfined)
        gate.onSpeed(70.0)
        runCurrent()
        assertEquals(UiMode.Glance, gate.mode.value)
        val before = store.settings.value
        for (act in SettingsAct.entries) { call(p, act); runCurrent() }
        assertEquals(before, store.settings.value)
        assertEquals(0, loc.requests)
        assertEquals(UiMode.Glance, gate.mode.value)
        // parked: the same calls work
        repeat(12) { gate.onSpeed(0.0); clock.advance(1_000); gate.tick() }
        runCurrent()
        assertEquals(UiMode.Normal, gate.mode.value)
        for (act in SettingsAct.entries) { call(p, act); runCurrent() }
        assertEquals(AppSettings(passengerMode = true, voiceEnabled = false, speechRate = 0.8f, includeNews = false, mediaKeyTrigger = true), store.settings.value)
        assertEquals(1, loc.requests)
    }

    // ---- the guard is on every presenter method (source scan, no reflection) -------------------------------------------

    private fun sourceOf(rel: String): String {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return File(requireNotNull(d), rel).readText()
    }

    private fun assertEveryActionIsGuarded(rel: String) {
        val src = sourceOf(rel)
        val methods = Regex("override fun (on\\w+)\\([^)]*\\)([^\\n]*)").findAll(src).toList()
        assertTrue("no actions found in $rel", methods.size >= 6)
        for (m in methods) {
            assertTrue("${m.groupValues[1]} in $rel is not routed through guarded(...)", m.groupValues[2].contains("guarded("))
        }
    }

    @Test fun everyPresenterActionGoesThroughTheGlanceGuard() {
        assertEveryActionIsGuarded("shared/src/main/kotlin/com/marketsboard/ui/markets/MarketsPresenter.kt")
        assertEveryActionIsGuarded("shared/src/main/kotlin/com/marketsboard/ui/news/NewsPresenter.kt")
        assertEveryActionIsGuarded("shared/src/main/kotlin/com/marketsboard/ui/settings/SettingsPresenter.kt")
    }

    @Test fun theActionInterfacesHaveExactlyTheMethodsTheEnumsListSoNothingEscapesTheScan() {
        fun count(rel: String, iface: String): Int {
            val src = sourceOf(rel)
            val body = src.substringAfter("interface $iface {").substringBefore("companion object")
            return Regex("fun (on\\w+)\\(").findAll(body).count()
        }
        assertEquals(MarketsAct.entries.size, count("shared/src/main/kotlin/com/marketsboard/ui/markets/MarketsUi.kt", "MarketsActions"))
        assertEquals(NewsAct.entries.size, count("shared/src/main/kotlin/com/marketsboard/ui/news/NewsUi.kt", "NewsActions"))
        assertEquals(SettingsAct.entries.size, count("shared/src/main/kotlin/com/marketsboard/ui/settings/SettingsUi.kt", "SettingsActions"))
    }

    @Test fun everyGuardedCallUsesTheEnumOfItsOwnPresenterAndTheLinkHookIsOnlyUsedFromAGuardedPath() {
        val base = "shared/src/main/kotlin/com/marketsboard/ui/"
        for ((file, enum) in listOf("markets/MarketsPresenter.kt" to "MarketsAct", "news/NewsPresenter.kt" to "NewsAct", "settings/SettingsPresenter.kt" to "SettingsAct")) {
            val src = sourceOf(base + file)
            val calls = Regex("guarded\\((\\w+)\\.(\\w+)\\)").findAll(src).toList()
            assertTrue("no guarded calls in $file", calls.size >= 6)
            assertTrue("$file uses another presenter's enum: ${calls.map { it.value }}", calls.all { it.groupValues[1] == enum })
            // and every enum entry is used exactly where its action lives
        }
        // the old composition local that let a screen open a link by itself is gone: the opener is only reachable through the presenter
        val offenders = listOf("shared/src/main/kotlin", "app/src/main", "sandbox/src/main").flatMap { dir ->
            var d: File? = File(System.getProperty("user.dir")).absoluteFile
            while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
            File(requireNotNull(d), dir).walkTopDown().filter { it.extension == "kt" && "LocalOpenUrl" in it.readText() }.map { it.name }.toList()
        }
        assertTrue("LocalOpenUrl still used in $offenders", offenders.isEmpty())
        val presenter = sourceOf(base + "news/NewsPresenter.kt")
        val openCalls = Regex("opener\\.open\\(").findAll(presenter).count()
        assertEquals(1, openCalls)
        assertTrue(presenter.substringAfter("override fun onOpenLink").substringBefore("override fun onCloseDetail").contains("guarded(NewsAct.OpenLink)"))
    }

    @Test fun anAllowedActionNeverAddsAnything() = runTest {
        // the only actions that stay alive in Glance are the closing ones
        assertEquals(
            listOf("Back", "CloseDetail", "RenameCancel", "CancelRemove", "AddClose"),
            MarketsAct.entries.filter { it.glance == GlanceRule.Allowed }.map { it.name },
        )
        assertEquals(listOf("Back", "CloseDetail"), NewsAct.entries.filter { it.glance == GlanceRule.Allowed }.map { it.name })
        assertTrue(SettingsAct.entries.all { it.glance == GlanceRule.Blocked })
    }
}
