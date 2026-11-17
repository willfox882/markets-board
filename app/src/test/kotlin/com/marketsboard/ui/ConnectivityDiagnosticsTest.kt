package com.marketsboard.ui

import com.marketsboard.FakeNetwork
import com.marketsboard.FakeQuotesSource
import com.marketsboard.VirtualClock
import com.marketsboard.core.DrivingReason
import com.marketsboard.core.DrivingStatus
import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.net.FetchEvent
import com.marketsboard.data.news.FeedState
import com.marketsboard.data.news.FeedStatus
import com.marketsboard.data.news.NewsOverall
import com.marketsboard.data.news.NewsState
import com.marketsboard.data.news.FeedConfig
import com.marketsboard.data.repo.ProviderHealth
import com.marketsboard.data.repo.QuoteRepository
import com.marketsboard.data.repo.QuoteState
import com.marketsboard.data.repo.QuoteStatus
import com.marketsboard.data.quoteOf
import com.marketsboard.data.store.WatchlistStore
import com.marketsboard.refresh.Connectivity
import com.marketsboard.refresh.ConnectivityRules
import com.marketsboard.refresh.NetworkStatus
import com.marketsboard.ui.markets.HeaderUi
import com.marketsboard.ui.markets.LiveKind
import com.marketsboard.ui.markets.MarketsUiState
import com.marketsboard.ui.markets.QuoteRowUi
import com.marketsboard.ui.markets.RowStatus
import com.marketsboard.ui.news.NewsUiState
import com.marketsboard.ui.tools.DiagText
import com.marketsboard.ui.tools.DiagnosticsInput
import com.marketsboard.ui.tools.DiagnosticsMapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityDiagnosticsTest {
    private val symbols = listOf("XEQT.TO", "MSFT")
    private fun err(k: ErrorKind) = QuoteState(null, QuoteStatus.Error(k), null, FetchResult.Err(k, "x"))
    private fun allFailed(k: ErrorKind) = symbols.associate { QuoteRepository.keyFor(it, ChartRange.D1) to err(k) }
    private fun fresh(sym: String) = QuoteState(quoteOf(sym), QuoteStatus.Fresh, 1_000L)

    // ---- the offline decision ------------------------------------------------------------------------------------------

    @Test fun noNetworkMeansOfflineWhateverTheDataSays() {
        assertTrue(ConnectivityRules.offline(NetworkStatus.Offline, NewsState(), emptyMap(), symbols))
    }

    @Test fun unknownOrOnlineAloneIsNeverOffline() {
        for (s in listOf(NetworkStatus.Unknown, NetworkStatus.Online)) assertFalse(ConnectivityRules.offline(s, NewsState(), emptyMap(), symbols))
    }

    @Test fun everySymbolFailingForANetworkReasonIsOfflineButOneIsNot() {
        assertTrue(ConnectivityRules.offline(NetworkStatus.Online, NewsState(), allFailed(ErrorKind.Network), symbols))
        // timeouts alone say a host is slow, not that the network is gone
        assertFalse(ConnectivityRules.offline(NetworkStatus.Unknown, NewsState(), allFailed(ErrorKind.Timeout), symbols))
        val oneOk = allFailed(ErrorKind.Network) + (QuoteRepository.keyFor("MSFT", ChartRange.D1) to fresh("MSFT"))
        assertFalse(ConnectivityRules.offline(NetworkStatus.Online, NewsState(), oneOk, symbols))
        // a server problem is not "offline"
        assertFalse(ConnectivityRules.offline(NetworkStatus.Online, NewsState(), allFailed(ErrorKind.Http(503)), symbols))
        // symbols not loaded yet: not offline
        assertFalse(ConnectivityRules.offline(NetworkStatus.Online, NewsState(), mapOf(QuoteRepository.keyFor("MSFT", ChartRange.D1) to err(ErrorKind.Network)), symbols))
    }

    private fun feedState(id: String, status: FeedStatus, kind: ErrorKind? = null) = FeedState(id, id, status, errorKind = kind)

    @Test fun everyNewsFeedFailingForANetworkReasonIsNewsDownPerFeedSoARecoveredFeedClearsIt() {
        val down = NewsState(overall = NewsOverall.Stale(60_000), feedStates = listOf(feedState("a", FeedStatus.Error, ErrorKind.Network), feedState("b", FeedStatus.Error, ErrorKind.Network)))
        assertTrue(ConnectivityRules.newsDown(down))
        assertTrue(ConnectivityRules.offline(NetworkStatus.Online, down, emptyMap(), symbols))
        // one feed answers again (or is only slow, or has a server problem): not down any more, whatever lastPassFailed still says
        val recovered = down.copy(lastPassFailed = true, feedStates = listOf(feedState("a", FeedStatus.Ok), feedState("b", FeedStatus.Error, ErrorKind.Network)))
        assertFalse(ConnectivityRules.newsDown(recovered))
        assertFalse(ConnectivityRules.newsDown(down.copy(feedStates = listOf(feedState("a", FeedStatus.Error, ErrorKind.Timeout), feedState("b", FeedStatus.Error, ErrorKind.Network)))))
        assertFalse(ConnectivityRules.newsDown(down.copy(feedStates = listOf(feedState("a", FeedStatus.Error, ErrorKind.Http(503)), feedState("b", FeedStatus.Error, ErrorKind.Network)))))
        assertFalse(ConnectivityRules.newsDown(down.copy(feedStates = emptyList())))
        assertFalse(ConnectivityRules.newsDown(down.copy(overall = NewsOverall.Loading)))
        assertFalse(ConnectivityRules.newsDown(down.copy(feedStates = listOf(feedState("a", FeedStatus.Pending), feedState("b", FeedStatus.Error, ErrorKind.Network)))))
    }

    @Test fun aNewsOnlyFailureDoesNotMakeTheMarketsOfflineButANoNetworkSignalDoes() {
        val down = NewsState(overall = NewsOverall.Stale(60_000), feedStates = listOf(feedState("a", FeedStatus.Error, ErrorKind.Network)))
        val healthy = symbols.associate { QuoteRepository.keyFor(it, ChartRange.D1) to fresh(it) }
        assertTrue(ConnectivityRules.offline(NetworkStatus.Online, down, healthy, symbols))
        assertFalse("Markets keep their live look while only the headlines fail", ConnectivityRules.marketsOffline(NetworkStatus.Online, healthy, symbols))
        assertTrue(ConnectivityRules.marketsOffline(NetworkStatus.Offline, healthy, symbols))
        assertTrue(ConnectivityRules.marketsOffline(NetworkStatus.Online, allFailed(ErrorKind.Network), symbols))
        assertFalse(ConnectivityRules.marketsOffline(NetworkStatus.Online, allFailed(ErrorKind.Timeout), symbols))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun theFlagFollowsTheSignalAndClearsWhenTheNetworkReturns() = runTest {
        val net = FakeNetwork()
        net.set(NetworkStatus.Online)
        val quotes = FakeQuotesSource(VirtualClock(testScheduler))
        val news = object : com.marketsboard.data.news.NewsSource {
            override val state = MutableStateFlow(NewsState())
            override fun correctedNowMs() = 0L
        }
        val c = Connectivity(backgroundScope, net, news, quotes, MutableStateFlow(symbols))
        runCurrent()
        assertFalse(c.offline.value)
        net.set(NetworkStatus.Offline)
        runCurrent()
        assertTrue(c.offline.value)
        net.set(NetworkStatus.Online)
        runCurrent()
        assertFalse(c.offline.value)
        quotes.putAll(allFailed(ErrorKind.Network))
        runCurrent()
        assertTrue(c.offline.value)
        assertTrue(c.marketsOffline.value)
        quotes.putAll(mapOf("XEQT.TO" to fresh("XEQT.TO")))
        runCurrent()
        assertFalse(c.offline.value)
        assertFalse(c.marketsOffline.value)
        // headlines alone failing: the app is "offline" for News and Settings, the Markets are not
        news.state.value = NewsState(overall = NewsOverall.Stale(1), feedStates = listOf(feedState("a", FeedStatus.Error, ErrorKind.Network)))
        runCurrent()
        assertTrue(c.offline.value)
        assertFalse(c.marketsOffline.value)
        news.state.value = NewsState(overall = NewsOverall.Fresh, feedStates = listOf(feedState("a", FeedStatus.Ok)))
        runCurrent()
        assertFalse(c.offline.value)
    }

    // ---- what offline looks like ---------------------------------------------------------------------------------------

    private fun row(sym: String, status: RowStatus, price: Double? = 10.0) = QuoteRowUi(sym, sym, "TSX", "CAD", price, 9.0, emptyList(), 2, status)

    @Test fun offlineViewMakesOneCalmStateOutOfTheErrorsAndSpinners() {
        val s = MarketsUiState(
            listOf(row("A", RowStatus.Error("Can't reach the data service", "5 min ago", 300_000)), row("B", RowStatus.Fresh), row("C", RowStatus.Error("x"), null)),
            header = HeaderUi(LiveKind.Live, "Live", "Updated 12:41 PM ET"), refreshing = true,
        )
        val o = OfflineView.markets(s)
        assertEquals(LiveKind.Offline, o.header.kind)
        assertEquals("Offline", o.header.label)
        assertEquals("Updated 12:41 PM ET", o.header.updated)
        assertEquals(RowStatus.Stale("5 min ago", 300_000), o.quotes[0].status)
        assertEquals(RowStatus.Fresh, o.quotes[1].status)
        assertTrue(o.quotes[2].status is RowStatus.Error) // nothing held: the row still says why
        assertFalse(o.refreshing)
        val n = OfflineView.news(NewsUiState(banner = "Can't reach the news sources", refreshing = true))
        assertEquals("", n.banner)
        assertFalse(n.refreshing)
        assertEquals("Offline, showing last data", OfflineView.BANNER)
    }

    @Test fun anEmptyWatchlistKeepsItsHeader() {
        val s = MarketsUiState(emptyList())
        assertEquals(s.header, OfflineView.markets(s).header)
    }

    // ---- diagnostics ---------------------------------------------------------------------------------------------------

    private fun lines(sec: String, item: Int = 0, i: DiagnosticsInput) = DiagnosticsMapper.map(i).sections.first { it.title == sec }.items[item].lines.associate { it.label to it }

    @Test fun ageAndDurationWords() {
        assertEquals("Never", DiagText.age(null))
        assertEquals("Just now", DiagText.age(2_000))
        assertEquals("40 s ago", DiagText.age(40_000))
        assertEquals("5 min ago", DiagText.age(5 * 60_000))
        assertEquals("3 h ago", DiagText.age(3 * 3_600_000L))
        assertEquals("2 d ago", DiagText.age(2 * 86_400_000L))
        assertEquals("45 s", DiagText.duration(45_000))
        assertEquals("2 min", DiagText.duration(61_000))
        assertEquals("3 h", DiagText.duration(2 * 3_600_000L + 1))
        assertEquals("under a minute", DiagText.skew(20_000))
        assertEquals("45 min", DiagText.skew(-45 * 60_000L))
        assertEquals("3 h", DiagText.skew(3 * 3_600_000L))
    }

    @Test fun connectionSectionSaysNetworkDataAndClock() {
        val ok = lines("Connection", i = DiagnosticsInput(signal = NetworkStatus.Online))
        assertEquals("Connected", ok.getValue("Network").value)
        assertEquals("Live", ok.getValue("Data").value)
        assertEquals("Looks right", ok.getValue("Device clock").value)
        val bad = lines("Connection", i = DiagnosticsInput(signal = NetworkStatus.Offline, offline = true, news = NewsState(serverOffsetMs = 3 * 3_600_000L)))
        assertEquals("No network", bad.getValue("Network").value)
        assertTrue(bad.getValue("Network").warn)
        assertEquals("Offline, showing last data", bad.getValue("Data").value)
        assertEquals("3 h behind, check the date and time", bad.getValue("Device clock").value)
        val skewedQuotes = lines("Connection", i = DiagnosticsInput(quotes = mapOf("MSFT" to fresh("MSFT").copy(clockSkew = true))))
        assertTrue(skewedQuotes.getValue("Device clock").warn)
        val ahead = lines("Connection", i = DiagnosticsInput(news = NewsState(serverOffsetMs = -2 * 3_600_000L)))
        assertTrue(ahead.getValue("Device clock").value.startsWith("2 h ahead"))
    }

    @Test fun providersShowSuccessErrorCooldownAndTheLastRequestHostOnly() {
        val health = listOf(
            ProviderHealth("yahoo", lastOkAgeMs = 3 * 60_000L, lastError = "The data service has a problem (503)", lastErrorAgeMs = 60_000L, failureStreak = 2, cooldownRemainingMs = 90_000L, okCount = 12, errorCount = 2),
            ProviderHealth("stooq"),
        )
        val fetches = listOf(FetchEvent(1, "yahoo", "query1.finance.yahoo.com", false, 503, 240, "HTTP 503"))
        val inp = DiagnosticsInput(providers = health, fetches = fetches)
        val y = lines("Prices", 0, inp)
        assertEquals("3 min ago", y.getValue("Last success").value)
        assertEquals("The data service has a problem (503), 1 min ago", y.getValue("Last error").value)
        assertEquals("12 good, 2 failed", y.getValue("Answers").value)
        assertEquals("Resting 2 min after 2 failures", y.getValue("Pause").value)
        assertTrue(y.getValue("Pause").warn)
        assertEquals("query1.finance.yahoo.com, 240 ms, failed", y.getValue("Last request").value)
        val st = lines("Prices", 1, inp)
        assertEquals("Never", st.getValue("Last success").value)
        assertEquals("None", st.getValue("Last error").value)
        assertEquals("Ready", st.getValue("Pause").value)
        assertEquals("Yahoo Finance", DiagnosticsMapper.map(inp).sections[1].items[0].heading)
        assertEquals("Stooq", DiagnosticsMapper.map(inp).sections[1].items[1].heading)
    }

    @Test fun watchlistCacheShowsAgePerSymbol() {
        val stale = QuoteState(quoteOf("MSFT"), QuoteStatus.Stale(7 * 60_000L), 0L)
        val i = DiagnosticsInput(
            watch = listOf(WatchItem("XEQT.TO", "XEQT", "CAD", "TSX"), WatchItem("MSFT", "Microsoft", "USD", "NASDAQ"), WatchItem("AAPL", "Apple", "USD", "NASDAQ")),
            quotes = mapOf("XEQT.TO" to fresh("XEQT.TO").copy(lastSuccessMs = 100_000L), "MSFT" to stale),
            wallNowMs = 100_000L + 3 * 60_000L,
        )
        val l = lines("Watchlist cache", i = i)
        assertEquals("Fresh, 3 min ago", l.getValue("XEQT.TO").value)
        assertEquals("Held, 7 min ago", l.getValue("MSFT").value)
        assertTrue(l.getValue("MSFT").warn)
        assertEquals("No data yet", l.getValue("AAPL").value)
    }

    @Test fun feedsShowCountsValidatorsBackoffAndDisabledAndAutoFeeds() {
        val a = FeedState(
            "a", "Feed A", FeedStatus.NotModified, 30, lastOkAgeMs = 120_000L, okCount = 3, notModifiedCount = 9, errorCount = 1,
            lastErrorAgeMs = 600_000L, validator = "ETag + Last-Modified", lastErrorSeen = "The news source is slow to answer",
        )
        val b = FeedState("b", "Feed B", FeedStatus.Error, 0, lastError = "Can't reach the news sources", consecutiveFailures = 3, retryInMs = 4 * 60_000L, errorCount = 3, lastErrorAgeMs = 5_000L, lastErrorSeen = "Can't reach the news sources")
        val auto = FeedState("sym:XEQT.TO", "Yahoo Finance", FeedStatus.Ok, 5, lastOkAgeMs = 1_000, okCount = 1, validator = "ETag")
        val inp = DiagnosticsInput(
            news = NewsState(feedStates = listOf(a, b, auto), lastOkAgeMs = 120_000L),
            feedConfigs = listOf(FeedConfig("a", "Feed A", "https://a.example.com/x"), FeedConfig("b", "Feed B", "https://b.example.com/x"), FeedConfig("c", "Feed C", "https://c.example.com/x", enabled = false)),
        )
        val sec = DiagnosticsMapper.map(inp).sections.first { it.title == "News feeds" }
        assertEquals(listOf("", "Feed A", "Feed B", "Feed C", "Yahoo Finance, XEQT.TO"), sec.items.map { it.heading })
        val la = sec.items[1].lines.associate { it.label to it }
        assertEquals("Working, nothing new (304)", la.getValue("Status").value)
        assertEquals("3 new, 9 not modified (304), 1 failed", la.getValue("Answers").value)
        assertEquals("ETag + Last-Modified", la.getValue("Change check").value)
        assertEquals("The news source is slow to answer, 10 min ago", la.getValue("Last error").value)
        assertFalse(la.getValue("Last error").warn) // an old error of a working feed is history, not a warning
        assertEquals("Normal schedule", la.getValue("Retry").value)
        val lb = sec.items[2].lines.associate { it.label to it }
        assertEquals("Failing", lb.getValue("Status").value)
        assertTrue(lb.getValue("Last error").warn)
        assertEquals("In 4 min after 3 failures", lb.getValue("Retry").value)
        assertEquals("Turned off", sec.items[3].lines.single().value)
        assertEquals("Feed C", sec.items[3].heading)
    }

    @Test fun speedSourceShowsStatusWordsNeverASpeedOrAPosition() {
        val moving = DrivingStatus(UiMode.Glance, DrivingReason.Moving, 87.0)
        val sec = DiagnosticsMapper.map(DiagnosticsInput(driving = moving)).sections.last()
        val text = sec.items.flatMap { it.lines }.joinToString("\n") { "${it.label} ${it.value}" }
        assertTrue(text.contains("Locked"))
        assertFalse(text.contains("87"))
        assertTrue(text.contains("Position is never shown, stored or logged"))
        val none = DiagnosticsMapper.map(DiagnosticsInput(driving = DrivingStatus(reason = DrivingReason.NoFixYet), locationGranted = false)).sections.last().items[0].lines.associate { it.label to it }
        assertEquals("Missing", none.getValue("Location permission").value)
        assertTrue(none.getValue("Status").warn)
    }

    @Test fun noScreenTextHasAnEmDashOrAUrlWithAQuery() {
        val inp = DiagnosticsInput(
            providers = listOf(ProviderHealth("yahoo", okCount = 1)),
            fetches = listOf(FetchEvent(1, "yahoo", "query1.finance.yahoo.com", true, 200, 100, null)),
            feedConfigs = listOf(FeedConfig("a", "Feed A", "https://a.example.com/x?token=SECRET")),
        )
        val all = DiagnosticsMapper.map(inp).sections.flatMap { s -> listOf(s.title) + s.items.flatMap { listOf(it.heading) + it.lines.flatMap { l -> listOf(l.label, l.value) } } }.joinToString("\n")
        assertFalse(all.contains(Char(0x2014)))
        assertFalse(all.contains("SECRET"))
        assertFalse(all.contains("https://"))
    }
}
