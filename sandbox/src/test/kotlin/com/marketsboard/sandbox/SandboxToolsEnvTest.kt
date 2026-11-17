package com.marketsboard.sandbox

import com.marketsboard.refresh.NetworkStatus
import com.marketsboard.ui.news.NewsPhase
import com.marketsboard.ui.tools.ToolsPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5 end to end in the sandbox (real time): the simulated network toggle, the offline state, the feed editor and the diagnostics. */
class SandboxToolsEnvTest {
    private suspend fun <T> until(timeoutMs: Long = 30_000, f: () -> T?): T = withTimeout(timeoutMs) {
        var v = f()
        while (v == null) { delay(20); v = f() }
        v
    }

    private suspend fun SandboxEnv.ready() = until { newsPresenter.state.value.takeIf { it.phase == NewsPhase.Ready && it.rows.size > 10 && !it.refreshing } }

    private fun env(block: suspend (SandboxEnv) -> Any?) {
        runBlocking {
            // The fake news servers change their content on 5 minute boundaries: never start a test just before one.
            val toBoundary = 300_000L - System.currentTimeMillis() % 300_000L
            if (toBoundary < 25_000L) delay(toBoundary + 1_000L)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try { block(SandboxEnv(scope)) } finally { scope.cancel() }
        }
    }

    @Test fun aNetworkDropShowsOneOfflineStateKeepsTheDataStopsPollingAndRecoversByItself() = env { e ->
        e.ready()
        until { e.presenter.state.value.takeIf { s -> s.quotes.isNotEmpty() && s.quotes.all { it.price != null } } }
        assertFalse(e.connectivity.offline.value)
        e.setNetworkOnline(false)
        until { e.connectivity.offline.value.takeIf { it } }
        assertEquals(NetworkStatus.Offline, e.networkStatus.value)
        // a pass that was already running when the network dropped may still finish: wait until nothing is in flight before counting
        until { (!e.news.state.value.refreshing && !e.quotes.refreshing.value).takeIf { it } }
        delay(300)
        // the last data stays on screen
        assertTrue(e.newsPresenter.state.value.rows.isNotEmpty())
        assertTrue(e.presenter.state.value.quotes.all { it.price != null })
        // no automatic fetching while there is no network
        val newsCalls = e.newsFetcher.calls
        val quoteCalls = e.fake.quoteCalls
        delay(2_500)
        assertEquals(newsCalls, e.newsFetcher.calls)
        assertEquals(quoteCalls, e.fake.quoteCalls)
        // a long outage (two virtual minutes in a quarter of a second), so the feeds are due again when the network returns
        e.time.speed = 600.0
        delay(250)
        e.time.speed = 1.0
        // back: the flag clears and a pass runs without anyone pressing Refresh
        e.setNetworkOnline(true)
        until { e.connectivity.offline.value.takeIf { !it } }
        until { e.newsFetcher.calls.takeIf { it > newsCalls } }
    }

    @Test fun refreshWhileOfflineNeverCrashesAndTheStateStaysOffline() = env { e ->
        e.ready()
        e.setNetworkOnline(false)
        until { e.connectivity.offline.value.takeIf { it } }
        e.presenter.onRefresh()
        e.newsPresenter.onRefresh()
        e.newsPoller.refreshNow()
        delay(1_500)
        assertTrue(e.connectivity.offline.value)
        assertTrue(e.newsPresenter.state.value.rows.isNotEmpty())
        e.setNetworkOnline(true)
        until { e.connectivity.offline.value.takeIf { !it } }
    }

    @Test fun theHotspotDropButtonDropsAndReturns() = env { e ->
        e.ready()
        e.simulateHotspotDrop(2)
        until { e.connectivity.offline.value.takeIf { it } }
        until { e.connectivity.offline.value.takeIf { !it } }
        assertEquals(NetworkStatus.Online, e.networkStatus.value)
    }

    @Test fun theFeedEditorChangesWhatTheNewsRepositoryFetches() = env { e ->
        e.ready()
        e.tools.onOpenFeeds()
        until { e.tools.state.value.takeIf { it.page == ToolsPage.Feeds && it.feeds.rows.size == 5 } }
        e.tools.onToggle("cbc-business", false)
        until { e.news.state.value.takeIf { s -> s.feedStates.none { it.id == "cbc-business" } } }
        assertFalse(e.tools.state.value.feeds.rows.first { it.id == "cbc-business" }.enabled)
        e.tools.onSymbolFeeds(false)
        until { e.news.state.value.takeIf { s -> s.feedStates.none { it.id.startsWith("sym:") } } }
        e.tools.onAskReset()
        e.tools.onConfirmReset()
        until { e.news.state.value.takeIf { s -> s.feedStates.any { it.id == "cbc-business" } && s.feedStates.any { it.id.startsWith("sym:") } } }
        e.tools.onToolsBack()
        until { e.tools.state.value.takeIf { it.page == ToolsPage.Main } }
    }

    @Test fun theDiagnosticsPageShowsRealHealthWithoutUrlsOrPositions() = env { e ->
        e.ready()
        e.tools.onSettingsVisible(true) // the diagnostics only run while the Settings tab is on screen
        e.tools.onOpenDiagnostics()
        val d = until { e.tools.state.value.diagnostics.takeIf { it.sections.isNotEmpty() } }
        assertEquals(listOf("Connection", "Prices", "Watchlist cache", "News feeds", "Speed source"), d.sections.map { it.title })
        val news = until { e.tools.state.value.diagnostics.sections.first { it.title == "News feeds" }.takeIf { s -> s.items.size > 3 && s.items.any { i -> i.lines.any { l -> l.label == "Answers" && !l.value.startsWith("0 new, 0 not") } } } }
        val text = e.tools.state.value.diagnostics.sections.flatMap { it.items }.flatMap { it.lines }.joinToString("\n") { "${it.label} ${it.value}" }
        assertFalse(text.contains("https://"))
        assertFalse(text.contains(Char(0x2014)))
        assertTrue(news.items.any { it.heading == "CBC Business" })
        e.tools.onToolsBack()
    }
}
