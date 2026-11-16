package com.marketsboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.marketsboard.refresh.NetworkSignal
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * [NetworkSignal] from a ConnectivityManager default-network callback: emits whenever the default network becomes
 * available (hotspot back, LTE attached) or becomes validated (internet really works). Collected only while the app is active. Needs
 * ACCESS_NETWORK_STATE (declared). If registration fails the flow is simply silent: polling still recovers.
 */
class ConnectivityNetworkSignal(context: Context) : NetworkSignal {
    private val cm: ConnectivityManager? = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override val available: Flow<Unit> = if (cm == null) emptyFlow() else callbackFlow {
        // A hotspot network is "available" long before it has working internet. VALIDATED turns true once it does, and that
        // is the moment the app can really fetch, so it fires too (once per network, not on every capability tweak).
        val validated = HashSet<Network>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(Unit) }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val changed = synchronized(validated) { if (ok) validated.add(network) else { validated.remove(network); false } }
                if (changed) trySend(Unit)
            }

            override fun onLost(network: Network) { synchronized(validated) { validated.remove(network) } }
        }
        val registered = try {
            cm.registerDefaultNetworkCallback(cb)
            true
        } catch (e: Throwable) {
            Log.w("MarketsBoard", "network callback not registered", e)
            false
        }
        awaitClose { if (registered) runCatching { cm.unregisterNetworkCallback(cb) } }
    }
}
