package com.marketsboard.refresh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Emits each time a network becomes available (hotspot back, LTE reattached). The platform implements it (Android:
 * a ConnectivityManager callback); the refresh controller answers with an immediate pass for failed symbols.
 */
interface NetworkSignal {
    val available: Flow<Unit>

    companion object { val None: NetworkSignal = object : NetworkSignal { override val available: Flow<Unit> = emptyFlow() } }
}
