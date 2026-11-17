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

    @Test fun aNetworkSignalRetriesFailedFeedsAtOnceButHonoursTheirBackoff() = runTest {
        var up = false
        var dead = true
        val r = rig(listOf(feed("a"), feed("b"), feed("c"))) { url ->
            when {
                url.contains("/a.xml") && !up -> HttpResponse(503, "")
                url.contains("/c.xml") && dead -> HttpResponse(0, failure = com.marketsboard.data.model.FetchResult.Err(com.marketsboard.data.model.ErrorKind.Network, "x"), offline = true)
                else -> page("Item ${url.length}", base = 1_699_999_000_000L)
            }
        }
        r.gate.value = true
        runCurrent()
        assertEquals(3, r.calls)
        advanceTimeBy(10_000)
        up = true; dead = false
        r.net.back()
        advanceTimeBy(1_100)
        runCurrent()
        // a 503 backs the feed off for a minute: the signal does not override it; the network failure has no backoff and is retried
        assertEquals(4, r.calls)
        assertTrue(r.fetcher.urls.last().contains("/c.xml"))
    }

    @Test fun returnAfterAnOutageIsNotForcedARateLimitedFeedKeepsItsBackoff() = runTest {
        var limited = true
        val r = rig(listOf(feed("a"), feed("b"))) { url ->
            if (url.contains("/a.xml") && limited) HttpResponse(429, "", retryAfterMs = 600_000L) else page("Item ${url.length}", base = 1_699_999_000_000L)
        }
        r.gate.value = true
        runCurrent()
        assertEquals(2, r.calls)
        r.net.set(NetworkStatus.Offline)
        advanceTimeBy(5 * 60_000L)
        limited = false
        r.net.set(NetworkStatus.Online)
        r.net.back()
        advanceTimeBy(1_100)
        runCurrent()
        val urls = r.fetcher.urls.drop(2)
        assertTrue("b is due again after the outage: $urls", urls.any { it.contains("/b.xml") })
        assertTrue("a is still inside its 10 minute Retry-After: $urls", urls.none { it.contains("/a.xml") })
    }

    @Test fun comingBackOnScreenAfterAnOutageThatEndedMeanwhileRunsTheReturnPassAtOnce() = runTest {
        val r = rig(listOf(feed("a")))
        r.gate.value = true
        runCurrent()
        assertEquals(1, r.calls)
        r.gate.value = false
        runCurrent()
        r.net.set(NetworkStatus.Offline) // the hotspot drops and returns while the app is in the background; no signal reaches it
        r.net.set(NetworkStatus.Online)
        advanceTimeBy(5 * 60_000L)
        r.gate.value = true
        runCurrent()
        assertEquals("the resume answers the outage at once", 2, r.calls)
    }

    @Test fun whileAFeedIsFailingThePollRunsEveryMinuteNotEveryTenToRecoverByItself() = runTest {
        var up = false
        val r = rig(listOf(feed("a"))) { _ -> if (up) page("Back", base = 1_699_999_000_000L) else HttpResponse(0, failure = com.marketsboard.data.model.FetchResult.Err(com.marketsboard.data.model.ErrorKind.Network, "x"), offline = true) }
        r.gate.value = true
        runCurrent()
        assertEquals(1, r.calls)
        up = true
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals("recovered within about a minute without any network signal", 2, r.calls)
        assertEquals(NewsOverall.Fresh, r.repo.state.value.overall)
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

    // ---- M5 connectivity ------------------------------------------------------------------------------------------------

    @Test fun noAutomaticPassWhileOfflineAndTheReturnFetchesEverythingOnce() = runTest {
        val r = rig(listOf(feed("a"), feed("b")))
        r.net.set(NetworkStatus.Offline)
        r.gate.value = true
        runCurrent()
        advanceTimeBy(35 * 60_000L)
        runCurrent()
        assertEquals("no timeouts, no spinner storms while there is no network", 0, r.calls)
        r.net.set(NetworkStatus.Online)
        r.net.back()
        advanceTimeBy(900)
        runCurrent()
        assertEquals("not before the settle time", 0, r.calls)
        advanceTimeBy(200)
        runCurrent()
        assertEquals("every feed is due again, not only failed ones", 2, r.calls)
    }

    @Test fun aBurstOfNetworkSignalsIsOnePass() = runTest {
        val r = rig(listOf(feed("a")))
        r.gate.value = true
        runCurrent()
        assertEquals(1, r.calls)
        advanceTimeBy(70_000)
        r.net.set(NetworkStatus.Online)
        repeat(4) { r.net.back(); advanceTimeBy(200); runCurrent() }
        advanceTimeBy(1_500)
        runCurrent()
        assertTrue("calls=${r.calls}", r.calls <= 2)
    }

    @Test fun oneDeadFeedNeverMakesTheHealthyFeedsRefetchEveryMinute() = runTest {
        val deadHits = mutableListOf<Long>()
        val r = rig(listOf(feed("a"), feed("b"), feed("dead"))) { url ->
            if (url.contains("/dead.xml")) { deadHits += testScheduler.currentTime; HttpResponse(503, "") } else page("Item ${url.length}", base = 1_699_999_000_000L)
        }
        r.gate.value = true
        runCurrent()
        assertEquals(3, r.calls)
        advanceTimeBy(9 * 60_000L + 30_000L) // inside the 10 minute poll period
        runCurrent()
        val healthy = r.fetcher.urls.count { !it.contains("/dead.xml") }
        assertEquals("healthy feeds are fetched once, not every minute", 2, healthy)
        assertTrue("the dead feed follows its own backoff (1, 2, 4 min), not every minute: $deadHits", deadHits.size in 3..4)
        advanceTimeBy(60_000L) // the full pass at ten minutes
        runCurrent()
        assertEquals("a full pass every pollMs", 4, r.fetcher.urls.count { !it.contains("/dead.xml") })
    }

    @Test fun afterAnOutageAFeedThatJustFailedIsRetriedAtOnceNotAfterTheFiveSecondGuard() = runTest {
        var up = false
        val r = rig(listOf(feed("a"))) { _ -> if (up) page("Back", base = 1_699_999_000_000L) else HttpResponse(0, failure = com.marketsboard.data.model.FetchResult.Err(com.marketsboard.data.model.ErrorKind.Network, "x"), offline = true) }
        r.gate.value = true
        runCurrent()
        assertEquals(1, r.calls)
        r.net.set(NetworkStatus.Offline)
        advanceTimeBy(1_000)
        up = true
        r.net.set(NetworkStatus.Online)
        r.net.back()
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals("retried 2 s after its failure", 2, r.calls)
        assertEquals(NewsOverall.Fresh, r.repo.state.value.overall)
    }

    @Test fun aSignalWhileStillOfflineDoesNothing() = runTest {
        val r = rig()
        r.net.set(NetworkStatus.Offline)
        r.gate.value = true
        runCurrent()
        r.net.back()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, r.calls)
    }
}
