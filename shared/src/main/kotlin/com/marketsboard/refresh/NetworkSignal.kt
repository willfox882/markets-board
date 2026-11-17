package com.marketsboard.refresh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** What the platform knows about the network. [Unknown] (no callback, or not told yet) never suppresses anything. */
enum class NetworkStatus { Unknown, Online, Offline }

/**
 * Emits each time a network becomes available (hotspot back, LTE reattached). The platform implements it (Android:
 * a ConnectivityManager callback); the refresh controller answers with an immediate pass for failed symbols.
 *
 * [status] says whether there is a default network at all. While it is [NetworkStatus.Offline] the automatic polling stops
 * (no timeouts, no spinners, no error churn), and the next [available] signal retries at once. A hotspot that is connected
 * but has no internet still reads Online here; the data layer's own failures then drive the offline state (see `Connectivity`).
 */
interface NetworkSignal {
    val available: Flow<Unit>

    val status: StateFlow<NetworkStatus> get() = UNKNOWN

    /**
     * How many times [status] has gone Offline (counted by the platform inside its callback, so a quick Offline and Online pair that a
     * conflated StateFlow would merge is still counted). Consumers compare it with the value they last handled ([OutageTracker]).
     */
    val outageCount: Long get() = 0L

    companion object {
        private val UNKNOWN: StateFlow<NetworkStatus> = MutableStateFlow(NetworkStatus.Unknown)

        val None: NetworkSignal = object : NetworkSignal { override val available: Flow<Unit> = emptyFlow() }
    }
}

/**
 * Remembers whether the network went away since the last pass that followed its return. Counts outages through
 * [NetworkSignal.outageCount] (never missed, even when the app was in the background) and passes skipped because the status was Offline.
 * Not thread-confined: all calls are short and synchronized.
 */
class OutageTracker(private val net: NetworkSignal) {
    private var handled = net.outageCount
    private var skipped = false

    /** A scheduled pass was skipped because there was no network. */
    @Synchronized fun skippedWhileOffline() { skipped = true }

    @Synchronized fun pending(): Boolean = skipped || net.outageCount != handled

    /** True when an outage is waiting to be answered; clears it. */
    @Synchronized fun consume(): Boolean {
        val was = pending()
        handled = net.outageCount
        skipped = false
        return was
    }
}
