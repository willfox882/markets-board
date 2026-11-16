package com.marketsboard.sandbox

import com.marketsboard.data.Clock
import com.marketsboard.data.MarketsData
import com.marketsboard.data.news.FeedStore
import com.marketsboard.data.news.NewsRepository
import com.marketsboard.data.repo.QuoteRepository
import com.marketsboard.data.store.FileJsonCache
import com.marketsboard.data.store.WatchlistStore
import com.marketsboard.refresh.ActiveGate
import com.marketsboard.refresh.NetworkSignal
import com.marketsboard.refresh.NewsPollConfig
import com.marketsboard.refresh.NewsPoller
import com.marketsboard.refresh.RefreshConfig
import com.marketsboard.ui.UiMode
import com.marketsboard.refresh.RefreshController
import com.marketsboard.ui.markets.MarketsPresenter
import com.marketsboard.ui.news.NewsPresenter
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Simulated time: [speed] runs everything (provider data, ages, cooldowns, refresh delays) that many times faster.
 * [trueClock] is what the fake market uses; [deviceClock] is the head unit's clock, which [deviceSkewMs] can push
 * behind to simulate a wrong date and time.
 */
class SandboxTime {
    @Volatile var speed: Double = 1.0
        set(v) { synchronized(this) { fold(); field = v } }
    @Volatile var deviceSkewMs: Long = 0

    private val wall0 = System.currentTimeMillis()
    private var lastReal = System.nanoTime()
    private var virtualNs = 0.0

    private fun fold() {
        val now = System.nanoTime()
        virtualNs += (now - lastReal) * speed
        lastReal = now
    }

    private fun virtualMs(): Long = synchronized(this) { fold(); (virtualNs / 1_000_000.0).toLong() }

    val trueClock: Clock = object : Clock {
        override fun nowMs(): Long = wall0 + virtualMs()
        override fun monoMs(): Long = virtualMs()
    }
    val deviceClock: Clock = object : Clock {
        override fun nowMs(): Long = wall0 + virtualMs() + deviceSkewMs
        override fun monoMs(): Long = virtualMs()
    }
}

/**
 * The real presenter, refresh controller, repository and watchlist store running against the fake provider, with a
 * temp-dir store. The control panel pokes the fields below.
 */
class SandboxEnv(
    scope: CoroutineScope,
    private val controlScope: CoroutineScope = scope,
    dir: File = Files.createTempDirectory("markets-board-sandbox").toFile().also { it.deleteOnExit() },
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    val time = SandboxTime()
    val fake = FakeMarketDataProvider(time.trueClock)
    val watchlist = WatchlistStore(File(dir, "watchlist.json"), time.deviceClock)
    private val cache = FileJsonCache(File(dir, "cache"), time.deviceClock)
    val quotes = QuoteRepository(
        listOf(fake), cache, time.deviceClock,
        hints = { sym -> watchlist.items.value.firstOrNull { it.symbol == sym } },
    )
    val data = MarketsData(watchlist, quotes, fake)

    /** The Activity-resumed switch the platform would flip. */
    val gate = ActiveGate(initial = true)
    val uiMode = MutableStateFlow(UiMode.Normal)

    /** "The hotspot is back": fired at the end of a simulated drop, like the Android connectivity callback. */
    private val networkFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val network: NetworkSignal = object : NetworkSignal { override val available: Flow<Unit> = networkFlow }

    val controller = RefreshController(
        scope, quotes, watchlist.items.map { l -> l.map { it.symbol } }, gate.isActive, time.deviceClock,
        RefreshConfig(timeScale = { time.speed }), onResume = { watchlist.flushIfDirty() }, network = network,
    )
    val presenter = MarketsPresenter(scope, data, controller, time.deviceClock, io, mode = uiMode)

    // ---- news: the real repository, poller and presenter against the fake news servers ----
    val newsFetcher = FakeFeedFetcher(time.trueClock)
    val feedStore = FeedStore(File(dir, "feeds.json"), time.deviceClock)
    val news = NewsRepository(feedStore, watchlist.items, newsFetcher, cache, time.deviceClock)
    val newsPoller = NewsPoller(scope, news, feedStore, watchlist.items, gate.isActive, network, time.deviceClock, NewsPollConfig(timeScale = { time.speed }))
    val newsPresenter = NewsPresenter(scope, news, newsPoller, uiMode)

    /** The last link the article screen asked to open (nothing is really opened unless the desktop allows it). */
    val lastOpenedUrl = MutableStateFlow("")

    fun openUrl(url: String) {
        lastOpenedUrl.value = url
        try {
            if (!java.awt.GraphicsEnvironment.isHeadless() && java.awt.Desktop.isDesktopSupported() &&
                java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)
            ) {
                java.awt.Desktop.getDesktop().browse(java.net.URI(url))
            }
        } catch (e: Throwable) {
            // No browser here: the panel still shows the link.
        }
    }

    private val _dropSecondsLeft = MutableStateFlow(0)
    /** Seconds left of a simulated hotspot drop (0 when none). */
    val dropSecondsLeft: StateFlow<Int> = _dropSecondsLeft

    /** Offline for [seconds], then back to whatever behaviour was selected. */
    fun simulateHotspotDrop(seconds: Int) {
        if (_dropSecondsLeft.value > 0) return
        val before = fake.behavior
        val newsBefore = newsFetcher.behavior
        fake.behavior = FakeBehavior.Offline
        newsFetcher.behavior = FakeNewsBehavior.Offline
        controlScope.launch {
            for (left in seconds downTo 1) {
                _dropSecondsLeft.value = left
                delay(1_000)
            }
            _dropSecondsLeft.value = 0
            if (fake.behavior == FakeBehavior.Offline) fake.behavior = if (before == FakeBehavior.Offline) FakeBehavior.Normal else before
            if (newsFetcher.behavior == FakeNewsBehavior.Offline) newsFetcher.behavior = if (newsBefore == FakeNewsBehavior.Offline) FakeNewsBehavior.Normal else newsBefore
            networkFlow.tryEmit(Unit)
        }
    }

    fun resetWatchlist() {
        controlScope.launch(Dispatchers.IO) { data.resetToDefaults() }
    }
}
