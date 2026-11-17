package com.marketsboard.data.news

import com.marketsboard.data.FakeClock
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.net.HttpFetcher
import com.marketsboard.data.net.HttpResponse
import com.marketsboard.data.store.FileJsonCache
import com.marketsboard.data.store.WatchlistStore
import com.marketsboard.data.tempDir
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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

@OptIn(ExperimentalCoroutinesApi::class)
class NewsRepositoryTest {
    private val FEED_A = feed("a")
    private val FEED_B = feed("b")

    private class Rig(
        scope: TestScope,
        val feeds: FakeFeedSource,
        val clock: FakeClock = FakeClock(now = NOW_MS),
        val dir: File = tempDir(),
        watch: List<WatchItem> = emptyList(),
        delayMs: Long = 0,
        val config: NewsConfig = NewsConfig(),
        handler: (String, Map<String, String>) -> HttpResponse,
    ) {
        val watch = MutableStateFlow(watch)
        val fetcher = DelayFetcher(delayMs, handler)
        val io = StandardTestDispatcher(scope.testScheduler)
        val cache = FileJsonCache(File(dir, "cache"), clock)
        val repo = NewsRepository(feeds, this.watch, fetcher, cache, clock, config, io)
        fun again(h: HttpFetcher = fetcher) = NewsRepository(feeds, this.watch, h, FileJsonCache(File(dir, "cache"), clock), clock, config, io)
    }

    private fun xml(vararg t: String, base: Long = NOW_MS, prefix: String = "https://e.com/") =
        rssXml(*t.mapIndexed { i, x -> Quad(x, prefix + x.lowercase().replace(Regex("[^a-z0-9]+"), "-"), rfc(base - (i + 1) * 60_000L)) }.toTypedArray())

    private fun ok(body: String, etag: String? = null, lastMod: String? = null, date: Long? = null) = HttpResponse(
        200, body, buildMap { etag?.let { put("etag", it) }; lastMod?.let { put("last-modified", it) }; date?.let { put("date", rfc(it)) } },
    )

    private fun rig(scope: TestScope, feeds: List<FeedConfig> = listOf(FEED_A), symbolFeeds: Boolean = false, watch: List<WatchItem> = emptyList(), delayMs: Long = 0, config: NewsConfig = NewsConfig(), handler: (String, Map<String, String>) -> HttpResponse) =
        Rig(scope, FakeFeedSource(feeds, symbolFeeds), watch = watch, delayMs = delayMs, config = config, handler = handler)

    // ---- basics --------------------------------------------------------------------------------------------------

    @Test fun startsLoadingThenShowsHeadlinesNewestFirst() = runTest {
        val r = rig(this) { _, _ -> ok(xml("First story today", "Second story today", "Third story today")) }
        assertEquals(NewsOverall.Loading, r.repo.state.value.overall)
        r.repo.refresh()
        val s = r.repo.state.value
        assertEquals(NewsOverall.Fresh, s.overall)
        assertEquals(listOf("First story today", "Second story today", "Third story today"), s.items.map { it.title })
        assertTrue(s.items.zipWithNext().all { (a, b) -> a.publishedMs >= b.publishedMs })
        assertEquals("A", s.items[0].sourceName)
        assertEquals(FeedStatus.Ok, s.feedStates.single().status)
        assertEquals(3, s.feedStates.single().itemCount)
        assertEquals(0L, s.lastOkAgeMs)
        assertFalse(s.refreshing)
    }

    @Test fun refreshingFlagIsTrueDuringAPass() = runTest {
        val r = rig(this, delayMs = 1_000) { _, _ -> ok(xml("One")) }
        val job = launch { r.repo.refresh() }
        runCurrent()
        assertTrue(r.repo.state.value.refreshing)
        advanceTimeBy(1_100)
        job.join()
        assertFalse(r.repo.state.value.refreshing)
    }

    @Test fun mergesFeedsAndDropsDuplicateUrls() = runTest {
        val r = rig(this, listOf(FEED_A, FEED_B)) { url, _ ->
            if (url.contains("/a.xml")) ok(xml("Shared story across feeds", "Only in a")) else ok(xml("Shared story across feeds", "Only in b"))
        }
        r.repo.refresh()
        val titles = r.repo.state.value.items.map { it.title }
        assertEquals(3, titles.size)
        assertEquals(1, titles.count { it == "Shared story across feeds" })
    }

    @Test fun nearDuplicateTitlesFromDifferentSourcesAreMerged() = runTest {
        val r = rig(this, listOf(FEED_A, FEED_B)) { url, _ ->
            if (url.contains("/a.xml")) ok(rssXml(Quad("Microsoft stock slides as investors weigh cloud spending", "https://a.com/1", rfc(NOW_MS - 600_000))))
            else ok(rssXml(Quad("Microsoft stock slides as investors weigh heavy cloud spending", "https://b.com/2", rfc(NOW_MS - 300_000))))
        }
        r.repo.refresh()
        val items = r.repo.state.value.items
        assertEquals(1, items.size)
        assertEquals("A", items[0].sourceName)
    }

    @Test fun listIsCappedAt120AndDropsItemsOlderThanSevenDays() = runTest {
        val r = rig(this, listOf(feed("a"), feed("b"), feed("c"))) { url, _ ->
            val k = url.substringAfterLast('/').substringBefore('.')
            ok(rssXml(*(1..100).map { Quad("Story $k$it", "https://e.com/$k/$it", rfc(NOW_MS - (it * 60_000L + k[0].code))) }.toTypedArray()))
        }
        r.repo.refresh()
        val items = r.repo.state.value.items
        assertEquals(120, items.size)
        assertTrue(items.zipWithNext().all { (a, b) -> a.publishedMs >= b.publishedMs })

        val old = rig(this) { _, _ ->
            ok(rssXml(Quad("Fresh one", "https://e.com/1", rfc(NOW_MS - 6 * 86_400_000L)), Quad("Too old", "https://e.com/2", rfc(NOW_MS - 8 * 86_400_000L))))
        }
        old.repo.refresh()
        assertEquals(listOf("Fresh one"), old.repo.state.value.items.map { it.title })
    }

    @Test fun ageCutoffAlsoAppliesToHeldItemsAsTimePasses() = runTest {
        val r = rig(this) { _, _ -> ok(rssXml(Quad("Aging story", "https://e.com/1", rfc(NOW_MS - 6 * 86_400_000L)))) }
        r.repo.refresh()
        assertEquals(1, r.repo.state.value.items.size)
        r.clock.advance(2 * 86_400_000L)
        r.repo.recompute()
        assertTrue(r.repo.state.value.items.isEmpty())
    }

    @Test fun datelessItemsKeepTheTimeTheyWereFirstSeen() = runTest {
        val r = rig(this) { _, _ -> ok(rssXml(Quad("No date story", "https://e.com/nodate"))) }
        r.repo.refresh()
        val first = r.repo.state.value.items.single()
        assertTrue(first.dateEstimated)
        assertEquals(NOW_MS, first.publishedMs)
        r.clock.advance(3_600_000L)
        r.repo.refresh(force = true)
        assertEquals(NOW_MS, r.repo.state.value.items.single().publishedMs)
    }

    @Test fun futureDatesAreReplacedAndNeverFloatAboveRealNews() = runTest {
        val r = rig(this) { _, _ -> ok(rssXml(Quad("Future", "https://e.com/f", rfc(NOW_MS + 5 * 86_400_000L)), Quad("Real", "https://e.com/r", rfc(NOW_MS - 60_000)))) }
        r.repo.refresh()
        val f = r.repo.state.value.items.first { it.title == "Future" }
        assertEquals(NOW_MS, f.publishedMs)
        assertTrue(f.dateEstimated)
    }

    @Test fun aWrongDeviceClockIsCorrectedFromTheDateHeader() = runTest {
        // The device thinks it is 3 days earlier than the server. Items dated "now" by the server must not look like the future.
        val r = rig(this) { _, _ -> ok(rssXml(Quad("Today", "https://e.com/t", rfc(NOW_MS - 600_000))), date = NOW_MS) }
        r.clock.now = NOW_MS - 3 * 86_400_000L
        r.repo.refresh()
        val h = r.repo.state.value.items.single()
        assertEquals(NOW_MS - 600_000, h.publishedMs)
        assertFalse(h.dateEstimated)
        assertEquals(3 * 86_400_000L, r.repo.state.value.serverOffsetMs)
        assertEquals(NOW_MS, r.repo.correctedNowMs())
    }

    @Test fun smallClockDifferencesAreIgnored() = runTest {
        val r = rig(this) { _, _ -> ok(xml("One"), date = NOW_MS + 5_000) }
        r.repo.refresh()
        assertEquals(0L, r.repo.state.value.serverOffsetMs)
    }

    // ---- conditional GET, cache ---------------------------------------------------------------------------------

    @Test fun sendsValidatorsAndKeepsItemsOn304() = runTest {
        var n = 0
        val r = rig(this) { _, h ->
            n++
            if (n == 1) ok(xml("Cached one", "Cached two"), etag = "\"v1\"", lastMod = "Mon, 16 Nov 2026 10:00:00 GMT")
            else if (h["If-None-Match"] == "\"v1\"") HttpResponse(304, "", mapOf("etag" to "\"v1\"")) else ok(xml("Wrong"))
        }
        r.repo.refresh()
        assertNull(r.fetcher.sentHeaders[0]["If-None-Match"])
        r.clock.advance(120_000)
        r.repo.refresh()
        assertEquals("\"v1\"", r.fetcher.sentHeaders[1]["If-None-Match"])
        assertEquals("Mon, 16 Nov 2026 10:00:00 GMT", r.fetcher.sentHeaders[1]["If-Modified-Since"])
        val s = r.repo.state.value
        assertEquals(listOf("Cached one", "Cached two"), s.items.map { it.title })
        assertEquals(FeedStatus.NotModified, s.feedStates.single().status)
        assertEquals(NewsOverall.Fresh, s.overall)
        assertEquals(0L, s.lastOkAgeMs)
    }

    @Test fun a200AfterValidatorsReplacesTheItems() = runTest {
        var n = 0
        val r = rig(this) { _, _ -> if (++n == 1) ok(xml("Old story"), etag = "\"v1\"") else ok(xml("New story"), etag = "\"v2\"") }
        r.repo.refresh()
        r.clock.advance(120_000)
        r.repo.refresh()
        assertEquals(listOf("New story"), r.repo.state.value.items.map { it.title })
        r.clock.advance(120_000)
        r.repo.refresh()
        assertEquals("\"v2\"", r.fetcher.sentHeaders[2]["If-None-Match"])
    }

    @Test fun a304WithoutAnyHeldItemsIsAnError() = runTest {
        val r = rig(this) { _, _ -> HttpResponse(304, "") }
        r.repo.refresh()
        assertEquals(FeedStatus.Error, r.repo.state.value.feedStates.single().status)
    }

    @Test fun staleWhileRevalidateServesTheCacheInstantlyAfterARestart() = runTest {
        val r = rig(this) { _, _ -> ok(xml("Cached story one", "Cached story two"), etag = "\"c1\"") }
        r.repo.refresh()
        r.clock.advance(2 * 3_600_000L)
        val calls = r.fetcher.urls.size

        val second = r.again(DelayFetcher(0) { _, _ -> error("must not be called by hydrate") })
        assertEquals(NewsOverall.Loading, second.state.value.overall)
        second.hydrate()
        val s = second.state.value
        assertEquals(listOf("Cached story one", "Cached story two"), s.items.map { it.title })
        val o = s.overall
        assertTrue("cache is shown as Stale: $o", o is NewsOverall.Stale)
        assertEquals(2 * 3_600_000L, (o as NewsOverall.Stale).ageMs)
        assertEquals(calls, r.fetcher.urls.size)

        // the first pass after the restart sends the stored validators
        val f3 = DelayFetcher(0) { _, h -> if (h["If-None-Match"] == "\"c1\"") HttpResponse(304, "") else ok(xml("Other")) }
        val third = r.again(f3)
        third.refresh()
        assertEquals("\"c1\"", f3.sentHeaders.single()["If-None-Match"])
        assertEquals(NewsOverall.Fresh, third.state.value.overall)
        assertEquals(2, third.state.value.items.size)
    }

    @Test fun corruptCacheEntriesAreDroppedWithoutCrashing() = runTest {
        val r = rig(this) { _, _ -> ok(xml("Story")) }
        r.cache.put("news.feed.a", "{ not json", 1_000_000)
        r.repo.hydrate()
        assertTrue(r.repo.state.value.items.isEmpty())
        assertNull(r.cache.get("news.feed.a", allowExpired = true))
        r.cache.put("news.feed.a", """{"v":1,"items":[{"id":"x","title":"Bad link","url":"javascript:alert(1)","published":1},{"title":"No url"},{"title":"Good","url":"https://e.com/g","published":${NOW_MS - 1000}}]}""", 1_000_000)
        r.repo.hydrate()
        assertEquals(listOf("Good"), r.repo.state.value.items.map { it.title })
        r.cache.put("news.feed.a", """{"v":7,"items":[]}""", 1_000_000)
        r.repo.hydrate() // already held: untouched, still no crash
    }

    @Test fun hydrateUsesTheCacheWhenOfflineAndTheFeedKeepsItsItems() = runTest {
        val r = rig(this) { _, _ -> ok(xml("Held story")) }
        r.repo.refresh()
        val offline = r.again(DelayFetcher(0) { _, _ -> HttpResponse(0, failure = FetchResult.Err(ErrorKind.Network, "x"), offline = true) })
        offline.hydrate()
        r.clock.advance(30 * 60_000L)
        offline.refresh(force = true)
        val s = offline.state.value
        assertEquals(listOf("Held story"), s.items.map { it.title })
        assertTrue(s.overall is NewsOverall.Stale)
        assertTrue(s.lastPassFailed)
    }

    // ---- concurrency ---------------------------------------------------------------------------------------------

    @Test fun atMostThreeFeedsAreFetchedAtOnce() = runTest {
        val feeds = (1..8).map { feed("f$it") }
        val r = rig(this, feeds, delayMs = 500) { _, _ -> ok(xml("Story")) }
        val job = launch { r.repo.refresh() }
        advanceTimeBy(5_000)
        job.join()
        assertEquals(8, r.fetcher.urls.size)
        assertEquals(3, r.fetcher.maxActive)
    }

    @Test fun aRequestDuringARunningPassIsQueuedAsOneFollowUpPass() = runTest {
        val r = rig(this, delayMs = 1_000) { _, _ -> ok(xml("Story")) }
        val a = launch { r.repo.refresh() }
        runCurrent()
        val b = launch { r.repo.refresh(force = true) }
        val c = launch { r.repo.refresh(force = true) }
        advanceTimeBy(5_000)
        a.join(); b.join(); c.join()
        assertEquals("one pass plus ONE follow-up for the two queued requests", 2, r.fetcher.urls.size)
    }

    @Test fun aFeedAddedDuringARunningPassIsFetchedByTheFollowUp() = runTest {
        val r = rig(this, delayMs = 1_000) { _, _ -> ok(xml("Story")) }
        val a = launch { r.repo.refresh() }
        runCurrent()
        r.feeds.feedFlow.value = listOf(FEED_A, FEED_B)
        val b = launch { r.repo.refresh() } // what the poller does on a config change
        advanceTimeBy(5_000)
        a.join(); b.join()
        assertTrue(r.fetcher.urls.any { it.contains("/b.xml") })
        assertEquals(2, r.repo.state.value.feedStates.count { it.status == FeedStatus.Ok })
    }

    @Test fun networkBackDuringARunningPassStillRetriesTheFailedFeed() = runTest {
        var failA = true
        val r = rig(this, listOf(FEED_A, FEED_B), delayMs = 500) { url, _ -> if (url.contains("/a.xml") && failA) HttpResponse(500, "") else ok(xml("S ${url.length}")) }
        r.repo.refresh()
        failA = false
        r.clock.advance(10_000)
        val running = launch { r.repo.refresh(force = true) }
        runCurrent()
        val net = launch { r.repo.refresh(onlyFailed = true) }
        advanceTimeBy(5_000)
        running.join(); net.join()
        assertEquals(FeedStatus.Ok, r.repo.state.value.feedStates.first { it.id == "a" }.status)
    }

    @Test fun aPassCancelledMidWayLeavesTheFeedsDueEvenInsideTheMinimumGap() = runTest {
        val r = rig(this, listOf(FEED_A, FEED_B), delayMs = 10_000) { _, _ -> ok(xml("Story")) }
        val job = launch { r.repo.refresh() }
        advanceTimeBy(5_000) // both fetches are in flight
        assertEquals(2, r.fetcher.urls.size)
        job.cancel()
        runCurrent()
        r.clock.advance(10_000) // the app resumes 10 s later: far inside the 60 s gap
        val again = launch { r.repo.refresh() }
        advanceTimeBy(11_000)
        again.join()
        assertEquals("a cancelled attempt does not count as an attempt", 4, r.fetcher.urls.size)
        assertEquals(2, r.repo.state.value.feedStates.count { it.status == FeedStatus.Ok })
    }

    @Test fun aCancelledPassKeepsTheForcedRequestForTheNextRun() = runTest {
        val r = rig(this, delayMs = 10_000) { _, _ -> ok(xml("Story")) }
        val first = launch { r.repo.refresh() }
        advanceTimeBy(11_000)
        first.join()
        r.clock.advance(5_000) // inside the 60 s gap: a plain request would do nothing
        val job = launch { r.repo.refresh(force = true) }
        advanceTimeBy(1_000)
        assertEquals(2, r.fetcher.urls.size)
        job.cancel()
        runCurrent()
        val next = launch { r.repo.refresh() } // the pending forced request rides along
        advanceTimeBy(11_000)
        next.join()
        assertEquals(3, r.fetcher.urls.size)
    }

    @Test fun nonForcedPassesRespectTheMinimumGap() = runTest {
        val r = rig(this) { _, _ -> ok(xml("Story")) }
        r.repo.refresh()
        r.clock.advance(30_000)
        r.repo.refresh()
        assertEquals(1, r.fetcher.urls.size)
        r.repo.refresh(force = true)
        assertEquals(2, r.fetcher.urls.size)
        r.clock.advance(61_000)
        r.repo.refresh()
        assertEquals(3, r.fetcher.urls.size)
    }

    @Test fun cancellingAPassLeavesTheRepositoryUsable() = runTest {
        val r = rig(this, delayMs = 10_000) { _, _ -> ok(xml("Story")) }
        val job = launch { r.repo.refresh() }
        runCurrent()
        assertTrue(r.repo.state.value.refreshing)
        job.cancel()
        runCurrent()
        assertFalse(r.repo.state.value.refreshing)
        val job2 = launch { r.repo.refresh(force = true) }
        advanceTimeBy(11_000)
        job2.join()
        assertEquals(1, r.repo.state.value.items.size)
    }

    // ---- failures and backoff ----------------------------------------------------------------------------------

    @Test fun backoffDoublesPerFailureAndCapsAtThirtyMinutes() = runTest {
        val r = rig(this) { _, _ -> HttpResponse(500, "oops") }
        val expected = listOf(60_000L, 120_000L, 240_000L, 480_000L, 960_000L, 1_800_000L, 1_800_000L, 1_800_000L)
        for ((i, want) in expected.withIndex()) {
            r.repo.refresh()
            val st = r.repo.state.value.feedStates.single()
            assertEquals(i + 1, st.consecutiveFailures)
            assertEquals("attempt ${i + 1}", want, st.retryInMs)
            r.clock.advance(want - 1)
            val before = r.fetcher.urls.size
            r.repo.refresh()
            assertEquals("still backing off", before, r.fetcher.urls.size)
            r.clock.advance(1)
        }
    }

    @Test fun successResetsTheBackoffAndForceIgnoresIt() = runTest {
        var fail = true
        val r = rig(this) { _, _ -> if (fail) HttpResponse(503, "") else ok(xml("Back again")) }
        r.repo.refresh()
        assertEquals(60_000L, r.repo.state.value.feedStates.single().retryInMs)
        fail = false
        r.repo.refresh() // within backoff: skipped
        assertEquals(1, r.fetcher.urls.size)
        r.repo.refresh(force = true)
        val s = r.repo.state.value.feedStates.single()
        assertEquals(FeedStatus.Ok, s.status)
        assertEquals(0, s.consecutiveFailures)
        assertEquals(0L, s.retryInMs)
        assertNull(s.lastError)
    }

    @Test fun retryAfterIsHonouredButCapped() = runTest {
        val r = rig(this) { _, _ -> HttpResponse(429, "slow down", retryAfterMs = 10 * 60_000L) }
        r.repo.refresh()
        assertEquals(10 * 60_000L, r.repo.state.value.feedStates.single().retryInMs)
        val r2 = rig(this) { _, _ -> HttpResponse(429, "slow down", retryAfterMs = 5 * 3_600_000L) }
        r2.repo.refresh()
        assertEquals(30 * 60_000L, r2.repo.state.value.feedStates.single().retryInMs)
    }

    @Test fun oneFailingFeedDoesNotHideTheOthers() = runTest {
        val r = rig(this, listOf(FEED_A, FEED_B)) { url, _ -> if (url.contains("/a.xml")) HttpResponse(500, "") else ok(xml("From b")) }
        r.repo.refresh()
        val s = r.repo.state.value
        assertEquals(listOf("From b"), s.items.map { it.title })
        assertEquals(FeedStatus.Error, s.feedStates.first { it.id == "a" }.status)
        assertEquals(FeedStatus.Ok, s.feedStates.first { it.id == "b" }.status)
        assertEquals(NewsOverall.Fresh, s.overall)
        assertFalse(s.lastPassFailed)
        assertNotNull(s.feedStates.first { it.id == "a" }.lastError)
    }

    @Test fun malformedFeedIsATypedParseErrorAndPartialOnesAreSalvaged() = runTest {
        val truncated = rssXml(Quad("Complete", "https://e.com/c")).dropLast(17) + "<item><title>cut"
        val r = rig(this, listOf(FEED_A, FEED_B, feed("c"))) { url, _ ->
            when {
                url.contains("/a.xml") -> ok("<html><body>Sign in</body></html>")
                url.contains("/b.xml") -> ok(truncated)
                else -> ok("")
            }
        }
        r.repo.refresh()
        val s = r.repo.state.value
        assertEquals(FeedStatus.Error, s.feedStates.first { it.id == "a" }.status)
        assertTrue(s.feedStates.first { it.id == "a" }.lastError!!.contains("unreadable"))
        val b = s.feedStates.first { it.id == "b" }
        assertEquals(FeedStatus.Ok, b.status)
        assertTrue(b.partial)
        assertEquals(listOf("Complete"), s.items.map { it.title })
        assertEquals(FeedStatus.Error, s.feedStates.first { it.id == "c" }.status)
    }

    @Test fun anExceptionFromTheFetcherNeverEscapes() = runTest {
        val r = rig(this) { _, _ -> throw IllegalStateException("boom") }
        r.repo.refresh()
        assertEquals(FeedStatus.Error, r.repo.state.value.feedStates.single().status)
        assertTrue(r.repo.state.value.overall is NewsOverall.Error)
    }

    @Test fun cancellationIsNotSwallowed() = runTest {
        val r = rig(this) { _, _ -> throw CancellationException("stop") }
        val job = launch { r.repo.refresh() }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(r.repo.state.value.refreshing)
    }

    @Test fun offlineFailsTheRestOfThePassAtOnceWithoutEscalatingBackoff() = runTest {
        val feeds = (1..6).map { feed("f$it") }
        val r = rig(this, feeds) { _, _ -> HttpResponse(0, failure = FetchResult.Err(ErrorKind.Network, "x"), offline = true) }
        r.repo.refresh()
        assertTrue("fetched ${r.fetcher.urls.size}", r.fetcher.urls.size < 6)
        val s = r.repo.state.value
        assertTrue(s.feedStates.all { it.status == FeedStatus.Error })
        assertTrue(s.feedStates.all { it.retryInMs == 0L })
        assertTrue(s.overall is NewsOverall.Error)
        assertEquals(ErrorKind.Network, (s.overall as NewsOverall.Error).kind)
        // the next pass tries again at the normal gap (nothing is backing off)
        val before = r.fetcher.urls.size
        r.clock.advance(61_000)
        r.repo.refresh()
        assertEquals(before + 1, r.fetcher.urls.size)
    }

    @Test fun onlyFailedRetriesJustTheFailingFeedsEvenInsideTheGapButNotInsideTheirBackoff() = runTest {
        var failA = true
        val r = rig(this, listOf(FEED_A, FEED_B)) { url, _ -> if (url.contains("/a.xml") && failA) HttpResponse(500, "") else ok(xml("S ${url.length}")) }
        r.repo.refresh()
        assertEquals(2, r.fetcher.urls.size)
        failA = false
        r.clock.advance(10_000)
        r.repo.refresh(onlyFailed = true)
        assertEquals("a server error backs the feed off: a signal does not override it", 2, r.fetcher.urls.size)
        r.clock.advance(51_000) // the one minute backoff is over, still far inside the 5 minute stale limit
        r.repo.refresh(onlyFailed = true)
        assertEquals(3, r.fetcher.urls.size)
        assertTrue(r.fetcher.urls.last().contains("/a.xml"))
        assertEquals(FeedStatus.Ok, r.repo.state.value.feedStates.first { it.id == "a" }.status)
    }

    // ---- overall status ------------------------------------------------------------------------------------------

    @Test fun freshTurnsStaleWithoutARefreshAndBackAfterOne() = runTest {
        val r = rig(this) { _, _ -> ok(xml("Story")) }
        r.repo.refresh()
        assertEquals(NewsOverall.Fresh, r.repo.state.value.overall)
        r.clock.advance(24 * 60_000L)
        r.repo.recompute()
        assertEquals(NewsOverall.Fresh, r.repo.state.value.overall)
        r.clock.advance(2 * 60_000L)
        r.repo.recompute()
        val o = r.repo.state.value.overall
        assertTrue("$o", o is NewsOverall.Stale)
        assertEquals(26 * 60_000L, (o as NewsOverall.Stale).ageMs)
        r.repo.refresh(force = true)
        assertEquals(NewsOverall.Fresh, r.repo.state.value.overall)
    }

    @Test fun anEmptyButHealthyFeedIsFreshNotAnError() = runTest {
        val r = rig(this) { _, _ -> ok("<rss version=\"2.0\"><channel><title>Empty</title></channel></rss>") }
        r.repo.refresh()
        assertEquals(NewsOverall.Fresh, r.repo.state.value.overall)
        assertTrue(r.repo.state.value.items.isEmpty())
    }

    @Test fun failingEverywhereWithHeldItemsIsStaleNotError() = runTest {
        var up = true
        val r = rig(this) { _, _ -> if (up) ok(xml("Held")) else HttpResponse(0, failure = FetchResult.Err(ErrorKind.Timeout, "t")) }
        r.repo.refresh()
        up = false
        r.clock.advance(40 * 60_000L)
        r.repo.refresh(force = true)
        val s = r.repo.state.value
        assertTrue(s.overall is NewsOverall.Stale)
        assertTrue(s.lastPassFailed)
        assertEquals(1, s.items.size)
        up = true
        r.repo.refresh(force = true)
        assertFalse(r.repo.state.value.lastPassFailed)
        assertEquals(NewsOverall.Fresh, r.repo.state.value.overall)
    }

    // ---- relevance, per-symbol feeds, configuration -------------------------------------------------------------

    @Test fun headlinesMentioningTheWatchlistAreTaggedAndRetaggedWhenItChanges() = runTest {
        val r = rig(this, watch = WatchlistStore.DEFAULTS) { _, _ ->
            ok(xml("Microsoft unveils new AI features", "TSX edges higher on energy", "Why XEQT keeps growing", "iShares Core Equity ETF draws inflows", "iShares adds new funds"))
        }
        r.repo.refresh()
        val tags = r.repo.state.value.items.associate { it.title to it.tags }
        assertEquals(listOf("MSFT"), tags["Microsoft unveils new AI features"])
        assertEquals(emptyList<String>(), tags["TSX edges higher on energy"])
        assertEquals(listOf("XEQT"), tags["Why XEQT keeps growing"])
        assertEquals(listOf("XEQT"), tags["iShares Core Equity ETF draws inflows"])
        assertEquals("a fund family name alone is not our fund", emptyList<String>(), tags["iShares adds new funds"])

        r.watch.value = listOf(WatchItem("SHOP.TO", "Shopify Inc.", "CAD", "TSX"))
        r.repo.onConfigChanged()
        assertTrue(r.repo.state.value.items.all { it.tags.isEmpty() })
    }

    @Test fun perSymbolFeedsAreFetchedForTheWatchlistAndTagTheirItems() = runTest {
        val r = rig(this, feeds = emptyList(), symbolFeeds = true, watch = WatchlistStore.DEFAULTS) { url, _ ->
            if (url.contains("s=MSFT")) ok(xml("Generic headline without the name")) else ok(xml("Quarterly numbers arrive"))
        }
        r.repo.refresh()
        assertEquals(setOf("XEQT.TO", "MSFT"), r.fetcher.urls.map { it.substringAfter("s=").substringBefore("&") }.toSet())
        val items = r.repo.state.value.items.associate { it.title to it.tags }
        assertEquals(listOf("MSFT"), items["Generic headline without the name"])
        assertEquals(listOf("XEQT"), items["Quarterly numbers arrive"])
        assertTrue(r.repo.state.value.feedStates.all { it.name == "Yahoo Finance" })
    }

    @Test fun aStoryThatNamesAnotherSymbolIsTaggedByItsTextNotByTheFeedItArrivedThrough() = runTest {
        val r = rig(this, feeds = emptyList(), symbolFeeds = true, watch = WatchlistStore.DEFAULTS) { url, _ ->
            if (url.contains("s=XEQT")) ok(xml("Microsoft unveils new AI features", "Quiet day for balanced funds")) else ok(xml("Nothing special today"))
        }
        r.repo.refresh()
        val tags = r.repo.state.value.items.associate { it.title to it.tags }
        assertEquals("text wins over the feed", listOf("MSFT"), tags["Microsoft unveils new AI features"])
        assertEquals("the feed fills in when the text names nothing", listOf("XEQT"), tags["Quiet day for balanced funds"])
        assertEquals(listOf("MSFT"), tags["Nothing special today"])
    }

    @Test fun removingASymbolDropsItsFeedItsTagsAndItsCache() = runTest {
        val r = rig(this, feeds = emptyList(), symbolFeeds = true, watch = WatchlistStore.DEFAULTS) { _, _ -> ok(xml("Something happened")) }
        r.repo.refresh()
        assertNotNull(r.cache.get("news.feed.sym:MSFT", allowExpired = true))
        r.watch.value = WatchlistStore.DEFAULTS.take(1)
        r.repo.onConfigChanged()
        assertNull(r.cache.get("news.feed.sym:MSFT", allowExpired = true))
        assertEquals(1, r.repo.state.value.feedStates.size)
        assertTrue(r.repo.state.value.items.all { "MSFT" !in it.tags })
    }

    @Test fun disablingOrRemovingAFeedRemovesItsItems() = runTest {
        val r = rig(this, listOf(FEED_A, FEED_B)) { url, _ -> if (url.contains("/a.xml")) ok(xml("From a")) else ok(xml("From b")) }
        r.repo.refresh()
        assertEquals(2, r.repo.state.value.items.size)
        r.feeds.feedFlow.value = listOf(FEED_A)
        r.repo.onConfigChanged()
        assertEquals(listOf("From a"), r.repo.state.value.items.map { it.title })
        assertNull(r.cache.get("news.feed.b", allowExpired = true))
    }

    @Test fun summariesAreCappedAndPlain() = runTest {
        val html = "&lt;p&gt;" + "long sentence about markets ".repeat(40) + "&lt;/p&gt;&lt;script&gt;x()&lt;/script&gt;"
        val r = rig(this) { _, _ -> ok(rssXml(Quad("T", "https://e.com/t", rfc(NOW_MS), html))) }
        r.repo.refresh()
        val h = r.repo.state.value.items.single()
        assertTrue(h.summary.length <= 280)
        assertFalse(h.summary.contains('<') || h.summary.contains("script"))
    }

    // ---- clocks and status ---------------------------------------------------------------------------------------------

    @Test fun aCacheWrittenByAClockAheadOfTheDeviceIsStaleWithAnUnknownAge() = runTest {
        val r = rig(this) { _, _ -> ok(xml("Cached story")) }
        r.repo.refresh()
        // The unit boots with a wrong clock: three days behind what the cache entry says.
        val behind = FakeClock(now = NOW_MS - 3 * 86_400_000L)
        val second = NewsRepository(r.feeds, r.watch, DelayFetcher(0) { _, _ -> error("offline") }, FileJsonCache(File(r.dir, "cache"), behind), behind, NewsConfig(), r.io)
        second.hydrate()
        val s = second.state.value
        assertEquals(1, s.items.size)
        val o = s.overall
        assertTrue("$o", o is NewsOverall.Stale && !o.ageKnown)
        assertNull("never 'Updated just now'", s.lastOkAgeMs)
    }

    @Test fun aFailedPassNeverMakesCachedHeadlinesFresh() = runTest {
        val r = rig(this) { _, _ -> ok(xml("Cached story")) }
        r.repo.refresh()
        val failing = r.again(DelayFetcher(0) { _, _ -> HttpResponse(500, "") })
        failing.hydrate()
        r.clock.advance(5 * 60_000L) // only five minutes old, well inside the fresh window
        assertTrue(failing.state.value.overall is NewsOverall.Stale)
        failing.refresh(force = true)
        val o = failing.state.value.overall
        assertTrue("a failed pass is not a confirmation: $o", o is NewsOverall.Stale)
        assertTrue(failing.state.value.lastPassFailed)
    }

    @Test fun theClockOffsetIsTheMedianAcrossFeedsSoOneBadServerCannotMoveIt() = runTest {
        val r = rig(this, listOf(FEED_A, FEED_B, feed("c"))) { url, _ ->
            val date = if (url.contains("/c.xml")) NOW_MS + 3 * 86_400_000L else NOW_MS
            ok(xml("Story ${url.substringAfterLast('/')}"), date = date)
        }
        r.repo.refresh()
        assertEquals(0L, r.repo.state.value.serverOffsetMs)
        assertEquals(3, r.repo.state.value.items.size)
        assertTrue(r.repo.state.value.items.none { it.dateEstimated })
    }

    @Test fun aCachedResponseDateIsCorrectedByItsAgeHeader() = runTest {
        // The CDN built this response 10 minutes ago and says so: the device clock is right.
        val r = rig(this) { _, _ -> HttpResponse(200, xml("Story"), mapOf("date" to rfc(NOW_MS - 600_000L), "age" to "600")) }
        r.repo.refresh()
        assertEquals(0L, r.repo.state.value.serverOffsetMs)
        val r2 = rig(this) { _, _ -> ok(xml("Story"), date = NOW_MS - 600_000L) }
        r2.repo.refresh()
        assertEquals("without the Age header the same Date is a real 10 minute skew", -600_000L, r2.repo.state.value.serverOffsetMs)
    }

    @Test fun smallSkewUnderFiveMinutesIsIgnored() = runTest {
        val r = rig(this) { _, _ -> ok(xml("One"), date = NOW_MS + 4 * 60_000L) }
        r.repo.refresh()
        assertEquals(0L, r.repo.state.value.serverOffsetMs)
    }

    // ---- backoff by kind, banner cause ------------------------------------------------------------------------------------

    @Test fun timeoutsAndNetworkErrorsNeverBackOffBeyondOnePollPeriod() = runTest {
        val r = rig(this) { _, _ -> HttpResponse(0, failure = FetchResult.Err(ErrorKind.Timeout, "t")) }
        repeat(8) {
            r.repo.refresh(force = true)
            r.clock.advance(1_000)
        }
        assertEquals(10 * 60_000L, r.repo.state.value.feedStates.single().retryInMs)
        val h = rig(this) { _, _ -> HttpResponse(500, "") }
        repeat(8) { h.repo.refresh(force = true); h.clock.advance(1_000) }
        assertEquals("server errors still grow to 30 min", 30 * 60_000L, h.repo.state.value.feedStates.single().retryInMs)
    }

    @Test fun theStateCarriesTheKindOfFailureMostFeedsShare() = runTest {
        val r = rig(this, listOf(FEED_A, FEED_B, feed("c"))) { url, _ ->
            if (url.contains("/c.xml")) HttpResponse(500, "") else HttpResponse(0, failure = FetchResult.Err(ErrorKind.Timeout, "t"))
        }
        r.repo.refresh()
        assertEquals(ErrorKind.Timeout, r.repo.state.value.errorKind)
        assertEquals(ErrorKind.ClockOrTls, dominantKind(listOf(ErrorKind.ClockOrTls, ErrorKind.Network)))
        assertEquals(ErrorKind.Network, dominantKind(listOf(ErrorKind.Network, ErrorKind.Network, ErrorKind.Parse)))
        assertNull(dominantKind(emptyList()))
        val fine = rig(this) { _, _ -> ok(xml("Fine")) }
        fine.repo.refresh()
        assertNull(fine.repo.state.value.errorKind)
    }
}
