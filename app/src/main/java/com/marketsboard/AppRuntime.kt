package com.marketsboard

import com.marketsboard.briefing.BriefingController
import com.marketsboard.briefing.LiveBriefingSource
import com.marketsboard.core.DrivingGate
import com.marketsboard.core.bind
import com.marketsboard.data.Clock
import com.marketsboard.refresh.ActiveGate
import com.marketsboard.refresh.NetworkSignal
import com.marketsboard.refresh.NewsPoller
import com.marketsboard.refresh.RefreshController
import com.marketsboard.speech.Speaker
import com.marketsboard.ui.DrivingActions
import com.marketsboard.ui.markets.MarketsPresenter
import com.marketsboard.ui.news.LinkOpener
import com.marketsboard.ui.news.NewsPresenter
import com.marketsboard.ui.settings.AboutInfo
import com.marketsboard.ui.settings.LocationAccess
import com.marketsboard.ui.settings.SettingsPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Process-scoped UI runtime: the refresh controller and the presenters on top of an [AppGraph]. It lives in the Application
 * (not the Activity) so a recreated Activity keeps the same presenters; [gate] is flipped by the Activity from onResume and
 * onPause and pauses all refreshing (and any briefing) while the app is in the background.
 *
 * Driving safety: [driving] turns GPS speed samples (fed by the Activity's `LocationSpeedSource`) into the UI mode that
 * every presenter observes; the presenters, not the widgets, enforce the Glance contract. The 1 s tick below lets a lost fix
 * and the 10 s unlock timer be noticed without a new sample.
 */
class AppRuntime(
    val graph: AppGraph,
    scope: CoroutineScope,
    clock: Clock = AndroidClock,
    network: NetworkSignal = NetworkSignal.None,
    val speaker: Speaker,
    val location: LocationAccess,
    about: AboutInfo = AboutInfo(),
    links: LinkOpener = LinkOpener.None,
) {
    /** Flipped by the Activity: [ActiveGate.resume] in onResume, [ActiveGate.pause] in onPause. Also gates the news poller and the briefing. */
    val gate = ActiveGate()

    val driving = DrivingGate(clock, passenger = graph.settingsStore.settings.value.passengerMode, permission = location.granted.value)
        // Passenger mode is session only and expires after 10 minutes stopped: the switch follows the gate.
        .also { it.bind(scope, graph.settingsStore.settings, location.granted) { graph.settingsStore.update { s -> s.copy(passengerMode = false) } } }

    init {
        scope.launch { while (true) { delay(1_000); driving.tick() } }
        // The first run (or a recovery) creates settings.json: off the main thread.
        scope.launch(Dispatchers.IO) { graph.settingsStore.flush() }
    }

    /** The "I am parked" hold after a long loss of signal while locked. */
    val drivingActions = object : DrivingActions { override fun onConfirmParked() { driving.confirmParked() } }

    val controller = RefreshController(
        scope = scope,
        quotes = graph.quotes,
        symbols = graph.watchlist.items.map { list -> list.map { it.symbol } },
        isActive = gate.isActive,
        clock = clock,
        // Retry a failed watchlist write on every resume, off the main thread.
        onResume = { scope.launch(Dispatchers.IO) { graph.watchlist.flushIfDirty(); graph.feedStore.flushIfDirty(); graph.settingsStore.flush() } },
        network = network,
    )

    val presenter = MarketsPresenter(scope, graph.data, controller, clock, mode = driving.mode)

    /** News polling (every 10 min, immediately on resume and when the network returns) on the same gate. */
    val newsPoller = NewsPoller(scope, graph.news, graph.feedStore, graph.watchlist.items, gate.isActive, network, clock)
    val newsPresenter = NewsPresenter(scope, graph.news, newsPoller, driving.mode, links)

    val settingsPresenter = SettingsPresenter(scope, graph.settingsStore, driving, location, speaker.availability, about, driving.mode)

    /** "Brief me": allowed in both modes. Also the [com.marketsboard.briefing.BriefingTrigger] for the media key (and a future steering wheel hook). */
    val briefing = BriefingController(
        scope, LiveBriefingSource(graph.data, graph.news, clock), speaker, graph.settingsStore.settings, graph.briefingHistory, driving.mode,
    )
}
