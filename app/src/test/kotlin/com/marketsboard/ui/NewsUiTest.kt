package com.marketsboard.ui

import com.marketsboard.core.NewsText
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.news.Headline
import com.marketsboard.data.news.NewsOverall
import com.marketsboard.data.news.NewsSource
import com.marketsboard.data.news.NewsState
import com.marketsboard.data.news.ms
import com.marketsboard.refresh.NewsRefresher
import com.marketsboard.ui.news.NewsFilter
import com.marketsboard.ui.news.NewsMapper
import com.marketsboard.ui.news.NewsPhase
import com.marketsboard.ui.news.NewsPresenter
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val NOW = ms("2026-11-16T18:00:00Z")
private val TORONTO = ZoneId.of("America/Toronto")

private fun hl(
    id: String, title: String, ageMin: Long, tags: List<String> = emptyList(), summary: String = "Summary of $title.", est: Boolean = false, source: String = "CBC Business",
) = Headline(id, title, summary, "https://e.com/$id", source, NOW - ageMin * 60_000L, tags, dateEstimated = est)

private val ITEMS = listOf(
    hl("a", "Microsoft unveils AI features", 5, listOf("MSFT")),
    hl("b", "TSX edges higher", 90),
    hl("c", "Why XEQT keeps growing", 200, listOf("XEQT", "MSFT", "SHOP")),
    hl("d", "Oil slips", 3000),
    hl("e", "Bank of Canada holds rate", 4000),
)

class NewsMapperTest {
    private fun map(s: NewsState, f: NewsFilter = NewsFilter.All, open: String? = null, mode: UiMode = UiMode.Normal) = NewsMapper.map(s, f, open, mode, NOW, TORONTO)
    private val fresh = NewsState(ITEMS, overall = NewsOverall.Fresh, lastOkAgeMs = 120_000)

    @Test fun readyRowsCarrySourceAgeTitleSummaryAndTags() {
        val u = map(fresh)
        assertEquals(NewsPhase.Ready, u.phase)
        assertEquals(5, u.rows.size)
        val r = u.rows[0]
        assertEquals("CBC BUSINESS", r.source)
        assertEquals("5m ago", r.ago)
        assertEquals("Microsoft unveils AI features", r.title)
        assertEquals("Summary of Microsoft unveils AI features.", r.summary)
        assertEquals(listOf("MSFT"), r.tags)
        assertEquals("1h ago", u.rows[1].ago)
        assertEquals("3h ago", u.rows[2].ago)
        assertEquals("2d ago", u.rows[3].ago)
        assertEquals(listOf("XEQT", "MSFT"), u.rows[2].tags) // at most two tags per row
        assertEquals("Updated 2 min ago", u.statusLine)
        assertEquals("", u.banner)
        assertFalse(u.stale)
    }

    @Test fun mySymbolsFilterKeepsOnlyTaggedHeadlines() {
        val u = map(fresh, NewsFilter.MySymbols)
        assertEquals(listOf("a", "c"), u.rows.map { it.id })
        assertEquals(NewsFilter.MySymbols, u.filter)
    }

    @Test fun mySymbolsWithNothingTaggedIsACalmEmptyStateWithAWayBack() {
        val s = fresh.copy(items = ITEMS.map { it.copy(tags = emptyList()) })
        val u = map(s, NewsFilter.MySymbols)
        assertEquals(NewsPhase.Empty, u.phase)
        assertEquals("No news on your symbols", u.emptyTitle)
        assertTrue(u.emptyHint.contains("All"))
    }

    @Test fun noItemsAtAllIsAnEmptyStateAndLoadingIsLoading() {
        val empty = map(NewsState(overall = NewsOverall.Fresh, lastOkAgeMs = 1000))
        assertEquals(NewsPhase.Empty, empty.phase)
        assertEquals("No headlines yet", empty.emptyTitle)
        assertEquals(NewsPhase.Loading, map(NewsState()).phase)
    }

    @Test fun errorWithNothingToShowGivesTheErrorPhaseAndAReason() {
        val u = map(NewsState(overall = NewsOverall.Error(ErrorKind.Network), lastPassFailed = true))
        assertEquals(NewsPhase.Error, u.phase)
        assertEquals("Can't reach the news sources", u.banner)
    }

    @Test fun staleListShowsAmberStatusAndABanner() {
        val s = fresh.copy(overall = NewsOverall.Stale(40 * 60_000L), lastOkAgeMs = 40 * 60_000L)
        val u = map(s)
        assertEquals(NewsPhase.Ready, u.phase)
        assertTrue(u.stale)
        assertEquals("Updated 40 min ago", u.statusLine)
        assertEquals("Headlines may be out of date", u.banner)
    }

    @Test fun aFailedPassWithItemsHeldSaysWhyEvenWhileStillFresh() {
        val u = map(fresh.copy(lastPassFailed = true))
        assertEquals("Can't reach the news sources", u.banner)
        assertTrue(u.stale)
    }

    @Test fun refreshingIsPassedThrough() {
        assertTrue(map(fresh.copy(refreshing = true)).refreshing)
    }

    @Test fun glanceShowsTheTopThreeTitlesOnlyAndNothingToTap() {
        val u = map(fresh.copy(lastPassFailed = true, overall = NewsOverall.Stale(1_800_000)), NewsFilter.MySymbols, open = "a", mode = UiMode.Glance)
        assertEquals(listOf("a", "b", "c"), u.rows.map { it.id })
        assertTrue(u.rows.all { it.summary.isEmpty() && it.tags.isEmpty() })
        assertNull(u.detail)
        assertEquals("", u.banner)
        assertEquals(NewsFilter.All, u.filter)
        assertEquals(UiMode.Glance, u.mode)
    }

    @Test fun detailHasThePublishedTimeInTheDeviceZone() {
        val u = map(fresh, open = "c")
        val d = u.detail!!
        assertEquals("Why XEQT keeps growing", d.title)
        assertEquals("CBC BUSINESS", d.source)
        assertEquals("3h ago", d.ago)
        // 18:00 UTC is 1:00 PM in Toronto (EST); 200 minutes earlier is 9:40 AM.
        assertEquals("Nov 16, 9:40 AM", d.published)
        assertEquals("https://e.com/c", d.url)
        assertEquals(listOf("XEQT", "MSFT", "SHOP"), d.tags)
    }

    @Test fun detailOfAnEstimatedDateHasNoTimestampAndAMissingItemHasNoDetail() {
        val s = fresh.copy(items = listOf(hl("z", "Undated", 10, est = true)))
        assertEquals("", map(s, open = "z").detail!!.published)
        assertNull(map(fresh, open = "nope").detail)
    }

    @Test fun futureOrNegativeAgesReadNow() {
        val s = fresh.copy(items = listOf(hl("f", "From the future", -30)))
        assertEquals("now", map(s).rows.single().ago)
    }
}

class NewsTextTest {
    @Test fun agoBoundaries() {
        assertEquals("now", NewsText.ago(-5))
        assertEquals("now", NewsText.ago(59_999))
        assertEquals("1m ago", NewsText.ago(60_000))
        assertEquals("59m ago", NewsText.ago(59 * 60_000L + 59_000))
        assertEquals("1h ago", NewsText.ago(3_600_000))
        assertEquals("23h ago", NewsText.ago(23 * 3_600_000L + 3_599_000))
        assertEquals("1d ago", NewsText.ago(86_400_000))
        assertEquals("6d ago", NewsText.ago(6 * 86_400_000L + 5))
    }

    @Test fun updatedBoundaries() {
        assertEquals("Updated just now", NewsText.updated(0))
        assertEquals("Updated just now", NewsText.updated(59_000))
        assertEquals("Updated 1 min ago", NewsText.updated(60_000))
        assertEquals("Updated 59 min ago", NewsText.updated(59 * 60_000L))
        assertEquals("Updated 1 h ago", NewsText.updated(60 * 60_000L))
        assertEquals("Updated 2 d ago", NewsText.updated(2 * 86_400_000L))
    }

    @Test fun wordingIsPlainAndFreeOfEmDashes() {
        val kinds = listOf(ErrorKind.Network, ErrorKind.Timeout, ErrorKind.RateLimited(1), ErrorKind.NotFound, ErrorKind.Parse, ErrorKind.Http(502), ErrorKind.Unknown, ErrorKind.ClockOrTls)
        for (k in kinds) {
            val t = NewsText.feedError(k)
            assertTrue(t.isNotBlank())
            assertFalse(t.contains(Char(0x2014)))
        }
        assertEquals("The news source has a problem (502)", NewsText.feedError(ErrorKind.Http(502)))
        assertEquals("CBC", NewsText.sourceLabel(" cbc "))
        assertEquals("NEWS", NewsText.sourceLabel("  "))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NewsPresenterTest {
    private class FakeSource(items: List<Headline> = ITEMS) : NewsSource {
        val flow = MutableStateFlow(NewsState(items, overall = NewsOverall.Fresh, lastOkAgeMs = 0))
        var now = NOW
        override val state: StateFlow<NewsState> = flow
        override fun correctedNowMs(): Long = now
    }

    private class Spy : NewsRefresher {
        var n = 0
        override fun refreshNow() { n++ }
    }

    @Test fun openFilterBackAndRefreshFlowThroughState() = runTest {
        val spy = Spy()
        val p = NewsPresenter(backgroundScope, FakeSource(), spy, zone = TORONTO, tick = MutableSharedFlow())
        runCurrent()
        assertEquals(5, p.state.value.rows.size)
        p.onSetFilter(NewsFilter.MySymbols)
        runCurrent()
        assertEquals(listOf("a", "c"), p.state.value.rows.map { it.id })
        p.onOpen("c")
        runCurrent()
        assertEquals("c", p.state.value.detail?.id)
        assertTrue(p.state.value.canGoBack)
        p.onBack()
        runCurrent()
        assertNull(p.state.value.detail)
        assertEquals("filter survives the detail", NewsFilter.MySymbols, p.state.value.filter)
        p.onRefresh(); p.onRetry()
        assertEquals(2, spy.n)
    }

    @Test fun glanceIgnoresOpenAndFilterAndHidesAnOpenArticle() = runTest {
        val mode = MutableStateFlow(UiMode.Normal)
        val p = NewsPresenter(backgroundScope, FakeSource(), mode = mode, zone = TORONTO, tick = MutableSharedFlow())
        p.onOpen("a")
        runCurrent()
        assertEquals("a", p.state.value.detail?.id)
        mode.value = UiMode.Glance
        runCurrent()
        assertNull("the article is not shown in glance", p.state.value.detail)
        p.onOpen("b"); p.onSetFilter(NewsFilter.MySymbols)
        runCurrent()
        mode.value = UiMode.Normal
        runCurrent()
        assertNull("entering glance closed the article for good", p.state.value.detail)
        assertEquals(NewsFilter.All, p.state.value.filter)
    }

    @Test fun agesMoveWithTheTickEvenWhenTheDataDoesNot() = runTest {
        val src = FakeSource()
        val tick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val p = NewsPresenter(backgroundScope, src, zone = TORONTO, tick = tick)
        runCurrent()
        assertEquals("5m ago", p.state.value.rows[0].ago)
        src.now += 3_600_000L
        tick.tryEmit(Unit)
        runCurrent()
        assertEquals("1h ago", p.state.value.rows[0].ago)
    }

    @Test fun stateFollowsTheRepository() = runTest {
        val src = FakeSource(emptyList())
        val p = NewsPresenter(backgroundScope, src, zone = TORONTO, tick = MutableSharedFlow())
        runCurrent()
        assertEquals(NewsPhase.Empty, p.state.value.phase)
        src.flow.value = NewsState(ITEMS, overall = NewsOverall.Fresh, lastOkAgeMs = 0)
        runCurrent()
        assertEquals(NewsPhase.Ready, p.state.value.phase)
    }
}
