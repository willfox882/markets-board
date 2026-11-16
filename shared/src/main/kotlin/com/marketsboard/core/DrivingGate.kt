package com.marketsboard.core

import com.marketsboard.data.Clock
import com.marketsboard.data.settings.AppSettings
import com.marketsboard.ui.UiMode
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

    /** Permission is granted but no valid fix has shown the truck is stopped since the app started or came back to the screen. Glance, for at most [DrivingConfig.checkingMs]. */
    Checking,

    /** Passenger mode is on: the full UI is always available. Normal. */
    Passenger,

    /** There is no location permission, so the app cannot know whether the truck moves. Normal (cannot be known). */
    PermissionMissing,

    /** Location services are switched off on the unit. Normal (cannot be known). */
    LocationOff,

    /** The checking time ran out without any valid fix. Normal (cannot be known). */
    NoFixYet,

    /** Not locked, but the fix was lost since. Normal (cannot be known until it returns). */
    SignalLost,

    /** The owner held "I am parked" after a long loss of signal while locked. Normal until a fix above the lock speed. */
    ParkedConfirmed,

    /** A valid fix, not locked. Normal. */
    Parked,
}

/**
 * The gate's output: the UI mode, why, and the last speed that is still current (null when the fix is lost or absent).
 * [parkedPrompt] is true when the gate has been locked without any fix for [DrivingConfig.parkedPromptAfterMs] (a
 * parkade): the UI then offers a 3 second hold on "I am parked".
 */
data class DrivingStatus(
    val mode: UiMode = UiMode.Normal,
    val reason: DrivingReason = DrivingReason.Parked,
    val speedKmh: Double? = null,
    val parkedPrompt: Boolean = false,
) {
    /** True when the app cannot tell whether the truck moves and is NOT locked (so Settings and the Markets header warn in amber). */
    val unknown: Boolean
        get() = reason == DrivingReason.PermissionMissing || reason == DrivingReason.LocationOff || reason == DrivingReason.NoFixYet || reason == DrivingReason.SignalLost
}

/** Tunables of [DrivingGate]. The defaults are the owner's rules; tests use them as given. */
data class DrivingConfig(
    /** Two consecutive samples strictly above this speed lock. */
    val lockAboveKmh: Double = 5.0,
    /** One sample strictly above this speed locks at once. */
    val instantLockAboveKmh: Double = 10.0,
    /** The median of the last three samples at or below this speed starts the unlock timer; a first fix at or below it ends Checking. */
    val unlockAtOrBelowKmh: Double = 2.0,
    /** That must hold, without a gap in the fixes, for this long to unlock. */
    val unlockAfterMs: Long = 10_000L,
    /** A fix older than this counts as lost. */
    val fixTimeoutMs: Long = 5_000L,
    /** Anything faster than this is a bad sample and is ignored. */
    val maxPlausibleKmh: Double = 300.0,
    /** After the app starts or comes back to the screen the gate stays in Glance ("Checking") until the first fix at or below the unlock speed, for at most this long. */
    val checkingMs: Long = 8_000L,
    /** Locked without any fix for this long: offer "I am parked". */
    val parkedPromptAfterMs: Long = 60_000L,
    /** Passenger mode switches itself off after the truck has been stopped (valid fixes) for this long. */
    val passengerExpiresAfterMs: Long = 10 * 60_000L,
)

/**
 * The driving safety gate: GPS speed samples in, [UiMode] out. Pure Kotlin, time from an injected monotonic [Clock]
 * (the wall clock is never read, so a clock jump cannot unlock anything).
 *
 * The safety principle: the screen is read while PARKED; moving means audio only; NOT KNOWING is treated as moving.
 * - **Checking.** From app start, every [restart] (the Activity became visible) and every time permission or location
 *   services come back, the mode is Glance until a valid fix at or below the unlock speed arrives (then Normal at once),
 *   at most [DrivingConfig.checkingMs]. After that with no fix the mode falls back to Normal and says so
 *   ([DrivingStatus.unknown]; the Markets header and Settings show an amber "GPS unknown"). A lock from before a restart is kept.
 * - **Lock** on two consecutive samples above [DrivingConfig.lockAboveKmh] or one above [DrivingConfig.instantLockAboveKmh].
 * - **Unlock** needs the median of the last three samples (the maximum while fewer than three exist) at or below the unlock
 *   speed continuously for [DrivingConfig.unlockAfterMs], with no gap longer than [DrivingConfig.fixTimeoutMs] and no loss
 *   between. A red light never unlocks. The sample that locks does not count towards the timer.
 * - **Unreliable fixes** (`reliable = false`: poor position or speed accuracy, already reduced by the speed accuracy by the
 *   caller) may LOCK but never unlock, never start the timer and never count as a fix.
 * - A lost fix (explicit null, or no valid sample for [DrivingConfig.fixTimeoutMs]) never unlocks. After
 *   [DrivingConfig.parkedPromptAfterMs] of that while locked, [DrivingStatus.parkedPrompt] is true and [confirmParked]
 *   (a 3 second hold in the UI) unlocks; the next lock rule locks again.
 * - Samples that are NaN, infinite, negative or faster than [DrivingConfig.maxPlausibleKmh] are ignored as if absent.
 * - **Passenger mode** forces Normal (the speed is still tracked, so switching it off while moving locks at once). It is
 *   session-only (never persisted by the settings store) and switches itself off through [onPassengerExpired] after
 *   [DrivingConfig.passengerExpiresAfterMs] of valid stopped fixes.
 * - Without permission, or with location services off, the app cannot know: Normal with the reason; all sample state is cleared.
 *
 * Call [onSpeed] for every sample (about 1 Hz; null for "no fix") and [tick] about once a second. Thread safe.
 */
class DrivingGate(
    private val clock: Clock = Clock.System,
    private val config: DrivingConfig = DrivingConfig(),
    passenger: Boolean = false,
    permission: Boolean = true,
    locationOn: Boolean = true,
) {
    private val lock = Any()
    private var passenger = passenger
    private var permission = permission
    private var locationOn = locationOn

    private var locked = false
    private var everFix = false
    private var lostExplicit = false
    private var lastSpeed: Double? = null
    private var lastFixMono: Long? = null
    private var slowSince: Long? = null
    private val recent = ArrayDeque<Double>() // the last three reliable samples of the current uninterrupted chain
    private var aboveCount = 0
    private var checking = true
    private var checkStart = clock.monoMs()
    private var parkedConfirmed = false

    /** Called when Passenger mode switched itself off (so the settings switch can follow). */
    @Volatile var onPassengerExpired: (() -> Unit)? = null

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
        if (!permission && granted) startChecking(clock.monoMs())
        permission = granted
        publish(clock.monoMs())
    }

    /** Location services switched on or off on the unit (the GPS provider). */
    fun setLocationOn(on: Boolean) = synchronized(lock) {
        if (locationOn && !on) clearSamples()
        if (!locationOn && on) startChecking(clock.monoMs())
        locationOn = on
        publish(clock.monoMs())
    }

    /** The app came back to the screen (Android onStart) or was started: Checking begins again. A lock is kept; nothing else is trusted. */
    fun restart() = synchronized(lock) {
        val now = clock.monoMs()
        lostExplicit = false; everFix = false; lastSpeed = null; lastFixMono = null; slowSince = null; recent.clear(); aboveCount = 0
        parkedConfirmed = false
        startChecking(now)
        publish(now)
    }

    /**
     * One speed sample in km/h, or null for "no fix". Invalid numbers are ignored (the fix then ages out). [reliable] false
     * (poor accuracy; pass the speed already reduced by its accuracy) lets the sample lock but never unlock.
     */
    fun onSpeed(kmh: Double?, reliable: Boolean = true) = synchronized(lock) {
        val now = clock.monoMs()
        when {
            kmh == null -> { lostExplicit = true; slowSince = null; recent.clear(); aboveCount = 0 } // a reported loss also ends the quiet period
            !isPlausible(kmh) -> Unit // a bad sample is not a fix: it neither refreshes nor unlocks anything
            !permission || !locationOn -> Unit // without permission nothing is trusted
            else -> accept(kmh, reliable, now)
        }
        publish(now)
    }

    /** Re-evaluates with the current time (a fix that stopped arriving, a timer that ran out). Call about once a second. */
    fun tick() = synchronized(lock) { publish(clock.monoMs()) }

    /** The owner held "I am parked". Only honoured while [DrivingStatus.parkedPrompt] is true. True when it unlocked. */
    fun confirmParked(): Boolean = synchronized(lock) {
        val now = clock.monoMs()
        if (!parkedPromptNow(now)) return false
        locked = false; parkedConfirmed = true; slowSince = null; checking = false
        publish(now)
        true
    }

    private fun isPlausible(kmh: Double) = kmh.isFinite() && kmh >= 0.0 && kmh <= config.maxPlausibleKmh

    private fun startChecking(now: Long) { checking = true; checkStart = now }

    private fun clearSamples() {
        locked = false; everFix = false; lostExplicit = false; lastSpeed = null; lastFixMono = null; slowSince = null; recent.clear()
        aboveCount = 0; parkedConfirmed = false; checking = false
    }

    private fun registerAbove(kmh: Double): Boolean {
        aboveCount++
        if (kmh > config.instantLockAboveKmh || aboveCount >= 2) {
            locked = true; parkedConfirmed = false; checking = false
            return true
        }
        return false
    }

    private fun accept(kmh: Double, reliable: Boolean, now: Long) {
        if (!reliable) {
            // May lock, never unlock: an unreliable sample is neither a fix nor part of a quiet period.
            if (kmh > config.lockAboveKmh && registerAbove(kmh)) slowSince = null
            return
        }
        val last = lastFixMono
        // Time never runs backwards on a monotonic clock; if it does (a broken test clock), act as if no time passed.
        val gap = if (last == null) Long.MAX_VALUE else (now - last).coerceAtLeast(0)
        if (gap > config.fixTimeoutMs) { slowSince = null; recent.clear(); aboveCount = 0 } // continuity is broken
        lastSpeed = kmh
        lastFixMono = now
        lostExplicit = false
        everFix = true
        recent.addLast(kmh); if (recent.size > 3) recent.removeFirst()
        var lockedNow = false
        if (kmh > config.lockAboveKmh) lockedNow = registerAbove(kmh) else aboveCount = 0
        if (kmh <= config.unlockAtOrBelowKmh) checking = false // the first fix that shows the truck stopped ends Checking
        if (lockedNow) { slowSince = null; return }
        if (quiet()) { if (slowSince == null) slowSince = now } else slowSince = null
    }

    /** Median of the last three samples (the maximum while fewer exist) is at or below the unlock speed. */
    private fun quiet(): Boolean {
        if (recent.isEmpty()) return false
        val s = recent.sorted()
        val v = if (s.size >= 3) s[1] else s.last()
        return v <= config.unlockAtOrBelowKmh
    }

    private fun fixValid(now: Long): Boolean {
        val last = lastFixMono ?: return false
        return !lostExplicit && (now - last).coerceAtLeast(0) <= config.fixTimeoutMs
    }

    private fun parkedPromptNow(now: Long): Boolean {
        if (!locked || fixValid(now) || !permission || !locationOn) return false
        val ref = lastFixMono ?: checkStart
        return now - ref >= config.parkedPromptAfterMs
    }

    private fun publish(now: Long) {
        val valid = fixValid(now)
        if (locked && valid) {
            val since = slowSince
            if (since != null) {
                if (now < since) slowSince = now // clock went backwards: restart rather than trust it
                else if (now - since >= config.unlockAfterMs && quiet()) locked = false
            }
        }
        if (checking && now - checkStart >= config.checkingMs) checking = false
        var expired = false
        if (passenger && valid) {
            val since = slowSince
            if (since != null && now >= since && now - since >= config.passengerExpiresAfterMs && quiet()) { passenger = false; expired = true }
        }
        val speed = if (valid) lastSpeed else null
        val s = when {
            passenger -> DrivingStatus(UiMode.Normal, DrivingReason.Passenger, speed)
            !permission -> DrivingStatus(UiMode.Normal, DrivingReason.PermissionMissing, null)
            !locationOn -> DrivingStatus(UiMode.Normal, DrivingReason.LocationOff, null)
            locked && !valid -> DrivingStatus(UiMode.Glance, DrivingReason.NoSignalWhileMoving, null, parkedPrompt = parkedPromptNow(now))
            locked -> DrivingStatus(
                UiMode.Glance,
                if ((lastSpeed ?: 0.0) > config.lockAboveKmh) DrivingReason.Moving else DrivingReason.StoppedRecently,
                speed,
            )
            checking -> DrivingStatus(UiMode.Glance, DrivingReason.Checking, speed)
            parkedConfirmed -> DrivingStatus(UiMode.Normal, DrivingReason.ParkedConfirmed, speed)
            !everFix -> DrivingStatus(UiMode.Normal, DrivingReason.NoFixYet, null)
            !valid -> DrivingStatus(UiMode.Normal, DrivingReason.SignalLost, null)
            else -> DrivingStatus(UiMode.Normal, DrivingReason.Parked, speed)
        }
        if (_status.value != s) _status.value = s
        if (_mode.value != s.mode) _mode.value = s.mode
        if (expired) onPassengerExpired?.invoke()
    }
}

/** Display text for [DrivingReason] (Settings). */
object DrivingTexts {
    /** One short line: what the gate is doing. */
    fun reason(r: DrivingReason): String = when (r) {
        DrivingReason.Moving -> "Moving: glance view only"
        DrivingReason.StoppedRecently -> "Stopped: unlocking soon"
        DrivingReason.NoSignalWhileMoving -> "No GPS signal: staying locked"
        DrivingReason.Checking -> "Checking GPS: glance view until it shows you are stopped"
        DrivingReason.Passenger -> "Passenger mode: never locked"
        DrivingReason.PermissionMissing -> "Location is off: cannot tell if the truck moves"
        DrivingReason.LocationOff -> "Location services are off: cannot tell if the truck moves"
        DrivingReason.NoFixYet -> "No GPS fix: cannot tell if the truck moves"
        DrivingReason.SignalLost -> "GPS signal lost: cannot tell if the truck moves"
        DrivingReason.ParkedConfirmed -> "Parked (you confirmed it)"
        DrivingReason.Parked -> "Parked"
    }

    /** The headline value of the Status row. */
    fun statusValue(s: DrivingStatus): String = when (s.reason) {
        DrivingReason.Moving, DrivingReason.StoppedRecently, DrivingReason.NoSignalWhileMoving -> "Locked"
        DrivingReason.Checking -> "Checking"
        DrivingReason.Passenger -> "Passenger"
        DrivingReason.Parked, DrivingReason.ParkedConfirmed -> "Parked"
        else -> "Unknown"
    }

    fun speed(s: DrivingStatus): String = s.speedKmh?.let { "${Math.round(it)} km/h" } ?: "No GPS fix"

    /** The amber line on the Markets header and in Settings while the app cannot tell. */
    const val GPS_UNKNOWN = "GPS unknown: cannot tell if the truck is moving"
}

/**
 * Keeps the gate in step with the permission and the passenger setting: applies both now, then on every change, and lets
 * Passenger mode switch the setting off when it expires. Shared by the Android runtime and the sandbox.
 */
fun DrivingGate.bind(scope: CoroutineScope, settings: StateFlow<AppSettings>, permission: StateFlow<Boolean>, onPassengerExpired: () -> Unit = {}) {
    setPassenger(settings.value.passengerMode)
    setPermission(permission.value)
    this.onPassengerExpired = onPassengerExpired
    scope.launch { settings.map { it.passengerMode }.distinctUntilChanged().collect { setPassenger(it) } }
    scope.launch { permission.collect { setPermission(it) } }
}
