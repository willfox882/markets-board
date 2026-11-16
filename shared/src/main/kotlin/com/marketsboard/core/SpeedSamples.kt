package com.marketsboard.core

/**
 * Turns the facts of one GPS fix into the speed sample for the [DrivingGate]: km/h, or null for "not a usable fix". Pure, so
 * the Android `LocationSpeedSource` stays a thin adapter and the rules are unit tested.
 *
 * A fix is unusable (null: the gate then treats the signal as lost and, if it was locked, stays locked) when:
 * - it carries no speed;
 * - it is older than [MAX_AGE_MS] (the receiver stopped delivering fresh data and Android replayed an old one);
 * - its position accuracy is worse than [MAX_POSITION_ACCURACY_M] or its speed accuracy worse than [MAX_SPEED_ACCURACY_MPS]
 *   (a fix that bad says almost nothing about the speed);
 * - the speed is not a finite non-negative number (the gate also rejects absurd values, but an obviously broken number
 *   never gets that far).
 */
object SpeedSamples {
    const val MAX_AGE_MS = 5_000L
    const val MAX_POSITION_ACCURACY_M = 200f
    const val MAX_SPEED_ACCURACY_MPS = 5f
    private const val MPS_TO_KMH = 3.6

    fun kmh(
        hasSpeed: Boolean,
        speedMps: Float,
        ageMs: Long,
        positionAccuracyM: Float? = null,
        speedAccuracyMps: Float? = null,
    ): Double? {
        if (!hasSpeed || !speedMps.isFinite() || speedMps < 0f) return null
        if (ageMs > MAX_AGE_MS) return null
        if (positionAccuracyM != null && (!positionAccuracyM.isFinite() || positionAccuracyM > MAX_POSITION_ACCURACY_M)) return null
        if (speedAccuracyMps != null && (!speedAccuracyMps.isFinite() || speedAccuracyMps > MAX_SPEED_ACCURACY_MPS)) return null
        return speedMps * MPS_TO_KMH
    }
}
