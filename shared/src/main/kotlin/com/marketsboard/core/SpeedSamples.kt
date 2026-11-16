package com.marketsboard.core

/** One usable GPS speed: [kmh] and whether it may also UNLOCK ([reliable]); an unreliable one may only lock. */
data class SpeedSample(val kmh: Double, val reliable: Boolean)

/**
 * Turns the facts of one GPS fix into a sample for the [DrivingGate], or null for "not a usable fix". Pure, so the Android
 * `LocationSpeedSource` stays a thin adapter and the rules are unit tested.
 *
 * Null (the gate then treats the signal as lost and, if it was locked, stays locked) when the fix carries no speed, is older
 * than [MAX_AGE_MS], or the speed is not a finite non-negative number.
 *
 * A fix with poor accuracy (position worse than [MAX_POSITION_ACCURACY_M] or speed accuracy worse than
 * [MAX_SPEED_ACCURACY_MPS]) is still returned but unreliable: its speed is REDUCED by the speed accuracy (a garage that
 * reports a drifting 8 km/h +/- 7 km/h is not "moving"), so it can only lock when even the reduced speed is above the lock
 * speed, and it can never unlock.
 */
object SpeedSamples {
    const val MAX_AGE_MS = 5_000L
    const val MAX_POSITION_ACCURACY_M = 200f
    const val MAX_SPEED_ACCURACY_MPS = 5f
    private const val MPS_TO_KMH = 3.6

    fun sample(
        hasSpeed: Boolean,
        speedMps: Float,
        ageMs: Long,
        positionAccuracyM: Float? = null,
        speedAccuracyMps: Float? = null,
    ): SpeedSample? {
        if (!hasSpeed || !speedMps.isFinite() || speedMps < 0f) return null
        if (ageMs > MAX_AGE_MS) return null
        val posBad = positionAccuracyM != null && (!positionAccuracyM.isFinite() || positionAccuracyM > MAX_POSITION_ACCURACY_M)
        val spdBad = speedAccuracyMps != null && (!speedAccuracyMps.isFinite() || speedAccuracyMps > MAX_SPEED_ACCURACY_MPS)
        val kmh = speedMps * MPS_TO_KMH
        if (!posBad && !spdBad) return SpeedSample(kmh, reliable = true)
        val reduced = (kmh - (speedAccuracyMps?.takeIf { it.isFinite() } ?: 0f) * MPS_TO_KMH).coerceAtLeast(0.0)
        return SpeedSample(reduced, reliable = false)
    }
}
