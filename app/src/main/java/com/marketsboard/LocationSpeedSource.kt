package com.marketsboard

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.marketsboard.core.SpeedSamples

/**
 * GPS speed for the driving gate: the platform GPS provider at 1 Hz, only between [start] and [stop] (the Activity calls them
 * from onResume and onPause), on the main looper, no foreground service and no background location. Each fix becomes a
 * sample through [SpeedSamples.kmh] (a fix older than 5 s, without speed or with poor accuracy is reported as null, which the
 * gate treats as a lost signal). Nothing about a fix is stored or logged: only the derived speed reaches [onSample].
 */
class LocationSpeedSource(
    context: Context,
    /** [kmh] null for "no usable fix"; [reliable] false lets the sample lock but never unlock. */
    private val onSample: (kmh: Double?, reliable: Boolean) -> Unit,
    /** Location services (the GPS provider) switched on or off. */
    private val onProvider: (Boolean) -> Unit = {},
) {
    private val manager = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var running = false

    private fun deliver(location: Location) {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        val speedAcc = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null
        val posAcc = if (location.hasAccuracy()) location.accuracy else null
        val s = SpeedSamples.sample(location.hasSpeed(), location.speed, ageMs, posAcc, speedAcc)
        if (s == null) onSample(null, true) else onSample(s.kmh, s.reliable)
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = deliver(location)

        // Required on API 26 to 29 (the framework still calls them); default methods only exist from API 30.
        override fun onProviderEnabled(provider: String) { onProvider(true) }
        override fun onProviderDisabled(provider: String) { onProvider(false); onSample(null, true) }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    /** Starts listening. False when it cannot (no permission, no GPS provider): the gate then simply never gets a fix. */
    @SuppressLint("MissingPermission") // checked through the SecurityException below and by the caller
    fun start(): Boolean {
        val m = manager ?: return false
        if (running) return true
        return try {
            if (!m.allProviders.contains(LocationManager.GPS_PROVIDER)) return false
            val enabled = m.isProviderEnabled(LocationManager.GPS_PROVIDER)
            onProvider(enabled)
            if (!enabled) return false
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
            running = true
            // A last known fix younger than 3 s (the unit was just tracking) ends the wait at once; an older one says nothing about now.
            try {
                m.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { l ->
                    if ((SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L < 3_000L) deliver(l)
                }
            } catch (e: SecurityException) { /* checked above by the request */ }
            true
        } catch (e: SecurityException) {
            false // permission denied is a normal state
        } catch (e: Throwable) {
            Log.w("MarketsBoard", "gps start failed: ${e.javaClass.simpleName}")
            false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try { manager?.removeUpdates(listener) } catch (e: Throwable) { /* nothing to undo */ }
    }
}
