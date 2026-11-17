package com.marketsboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.marketsboard.refresh.NetworkSignal
import com.marketsboard.refresh.NetworkStatus
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [NetworkSignal] from ONE process-wide ConnectivityManager default-network callback (registered at construction, so the status
 * is right when the app comes back to the screen):
 * - [available] emits when the default network becomes available (hotspot back, LTE attached) and again when it becomes
 *   VALIDATED (internet really works, once per network). Bursts are collapsed by the collectors, not here.
 * - [status] is Offline only when there is no default network at all (the hotspot dropped) for more than [OFFLINE_DELAY_MS] (a
 *   Wi-Fi to LTE hand-over or a flapping hotspot announces the new network within that time and cancels the pending Offline), Online as
 *   soon as one is available. [outageCount] counts committed Offline transitions inside the callback, so none is lost to StateFlow conflation.
 *   A network that never validates (some head units cannot reach the connectivity check) still reads Online, so polling is
 *   never suppressed by a wrong guess; the data layer's own failures drive the offline banner in that case.
 * Needs ACCESS_NETWORK_STATE (declared). If registration fails the status stays Unknown and polling recovers on its own.
 */
class ConnectivityNetworkSignal(context: Context) : NetworkSignal {
    private val cm: ConnectivityManager? = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val available: Flow<Unit> = flow

    private val _status = MutableStateFlow(NetworkStatus.Unknown)
    override val status: StateFlow<NetworkStatus> = _status

    private val _outages = java.util.concurrent.atomic.AtomicLong(0)
    override val outageCount: Long get() = _outages.get()

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val commitOffline = Runnable {
        val gone = synchronized(lock) { current == null }
        if (gone && _status.value != NetworkStatus.Offline) { _outages.incrementAndGet(); _status.value = NetworkStatus.Offline }
    }

    private val lock = Any()
    private var current: Network? = null
    private val validated = HashSet<Network>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(lock) { current = network }
            handler.removeCallbacks(commitOffline) // a new network arrived in time: the drop was only a hand-over
            _status.value = NetworkStatus.Online
            flow.tryEmit(Unit)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val changed = synchronized(lock) { if (ok) validated.add(network) else { validated.remove(network); false } }
            if (changed) { handler.removeCallbacks(commitOffline); _status.value = NetworkStatus.Online; flow.tryEmit(Unit) }
        }

        override fun onLost(network: Network) {
            val gone = synchronized(lock) {
                validated.remove(network)
                // Wi-Fi to LTE: the new network is announced before the old one is lost, so only the CURRENT network going away is "offline".
                if (current == network) { current = null; true } else false
            }
            if (gone) { handler.removeCallbacks(commitOffline); handler.postDelayed(commitOffline, OFFLINE_DELAY_MS) }
        }
    }

    init {
        if (cm != null) {
            try {
                // No default network at start: Offline at once (there is no earlier network to hand over from); not counted as an outage.
                _status.value = if (cm.activeNetwork == null) NetworkStatus.Offline else NetworkStatus.Online
                cm.registerDefaultNetworkCallback(callback)
            } catch (e: Throwable) {
                Log.w("MarketsBoard", "network callback not registered", e)
                _status.value = NetworkStatus.Unknown
            }
        }
    }

    companion object { const val OFFLINE_DELAY_MS = 1_000L }
}
