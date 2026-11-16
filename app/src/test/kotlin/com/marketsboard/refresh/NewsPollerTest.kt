package com.marketsboard.refresh

import com.marketsboard.FakeNetwork
import com.marketsboard.VirtualClock
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.net.HttpResponse
import com.marketsboard.data.news.DelayFetcher
import com.marketsboard.data.news.FakeFeedSource
import com.marketsboard.data.news.NewsConfig
import com.marketsboard.data.news.NewsOverall
import com.marketsboard.data.news.NewsRepository
import com.marketsboard.data.news.Quad
import com.marketsboard.data.news.feed
import com.marketsboard.data.news.rfc
import com.marketsboard.data.news.rssXml
import com.marketsboard.data.store.FileJsonCache
import com.marketsboard.data.store.WatchlistStore
import com.marketsboard.data.tempDir
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NewsPollerTest {
    private class Rig(val scope: TestScope, feeds: List<com.marketsboard.data.news.FeedConfig> = listOf(feed("a")), val dir: File = tempDir(), config: NewsPollConfig = NewsPollConfig(), handler: (String) -> HttpResponse) {
        val clock = VirtualClock(scope.testScheduler)
        val source = FakeFeedSource(feeds)
        val watch = MutableStateFlow(WatchlistStore.DEFAULTS)
        val gate = MutableStateFlow(false)
        val net = FakeNetwork()
        val fetcher = DelayFetcher(0) { url, _ -> handler(url) }
        val repo = NewsRepository(source, watch, fetcher, FileJsonCache(File(dir, "cache"), clock), clock, NewsConfig(), StandardTestDispatcher(scope.testScheduler))
        val poller = NewsPoller(scope.backgroundScope, repo, source, watch, gate, net, clock, config)
        val calls get() = fetcher.urls.size
    }

    private fun page(vararg t: String, base: Long) = HttpResponse(
        200, rssXml(*t.mapIndexed { i, x -> Quad(x, "https://e.com/${x.hashCode()}", rfc(base - i * 60_000L)) }.toTypedArray()),
    )

    private fun TestScope.rig(feeds: List<com.marketsboard.data.news.FeedConfig> = listOf(feed("a")), config: NewsPollConfig = NewsPollConfig(), handler: (String) -> HttpResponse = { page("Headline one", base = 1_699_999_000_000L) }) =
        Rig(this, feeds, config = config, handler = handler)

    @Test fun nothingHappensWhileTheAppIsInactive() = runTest {
        val r = rig()
        runCurrent()
        advanceTimeBy(60 * 60_000L)
        assertEquals(0, r.calls)
    }

    @Test fun resumeStartsAnImmediatePassThenPollsEveryTenMinutes() = runTest {
        val r = rig()
        r.gate.value = true
        runCurrent()
        assertEquals(1, r.calls)
        advanceTimeBy(10 * 60_000L - 1)
        assertEquals(1, r.calls)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, r.calls)
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(3, r.calls)
    }

    @Test fun pausingStopsPollingAndResumingFetchesAtOnceAfterTheGap() = runTest {
        val r = rig()
        r.gate.value = true
        runCurrent()
        r.gate.value = false
        runCurrent()
        advanceTimeBy(60 * 60_000L)
        assertEquals(1, r.calls)
        r.gate.value = true
        runCurrent()
        assertEquals(2, r.calls)
    }

    @Test fun aQuickPauseAndResumeDoesNotRefetch() = runTest {
        val r = rig()
        r.gate.value = true
        runCurrent()
        advanceTimeBy(10_000)
        r.gate.value = false
        runCurrent()
        advanceTimeBy(10_000)
        r.gate.value = true
        runCurrent()
        assertEquals("within the 60 s minimum gap", 1, r.calls)
    }

    @Test fun cachedHeadlinesAreShownBeforeTheAppIsEverActive() = runTest {
        val dir = tempDir()
        val first = Rig(this, dir = dir) { page("Cached headline", base = 1_699_999_000_000L) }
        first.repo.refresh()
        runCurrent()
        val second = Rig(this, dir = dir) { error("not active, must not fetch") }
        runCurrent()
        assertEquals(listOf("Cached headline"), second.repo.state.value.items.map { it.title })
        assertTrue(second.repo.state.value.overall is NewsOverall.Stale)
        assertEquals(0, second.calls)
    }

    @Test fun networkReturningRetriesFailedFeedsAtOnceIgnoringBackoff() = runTest {
        var up = false
        val r = rig(listOf(feed("a"), feed("b"))) { url -> if (url.contains("/a.xml") && !up) HttpResponse(500, "") else page("Item ${url.length}", base = 1_699_999_000_000L) }
        r.gate.value = true
        runCurrent()
        assertEquals(2, r.calls)
        advanceTimeBy(10_000)
        up = true
        r.net.back()
        runCurrent()
        assertEquals("only the failed feed is retried", 3, r.calls)
        assertTrue(r.fetcher.urls.last().contains("/a.xml"))
    }

    @Test fun manualRefreshIsForcedAndDebounced() = runTest {
        val r = rig()
        r.gate.value = true
        runCurrent()
        r.poller.refreshNow()
        runCurrent()
        assertEquals("forced even inside the minimum gap", 2, r.calls)
        r.poller.refreshNow()
        runCurrent()
        assertEquals("second press inside 5 s is ignored", 2, r.calls)
        advanceTimeBy(6_000)
        r.poller.refreshNow()
        runCurrent()
        assertEquals(3, r.calls)
    }

    @Test fun addingAFeedFetchesItAtOnceAndAWatchlistChangeRetags() = runTest {
        val r = rig { page("Microsoft unveils things", base = 1_699_999_000_000L) }
        r.gate.value = true
        runCurrent()
        assertEquals(listOf("MSFT"), r.repo.state.value.items.single().tags)
        r.watch.value = listOf(WatchItem("SHOP.TO", "Shopify Inc.", "CAD", "TSX"))
        runCurrent()
        assertTrue(r.repo.state.value.items.single().tags.isEmpty())
        r.source.feedFlow.value = r.source.feedFlow.value + feed("c")
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(r.fetcher.urls.any { it.contains("/c.xml") })
        assertEquals(2, r.repo.state.value.feedStates.size)
    }

    @Test fun listTurnsStaleWhenEveryPassKeepsFailing() = runTest {
        var up = true
        val r = rig { if (up) page("Held", base = 1_699_999_000_000L) else HttpResponse(500, "") }
        r.gate.value = true
        runCurrent()
        assertEquals(NewsOverall.Fresh, r.repo.state.value.overall)
        up = false
        advanceTimeBy(27 * 60_000L)
        runCurrent()
        assertTrue(r.repo.state.value.overall is NewsOverall.Stale)
        assertTrue(r.repo.state.value.lastPassFailed)
    }

    @Test fun timeScaleSpeedsUpThePollLoop() = runTest {
        val r = rig(config = NewsPollConfig(timeScale = { 10.0 })) { page("Fast", base = 1_699_999_000_000L) }
        r.gate.value = true
        runCurrent()
        advanceTimeBy(61_000)
        runCurrent()
        assertTrue("calls=${r.calls}", r.calls >= 2)
    }
}
