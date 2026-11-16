package com.marketsboard.sandbox

import com.marketsboard.briefing.BriefingController
import com.marketsboard.briefing.LiveBriefingSource
import com.marketsboard.core.DrivingGate
import com.marketsboard.core.bind
import com.marketsboard.data.Clock
import com.marketsboard.data.settings.AppSettingsStore
import com.marketsboard.data.settings.BriefingHistoryStore
import com.marketsboard.ui.settings.AboutInfo
import com.marketsboard.ui.settings.LocationAccess
import com.marketsboard.ui.settings.SettingsPresenter
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

/** The location permission of the sandbox: a toggle in the control panel; "Grant location permission" in Settings grants it. */
class SandboxLocation : LocationAccess {
    val flow = MutableStateFlow(true)
    override val granted: StateFlow<Boolean> = flow
    override fun request() { flow.value = true }
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
    /** Clock of the driving gate. Real time by default (the time-speed control must not make 1 Hz samples look lost); tests pass a fake. */
    gateClock: Clock = Clock.System,
    /** Feed the gate from the control panel's speed and GPS toggles every second. Tests turn it off and call [feed] themselves. */
    autoFeed: Boolean = true,
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

    // ---- driving: the real gate, fed by the control panel instead of a GPS ----
    val settingsStore = AppSettingsStore(File(dir, "settings.json"), time.deviceClock)

    /** The location permission toggle of the control panel ("no permission" = false). */
    val location = SandboxLocation()
    val speedKmh = MutableStateFlow(0.0)
    val gpsLost = MutableStateFlow(false)
    val driving = DrivingGate(gateClock).also { it.bind(scope, settingsStore.settings, location.granted) { settingsStore.update { s -> s.copy(passengerMode = false) } } }
    val uiMode: StateFlow<UiMode> = driving.mode

    /** One GPS sample from the panel's controls (null while "GPS lost"), then a tick. Runs every second unless [autoFeed] is off. */
    fun feed() {
        driving.onSpeed(if (gpsLost.value) null else speedKmh.value)
        driving.tick()
    }

    /** Sets the simulated speed and delivers a sample at once. */
    fun driveAt(kmh: Double) { speedKmh.value = kmh; feed() }

    /** The "I am parked" hold of the Glance lower area. */
    val drivingActions = object : com.marketsboard.ui.DrivingActions { override fun onConfirmParked() { driving.confirmParked() } }

    init {
        // The first sample (0 km/h) ends the gate's Checking phase at once; then one per second.
        if (autoFeed) { feed(); controlScope.launch { while (true) { delay(1_000); feed() } } }
    }

    /** "The hotspot is back": fired at the end of a simulated drop, like the Android connectivity callback. */
    private val networkFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val network: NetworkSignal = object : NetworkSignal { override val available: Flow<Unit> = networkFlow }

    val controller = RefreshController(
        scope, quotes, watchlist.items.map { l -> l.map { it.symbol } }, gate.isActive, time.deviceClock,
        RefreshConfig(timeScale = { time.speed }), onResume = { watchlist.flushIfDirty() }, network = network,
    )
    val presenter = MarketsPresenter(scope, data, controller, time.deviceClock, io, mode = driving.mode)

    // ---- news: the real repository, poller and presenter against the fake news servers ----
    val newsFetcher = FakeFeedFetcher(time.trueClock)
    val feedStore = FeedStore(File(dir, "feeds.json"), time.deviceClock)
    val news = NewsRepository(feedStore, watchlist.items, newsFetcher, cache, time.deviceClock)
    val newsPoller = NewsPoller(scope, news, feedStore, watchlist.items, gate.isActive, network, time.deviceClock, NewsPollConfig(timeScale = { time.speed }))
    val newsPresenter = NewsPresenter(scope, news, newsPoller, uiMode, com.marketsboard.ui.news.LinkOpener { url -> openUrl(url) })

    // ---- settings and the spoken briefing (the speaker is a fake: no sound, a log and the caption) ----
    val speaker = FakeSpeaker(scope, speedUp = { time.speed })
    val settingsPresenter = SettingsPresenter(scope, settingsStore, driving, location, speaker.availability, AboutInfo("sandbox", "desktop"), uiMode, io)
    val history = BriefingHistoryStore(File(dir, "briefing-history.json"), time.deviceClock)
    val briefing = BriefingController(scope, LiveBriefingSource(data, news, time.deviceClock), speaker, settingsStore.settings, history, uiMode, io = io)

    /** The last link the article screen asked to open (nothing is really opened unless the desktop allows it). */
    val lastOpenedUrl = MutableStateFlow("")

    /** Records the link and, when this machine has a browser, opens it. False when it could not (the panel still shows the link). */
    fun openUrl(url: String): Boolean {
        lastOpenedUrl.value = url
        return try {
            if (!java.awt.GraphicsEnvironment.isHeadless() && java.awt.Desktop.isDesktopSupported() &&
                java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)
            ) {
                java.awt.Desktop.getDesktop().browse(java.net.URI(url))
                true
            } else {
                false
            }
        } catch (e: Throwable) {
            false
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
