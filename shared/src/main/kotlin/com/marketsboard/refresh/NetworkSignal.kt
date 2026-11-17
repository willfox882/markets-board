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

    companion object {
        private val UNKNOWN: StateFlow<NetworkStatus> = MutableStateFlow(NetworkStatus.Unknown)

        val None: NetworkSignal = object : NetworkSignal { override val available: Flow<Unit> = emptyFlow() }
    }
}
