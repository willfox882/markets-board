package com.marketsboard.sandbox

import com.marketsboard.core.DrivingReason
import com.marketsboard.core.DrivingStatus
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.net.FetchEvent
import com.marketsboard.data.news.DefaultFeeds
import com.marketsboard.data.news.FeedConfig
import com.marketsboard.data.news.FeedKind
import com.marketsboard.data.news.FeedState
import com.marketsboard.data.news.FeedStatus
import com.marketsboard.data.news.NewsOverall
import com.marketsboard.data.news.NewsState
import com.marketsboard.data.repo.ProviderHealth
import com.marketsboard.data.store.WatchlistStore
import com.marketsboard.refresh.NetworkStatus
import com.marketsboard.ui.UiMode
import com.marketsboard.ui.markets.MarketsUiState
import com.marketsboard.ui.tools.DiagnosticsInput
import com.marketsboard.ui.tools.DiagnosticsMapper
import com.marketsboard.ui.tools.FeedAddUi
import com.marketsboard.ui.tools.FeedRemoveUi
import com.marketsboard.ui.tools.FeedRenameUi
import com.marketsboard.ui.tools.FeedRowUi
import com.marketsboard.ui.tools.FeedsUiState
import com.marketsboard.ui.tools.ToolsPage
import com.marketsboard.ui.tools.ToolsUiState

/** Deterministic states of the M5 screens (feed editor, diagnostics) and of the offline look, for screenshots and render tests. */
object ToolsFixtures {
    private val F = ScreenshotFixtures

    private fun rows(selected: String? = null, off: Set<String> = setOf("marketwatch-top")): List<FeedRowUi> {
        val list = DefaultFeeds.ALL
        return list.mapIndexed { i, f ->
            FeedRowUi(
                f.id, f.name, f.url.removePrefix("https://").removePrefix("www.").substringBefore('/'), f.id !in off, FeedKind.General, null,
                canMoveUp = i > 0, canMoveDown = i < list.size - 1, selected = f.id == selected,
            )
        }
    }

    fun feedsState(selected: String? = null) = FeedsUiState(rows(selected), symbolFeeds = true, symbolFeedCount = 2)

    fun feeds() = ToolsUiState(ToolsPage.Feeds, feedsState())
    fun feedsSelected() = ToolsUiState(ToolsPage.Feeds, feedsState("globe-business"))
    fun feedsAdd() = ToolsUiState(ToolsPage.Feeds, feedsState().copy(add = FeedAddUi("My blog", "example.com/feed.xml")))
    fun feedsAddError() = ToolsUiState(ToolsPage.Feeds, feedsState().copy(add = FeedAddUi("", "http://example.com/feed.xml", "Feeds must use an https address")))
    fun feedsConfirmRemove() = ToolsUiState(ToolsPage.Feeds, feedsState("yahoo-top").copy(confirmRemove = FeedRemoveUi("yahoo-top", "Yahoo Finance")))
    fun feedsConfirmReset() = ToolsUiState(ToolsPage.Feeds, feedsState().copy(confirmReset = true))
    fun feedsRename() = ToolsUiState(ToolsPage.Feeds, feedsState("cbc-business").copy(rename = FeedRenameUi("cbc-business", "CBC News", "CBC Business")))
    fun feedsReadOnly() = ToolsUiState(
        ToolsPage.Feeds, feedsState().copy(readOnly = true, notice = "These feeds were saved by a newer version of the app and cannot be changed here."),
    )

    private val watch = WatchlistStore.DEFAULTS

    private fun input(offline: Boolean = false): DiagnosticsInput {
        val feeds = DefaultFeeds.ALL + FeedConfig("f-x1", "Bank of Canada", "https://example.com/boc.xml", enabled = false)
        val states = listOf(
            FeedState("cbc-business", "CBC Business", FeedStatus.Ok, 28, lastOkAgeMs = 3 * 60_000L, okCount = 4, notModifiedCount = 11, errorCount = 0, validator = "ETag"),
            FeedState(
                "globe-business", "The Globe and Mail", FeedStatus.NotModified, 31, lastOkAgeMs = 3 * 60_000L, okCount = 2, notModifiedCount = 13, errorCount = 1,
                lastErrorAgeMs = 42 * 60_000L, lastErrorSeen = "The news source is slow to answer", validator = "ETag + Last-Modified",
            ),
            FeedState(
                "financial-post", "Financial Post", FeedStatus.Error, 12, lastOkAgeMs = 25 * 60_000L, lastError = "The news source has a problem (503)", consecutiveFailures = 3, retryInMs = 4 * 60_000L,
                okCount = 1, errorCount = 3, lastErrorAgeMs = 20_000L, lastErrorSeen = "The news source has a problem (503)", validator = "Last-Modified",
            ),
            FeedState("yahoo-top", "Yahoo Finance", FeedStatus.Ok, 20, lastOkAgeMs = 3 * 60_000L, okCount = 5, validator = "None"),
            FeedState("sym:XEQT.TO", "Yahoo Finance", FeedStatus.Ok, 6, lastOkAgeMs = 3 * 60_000L, okCount = 5, notModifiedCount = 2, validator = "ETag"),
        )
        val quotes = mapOf(
            "XEQT.TO" to F.fresh(F.quote("XEQT.TO")).copy(lastSuccessMs = ScreenshotFixtures.NOW - 90_000L),
            "MSFT" to F.stale(F.quote("MSFT"), 11 * 60_000L),
        )
        return DiagnosticsInput(
            signal = if (offline) NetworkStatus.Offline else NetworkStatus.Online, offline = offline,
            providers = listOf(
                ProviderHealth("yahoo", lastOkAgeMs = 90_000L, lastError = "The data service has a problem (503)", lastErrorAgeMs = 17 * 60_000L, failureStreak = 0, okCount = 142, errorCount = 3),
                ProviderHealth("stooq", lastOkAgeMs = 2 * 3_600_000L, okCount = 4, errorCount = 0),
            ),
            fetches = listOf(
                FetchEvent(1, "stooq", "stooq.com", true, 200, 310, null),
                FetchEvent(2, "yahoo", "query1.finance.yahoo.com", true, 200, 180, null),
            ),
            news = NewsState(
                items = emptyList(), feedStates = states, overall = NewsOverall.Fresh, lastOkAgeMs = 3 * 60_000L,
                serverOffsetMs = 0L,
            ),
            feedConfigs = feeds, watch = watch, quotes = quotes,
            driving = DrivingStatus(UiMode.Normal, DrivingReason.Parked, 0.0), locationGranted = true, wallNowMs = ScreenshotFixtures.NOW,
        )
    }

    fun diagnostics() = ToolsUiState(ToolsPage.Diagnostics, diagnostics = DiagnosticsMapper.map(input()))

    fun diagnosticsOffline() = ToolsUiState(
        ToolsPage.Diagnostics,
        diagnostics = DiagnosticsMapper.map(
            input(offline = true).let { i ->
                i.copy(
                    providers = i.providers.map { it.copy(lastError = "Can't reach the data service", lastErrorAgeMs = 5_000L, errorCount = it.errorCount + 1) },
                    news = i.news.copy(serverOffsetMs = -3 * 3_600_000L, lastPassFailed = true, errorKind = ErrorKind.Network),
                    driving = DrivingStatus(UiMode.Normal, DrivingReason.NoFixYet), locationGranted = false,
                )
            },
        ),
    )

    /** Both default rows holding a price, the last refresh failing for a network reason (what the offline look turns into one calm state). */
    fun offlineMarkets(): MarketsUiState {
        val items: List<WatchItem> = watch
        val states = items.associate { it.symbol to F.failed(F.quote(it.symbol), ErrorKind.Network, 9 * 60_000L) }
        return F.marketsState(items, states)
    }
}
