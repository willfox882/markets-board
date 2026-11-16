package com.marketsboard.sandbox

import com.marketsboard.data.Clock
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.news.DefaultFeeds
import com.marketsboard.data.news.FeedParser
import com.marketsboard.data.news.FeedPlan
import com.marketsboard.data.news.ParsedFeed
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fake news servers must serve documents the real parser accepts, or the sandbox would prove nothing. */
class FakeFeedFetcherTest {
    private val now = ScreenshotFixtures.NOW
    private fun fetcher(b: FakeNewsBehavior = FakeNewsBehavior.Normal) = FakeFeedFetcher(Clock { now }, b, slowDelayMs = 50)

    private fun parse(url: String, f: FakeFeedFetcher = fetcher()): ParsedFeed {
        val r = runBlocking { f.get(url) }
        assertEquals(url, 200, r.code)
        val p = FeedParser.parse(r.bytes ?: error("no bytes"), now)
        assertTrue("$url: $p", p is FetchResult.Ok)
        return (p as FetchResult.Ok).value
    }

    @Test fun everyDefaultFeedParsesWithPlentyOfItems() {
        for (f in DefaultFeeds.ALL) {
            val p = parse(f.url)
            assertTrue("${f.id} has ${p.items.size}", p.items.size >= 6)
            assertEquals(0, p.dropped)
            assertFalse(p.partial)
            assertTrue(p.items.all { it.publishedMs != null && !it.dateReplaced })
        }
    }

    @Test fun formatsAreVariedRssAndAtom() {
        assertEquals("rss", parse(DefaultFeeds.ALL[0].url).format)
        assertEquals("atom", parse(DefaultFeeds.ALL[1].url).format)
    }

    @Test fun summariesAreHtmlThatTheParserTurnsIntoPlainText() {
        val items = DefaultFeeds.ALL.flatMap { parse(it.url).items }
        assertTrue(items.any { it.summary.isNotEmpty() })
        assertTrue(items.none { it.summary.contains('<') || it.summary.contains("&#") || it.summary.contains("&amp;") })
        assertTrue(items.any { it.title.contains("Rogers & Bell") })
        assertTrue(items.any { it.summary.contains("“limited-time”") })
    }

    @Test fun symbolFeedsCarryTheWatchlistStories() {
        val msft = parse(FeedPlan.symbolFeedUrl("MSFT")).items.map { it.title }
        assertTrue(msft.toString(), msft.any { it.contains("Microsoft") })
        val xeqt = parse(FeedPlan.symbolFeedUrl("XEQT.TO")).items.map { it.title }
        assertTrue(xeqt.toString(), xeqt.any { it.contains("XEQT") || it.contains("iShares") })
    }

    @Test fun storiesOverlapAcrossFeedsSoDedupeHasWork() {
        val titles = DefaultFeeds.ALL.flatMap { parse(it.url).items.map { i -> i.title } }
        assertTrue(titles.count { it.startsWith("Microsoft stock slides") } >= 1)
        val urls = (DefaultFeeds.ALL.map { it.url } + FeedPlan.symbolFeedUrl("MSFT")).flatMap { parse(it).items.map { i -> i.url } }
        assertTrue("some URLs repeat with tracking parameters", urls.size > urls.toSet().size || urls.any { it.contains("guccounter") })
    }

    @Test fun anEtagMakesTheSecondRequestA304AndABreakingHeadlineChangesIt() = runBlocking {
        val f = fetcher()
        val url = DefaultFeeds.ALL[0].url
        val first = f.get(url)
        val etag = first.headers["etag"]
        assertNotNull(etag)
        val second = f.get(url, mapOf("If-None-Match" to etag!!))
        assertEquals(304, second.code)
        assertEquals(1, f.notModified)
        val title = f.addBreaking()
        val third = f.get(url, mapOf("If-None-Match" to etag))
        assertEquals(200, third.code)
        val items = (FeedParser.parse(third.bytes!!, now) as FetchResult.Ok).value.items
        assertEquals(title, items.first().title)
        assertTrue("fresh timestamp", now - items.first().publishedMs!! < 300_000L)
        Unit
    }

    @Test fun offlineIsAnOfflineTransportFailure() = runBlocking {
        val r = fetcher(FakeNewsBehavior.Offline).get(DefaultFeeds.ALL[0].url)
        assertEquals(0, r.code)
        assertTrue(r.offline)
        assertNotNull(r.failure)
        Unit
    }

    @Test fun oneFeedFailingFailsOnlyCbc() = runBlocking {
        val f = fetcher(FakeNewsBehavior.OneFeedFailing)
        assertEquals(503, f.get(DefaultFeeds.ALL[0].url).code)
        assertEquals(200, f.get(DefaultFeeds.ALL[1].url).code)
        Unit
    }

    @Test fun malformedFeedsGiveALoginPageAndATruncatedAtomFeedThatIsPartlyReadable() = runBlocking {
        val f = fetcher(FakeNewsBehavior.MalformedFeeds)
        val html = f.get(DefaultFeeds.ALL[2].url)
        assertTrue(FeedParser.parse(html.bytes ?: html.body.toByteArray(), now) is FetchResult.Err)
        val globe = f.get(DefaultFeeds.ALL[1].url)
        val p = (FeedParser.parse(globe.body.toByteArray(), now) as FetchResult.Ok).value
        assertTrue(p.partial)
        assertTrue("salvaged ${p.items.size}", p.items.size in 3..5)
        Unit
    }

    @Test fun slowWaitsBeforeAnswering() = runBlocking {
        val t0 = System.nanoTime()
        fetcher(FakeNewsBehavior.Slow).get(DefaultFeeds.ALL[0].url)
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 45)
    }

    @Test fun unknownHostsAre404() = runBlocking {
        assertEquals(404, fetcher().get("https://nowhere.example.org/feed").code)
        Unit
    }
}
