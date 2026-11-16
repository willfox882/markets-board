package com.marketsboard.sandbox

import com.marketsboard.ui.UiMode
import com.marketsboard.ui.news.NewsFilter
import com.marketsboard.ui.news.NewsPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real news repository, poller and presenter against the fake news servers (real time). */
class SandboxNewsEnvTest {
    private suspend fun <T> until(timeoutMs: Long = 15_000, f: () -> T?): T = withTimeout(timeoutMs) {
        var v = f()
        while (v == null) { delay(20); v = f() }
        v
    }

    /** Ready, with the first pass finished (so a flipped behaviour cannot hit a pass that is still running). */
    private suspend fun SandboxEnv.ready() = until { newsPresenter.state.value.takeIf { it.phase == NewsPhase.Ready && it.rows.size > 10 && !it.refreshing } }

    private fun env(block: suspend (SandboxEnv) -> Any?) {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try { block(SandboxEnv(scope)) } finally { scope.cancel() }
        }
    }

    @Test fun headlinesLoadOnStartAndMySymbolsFiltersThem() = env { e ->
        // The fake server's content changes on 5 minute boundaries; do not straddle one.
        if (System.currentTimeMillis() % 300_000L > 290_000L) delay(11_000)
        val s = e.ready()
        assertTrue(s.rows.any { "MSFT" in it.tags })
        e.newsPresenter.onSetFilter(NewsFilter.MySymbols)
        val mine = until { e.newsPresenter.state.value.takeIf { it.filter == NewsFilter.MySymbols } }
        assertTrue(mine.rows.isNotEmpty() && mine.rows.all { it.tags.isNotEmpty() })
        assertTrue(mine.rows.size < s.rows.size)
        // the second pass is answered with 304s where nothing changed
        e.newsPoller.refreshNow()
        until { e.newsFetcher.notModified.takeIf { it > 0 } }
    }

    @Test fun anArticleOpensAndGlanceHidesIt() = env { e ->
        val s = e.ready()
        e.newsPresenter.onOpen(s.rows.first().id)
        val d = until { e.newsPresenter.state.value.detail }
        assertEquals(s.rows.first().title, d.title)
        e.uiMode.value = UiMode.Glance
        until { e.newsPresenter.state.value.takeIf { it.detail == null && it.rows.size == 3 } }
        e.uiMode.value = UiMode.Normal
    }

    @Test fun offlineKeepsTheHeadlinesAndSaysSoThenRecovers() = env { e ->
        e.ready()
        e.newsFetcher.behavior = FakeNewsBehavior.Offline
        e.newsPoller.refreshNow()
        val off = until { e.newsPresenter.state.value.takeIf { it.banner.isNotEmpty() } }
        assertEquals("Can't reach the news sources", off.banner)
        assertEquals(NewsPhase.Ready, off.phase)
        assertTrue(off.rows.isNotEmpty())
        e.newsFetcher.behavior = FakeNewsBehavior.Normal
        delay(5_200) // manual refresh is debounced to one press per 5 s
        e.newsPoller.refreshNow()
        until { e.newsPresenter.state.value.takeIf { it.banner.isEmpty() } }
    }

    @Test fun aBreakingHeadlineAppearsAtTheTopAfterARefresh() = env { e ->
        e.ready()
        val title = e.newsFetcher.addBreaking()
        e.newsPoller.refreshNow()
        val top = until { e.newsPresenter.state.value.takeIf { st -> st.rows.firstOrNull()?.title == title } }
        assertEquals("now", top.rows.first().ago)
    }

    @Test fun oneFailingAndMalformedFeedsDoNotStopTheOthers() = env { e ->
        e.ready()
        e.newsFetcher.behavior = FakeNewsBehavior.OneFeedFailing
        e.newsPoller.refreshNow()
        val failing = until { e.news.state.value.feedStates.firstOrNull { it.id == "cbc-business" }?.takeIf { it.lastError != null } }
        assertTrue(failing.consecutiveFailures >= 1)
        assertTrue("held items stay", failing.itemCount > 0)
        until { e.news.state.value.feedStates.firstOrNull { it.id == "globe-business" }?.takeIf { it.lastError == null && !it.partial } }
        assertTrue(e.news.state.value.items.isNotEmpty())
        e.newsFetcher.behavior = FakeNewsBehavior.MalformedFeeds
        delay(5_200)
        e.newsPoller.refreshNow()
        val partial = until { e.news.state.value.feedStates.firstOrNull { it.id == "globe-business" }?.takeIf { it.partial } }
        assertTrue(partial.itemCount in 3..5)
        assertTrue(until { e.news.state.value.feedStates.firstOrNull { it.id == "yahoo-top" }?.takeIf { it.lastError != null } }.lastError!!.contains("unreadable"))
    }

    @Test fun theLastOpenedLinkIsRecordedForThePanel() = env { e ->
        e.openUrl("https://example.com/a")
        assertEquals("https://example.com/a", e.lastOpenedUrl.value)
    }
}
