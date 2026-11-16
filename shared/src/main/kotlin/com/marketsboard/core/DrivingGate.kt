package com.marketsboard.core

import com.marketsboard.data.Clock
import com.marketsboard.ui.UiMode
import com.marketsboard.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Why the gate is in its current mode. Shown in Settings (Driving section) so a lock is never a mystery. */
enum class DrivingReason {
    /** A valid fix says the truck is faster than the lock speed. Glance. */
    Moving,

    /** Locked, the fix is good and the truck is slow or stopped, but not yet for the whole unlock time. Glance. */
    StoppedRecently,

    /** Locked and the fix is lost or too old: it stays locked until a valid fix shows the truck is stopped. Glance. */
    NoSignalWhileMoving,

    /** Passenger mode is on: the full UI is always available. Normal. */
    Passenger,

    /** There is no location permission, so the app cannot know whether the truck moves. Normal (cannot be known). */
    PermissionMissing,

    /** No valid fix has arrived since the app started, so the app cannot know. Normal (cannot be known). */
    NoFixYet,

    /** Not locked, but the fix was lost since. Normal (cannot be known until it returns). */
    SignalLost,

    /** A valid fix, not locked. Normal. */
    Parked,
}

/** The gate's output: the UI mode, why, and the last speed that is still current (null when the fix is lost or absent). */
data class DrivingStatus(
    val mode: UiMode = UiMode.Normal,
    val reason: DrivingReason = DrivingReason.NoFixYet,
    val speedKmh: Double? = null,
) {
    /** True when the app cannot tell whether the truck moves (so Settings should warn). */
    val unknown: Boolean get() = reason == DrivingReason.PermissionMissing || reason == DrivingReason.NoFixYet || reason == DrivingReason.SignalLost
}

/** Tunables of [DrivingGate]. The defaults are the owner's rules; tests use them as given. */
data class DrivingConfig(
    /** Strictly above this speed the gate locks at once. */
    val lockAboveKmh: Double = 5.0,
    /** At or below this speed the unlock timer runs. */
    val unlockAtOrBelowKmh: Double = 2.0,
    /** The speed must stay at or below [unlockAtOrBelowKmh] for this long, without a gap in the fixes, to unlock. */
    val unlockAfterMs: Long = 10_000L,
    /** A fix older than this counts as lost. */
    val fixTimeoutMs: Long = 5_000L,
    /** Anything faster than this is a bad sample and is ignored. */
    val maxPlausibleKmh: Double = 300.0,
)

/**
 * The driving safety gate: GPS speed samples in, [UiMode] out. Pure Kotlin, time from an injected monotonic [Clock]
 * (the wall clock is never read, so a clock jump cannot unlock anything).
 *
 * Rules (the safety principle: the screen is read while PARKED; moving means audio only):
 * - A valid speed above [DrivingConfig.lockAboveKmh] locks (Glance) immediately.
 * - Unlock needs the speed at or below [DrivingConfig.unlockAtOrBelowKmh] continuously for [DrivingConfig.unlockAfterMs],
 *   with fixes arriving without a gap longer than [DrivingConfig.fixTimeoutMs]. A red light (a few seconds) never unlocks.
 *   A speed between the two thresholds, a gap in the fixes or a speed spike restarts the countdown.
 * - A lost fix (explicit null, or no valid sample for [DrivingConfig.fixTimeoutMs]) never unlocks: a tunnel keeps the
 *   lock until a valid fix shows the truck is stopped (and then the full unlock time still applies).
 * - Samples that are NaN, infinite, negative or faster than [DrivingConfig.maxPlausibleKmh] are ignored as if absent.
 * - Passenger mode forces Normal (reason [DrivingReason.Passenger]); the speed is still tracked so switching it off
 *   while moving locks at once. The UI shows a persistent label while it is on.
 * - Without location permission, or before the first valid fix of this run, the app cannot know: it stays Normal and
 *   says so ([DrivingStatus.unknown]) so Settings can warn. This is a documented limitation, not a guess. Losing the
 *   permission clears all sample state (nothing can be known any more).
 *
 * Call [onSpeed] for every sample (about 1 Hz; null for "no fix") and [tick] about once a second so a fix that stops
 * arriving is noticed and the unlock timer can finish without a new sample. Thread safe (calls are serialised).
 */
class DrivingGate(
    private val clock: Clock = Clock.System,
    private val config: DrivingConfig = DrivingConfig(),
    passenger: Boolean = false,
    permission: Boolean = true,
) {
    private val lock = Any()
    private var passenger = passenger
    private var permission = permission

    private var locked = false
    private var everFix = false
    private var lostExplicit = false
    private var lastSpeed: Double? = null
    private var lastFixMono: Long? = null
    private var slowSince: Long? = null

    private val _status = MutableStateFlow(DrivingStatus())
    val status: StateFlow<DrivingStatus> = _status.asStateFlow()
    private val _mode = MutableStateFlow(UiMode.Normal)

    /** The UI mode: what presenters and the root observe. */
    val mode: StateFlow<UiMode> = _mode.asStateFlow()

    init { publish(clock.monoMs()) }

    fun setPassenger(on: Boolean) = synchronized(lock) {
        passenger = on
        publish(clock.monoMs())
    }

    fun setPermission(granted: Boolean) = synchronized(lock) {
        if (permission && !granted) clearSamples()
        permission = granted
        publish(clock.monoMs())
    }

    /** One speed sample in km/h, or null for "no fix". Invalid numbers are ignored (the fix then ages out). */
    fun onSpeed(kmh: Double?) = synchronized(lock) {
        val now = clock.monoMs()
        when {
            kmh == null -> { lostExplicit = true; slowSince = null } // a reported loss also ends the quiet period
            !isPlausible(kmh) -> Unit // a bad sample is not a fix: it neither refreshes nor unlocks anything
            permission -> accept(kmh, now)
            else -> Unit // without permission nothing is trusted (the platform should not deliver samples anyway)
        }
        publish(now)
    }

    /** Re-evaluates with the current time (a fix that stopped arriving, an unlock timer that ran out). Call about once a second. */
    fun tick() = synchronized(lock) { publish(clock.monoMs()) }

    private fun isPlausible(kmh: Double) = kmh.isFinite() && kmh >= 0.0 && kmh <= config.maxPlausibleKmh

    private fun clearSamples() {
        locked = false; everFix = false; lostExplicit = false; lastSpeed = null; lastFixMono = null; slowSince = null
    }

    private fun accept(kmh: Double, now: Long) {
        val last = lastFixMono
        // Time never runs backwards on a monotonic clock; if it does (a broken test clock), act as if no time passed.
        val gap = if (last == null) Long.MAX_VALUE else (now - last).coerceAtLeast(0)
        if (gap > config.fixTimeoutMs) slowSince = null // continuity is broken: the slow period starts again
        lastSpeed = kmh
        lastFixMono = now
        lostExplicit = false
        everFix = true
        when {
            kmh > config.lockAboveKmh -> { locked = true; slowSince = null }
            kmh <= config.unlockAtOrBelowKmh -> if (slowSince == null) slowSince = now
            else -> slowSince = null // creeping between the thresholds is not "stopped"
        }
    }

    private fun fixValid(now: Long): Boolean {
        val last = lastFixMono ?: return false
        return !lostExplicit && (now - last).coerceAtLeast(0) <= config.fixTimeoutMs
    }

    private fun publish(now: Long) {
        val valid = fixValid(now)
        if (locked && valid) {
            val since = slowSince
            if (since != null) {
                if (now < since) slowSince = now // clock went backwards: restart rather than trust it
                else if (now - since >= config.unlockAfterMs && (lastSpeed ?: Double.MAX_VALUE) <= config.unlockAtOrBelowKmh) locked = false
            }
        }
        val speed = if (valid) lastSpeed else null
        val s = when {
            passenger -> DrivingStatus(UiMode.Normal, DrivingReason.Passenger, speed)
            !permission -> DrivingStatus(UiMode.Normal, DrivingReason.PermissionMissing, null)
            locked && !valid -> DrivingStatus(UiMode.Glance, DrivingReason.NoSignalWhileMoving, null)
            locked -> DrivingStatus(
                UiMode.Glance,
                if ((lastSpeed ?: 0.0) > config.lockAboveKmh) DrivingReason.Moving else DrivingReason.StoppedRecently,
                speed,
            )
            !everFix -> DrivingStatus(UiMode.Normal, DrivingReason.NoFixYet, null)
            !valid -> DrivingStatus(UiMode.Normal, DrivingReason.SignalLost, null)
            else -> DrivingStatus(UiMode.Normal, DrivingReason.Parked, speed)
        }
        if (_status.value != s) _status.value = s
        if (_mode.value != s.mode) _mode.value = s.mode
    }
}

/** Display text for [DrivingReason] (Settings). */
object DrivingTexts {
    /** One short line: what the gate is doing. */
    fun reason(r: DrivingReason): String = when (r) {
        DrivingReason.Moving -> "Moving: glance view only"
        DrivingReason.StoppedRecently -> "Stopped: unlocking soon"
        DrivingReason.NoSignalWhileMoving -> "No GPS signal: staying locked"
        DrivingReason.Passenger -> "Passenger mode: never locked"
        DrivingReason.PermissionMissing -> "Location is off: cannot tell if the truck moves"
        DrivingReason.NoFixYet -> "Waiting for a GPS fix"
        DrivingReason.SignalLost -> "GPS signal lost: cannot tell if the truck moves"
        DrivingReason.Parked -> "Parked"
    }

    /** The headline value of the Status row. */
    fun statusValue(s: DrivingStatus): String = when (s.reason) {
        DrivingReason.Moving, DrivingReason.StoppedRecently, DrivingReason.NoSignalWhileMoving -> "Locked"
        DrivingReason.Passenger -> "Passenger"
        DrivingReason.Parked -> "Parked"
        else -> "Unknown"
    }

    fun speed(s: DrivingStatus): String = s.speedKmh?.let { "${Math.round(it)} km/h" } ?: "No GPS fix"
}

/**
 * Keeps the gate in step with the passenger setting and the location permission: applies both now, then on every change.
 * Shared by the Android runtime and the sandbox so both wire it the same way.
 */
fun DrivingGate.bind(scope: CoroutineScope, settings: StateFlow<AppSettings>, permission: StateFlow<Boolean>) {
    setPassenger(settings.value.passengerMode)
    setPermission(permission.value)
    scope.launch { settings.map { it.passengerMode }.distinctUntilChanged().collect { setPassenger(it) } }
    scope.launch { permission.collect { setPermission(it) } }
}
