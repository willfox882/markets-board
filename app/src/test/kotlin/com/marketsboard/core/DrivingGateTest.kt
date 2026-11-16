package com.marketsboard.core

import com.marketsboard.data.FakeClock
import com.marketsboard.ui.UiMode
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The safety gate: checking, lock, unlock, lost fix, bad and unreliable samples, passenger, parkade, clocks, and a brute-force oracle. */
class DrivingGateTest {
    private class Rig(passenger: Boolean = false, permission: Boolean = true, settled: Boolean = true) {
        val clock = FakeClock()
        val gate = DrivingGate(clock, passenger = passenger, permission = permission)
        val mode get() = gate.mode.value
        val reason get() = gate.status.value.reason

        init { if (settled && permission) { gate.onSpeed(0.0); clock.advance(1_000); gate.tick() } } // the first stopped fix ends Checking

        /** One sample, then [ms] of time passes (a tick at the end, like the 1 s ticker). */
        fun s(kmh: Double?, ms: Long = 1_000, reliable: Boolean = true) { gate.onSpeed(kmh, reliable); clock.advance(ms); gate.tick() }

        /** Samples at 1 Hz for [seconds] seconds. */
        fun hold(kmh: Double, seconds: Int) { repeat(seconds) { s(kmh) } }
    }

    // ---- checking: not knowing is not interactive -----------------------------------------------------------------

    @Test fun coldStartWithPermissionIsGlanceUntilAFixShowsStopped() {
        val r = Rig(settled = false)
        assertEquals(UiMode.Glance, r.mode)
        assertEquals(DrivingReason.Checking, r.reason)
        r.s(0.5)
        assertEquals("the first fix at or below 2 km/h unlocks at once", UiMode.Normal, r.mode)
        assertEquals(DrivingReason.Parked, r.reason)
    }

    @Test fun aSlowButNotStoppedFirstFixStaysInCheckingUntilTheTimeIsUp() {
        val r = Rig(settled = false)
        r.s(3.0)
        assertEquals(UiMode.Glance, r.mode)
        r.hold(3.0, 6)
        assertEquals(UiMode.Glance, r.mode)
        r.hold(3.0, 2)
        assertEquals("eight seconds later the fix is valid and slow: Normal", UiMode.Normal, r.mode)
    }

    @Test fun noFixAtAllFallsBackToNormalAfterEightSecondsWithAnUnknownReason() {
        val r = Rig(settled = false)
        repeat(7) { r.clock.advance(1_000); r.gate.tick() }
        assertEquals(UiMode.Glance, r.mode)
        r.clock.advance(1_000); r.gate.tick()
        assertEquals(UiMode.Normal, r.mode)
        assertEquals(DrivingReason.NoFixYet, r.reason)
        assertTrue(r.gate.status.value.unknown)
    }

    @Test fun resumeStartsCheckingAgainEvenWhenParked() {
        val r = Rig()
        assertEquals(UiMode.Normal, r.mode)
        r.clock.advance(60_000); r.gate.tick()
        r.gate.restart()
        assertEquals("no fresh fix since the app came back: not interactive", UiMode.Glance, r.mode)
        assertEquals(DrivingReason.Checking, r.reason)
        r.s(0.0)
        assertEquals(UiMode.Normal, r.mode)
    }

    @Test fun aLockSurvivesARestart() {
        val r = Rig()
        r.hold(60.0, 3)
        r.gate.restart()
        assertEquals(UiMode.Glance, r.mode)
        assertEquals(DrivingReason.NoSignalWhileMoving, r.reason)
        r.s(0.0)
        assertEquals("a stopped fix still needs the ten seconds", UiMode.Glance, r.mode)
    }

    @Test fun aMovingFixDuringCheckingLocks() {
        val r = Rig(settled = false)
        r.hold(40.0, 2)
        assertEquals(DrivingReason.Moving, r.reason)
    }

    @Test fun missingPermissionOrLocationOffStaysNormalWithAnAmberReason() {
        val r = Rig(permission = false)
        r.s(60.0)
        assertEquals(UiMode.Normal, r.mode)
        assertEquals(DrivingReason.PermissionMissing, r.reason)
        assertTrue(r.gate.status.value.unknown)
        r.gate.setPermission(true)
        assertEquals("permission granted: checking begins", DrivingReason.Checking, r.reason)
        r.s(0.0)
        r.gate.setLocationOn(false)
        assertEquals(DrivingReason.LocationOff, r.reason)
        assertEquals(UiMode.Normal, r.mode)
        assertTrue(r.gate.status.value.unknown)
        r.gate.setLocationOn(true)
        assertEquals(DrivingReason.Checking, r.reason)
    }

    @Test fun losingThePermissionWhileLockedUnlocksAndForgetsEverything() {
        val r = Rig()
        r.hold(60.0, 3)
        assertEquals(UiMode.Glance, r.mode)
        r.gate.setPermission(false)
        assertEquals(UiMode.Normal, r.mode)
        assertEquals(DrivingReason.PermissionMissing, r.reason)
    }

    // ---- lock ---------------------------------------------------------------------------------------------------

    @Test fun locksOnTwoConsecutiveSamplesAboveFive() {
        val r = Rig()
        r.s(5.1)
        assertEquals("one sample between 5 and 10 is not enough", UiMode.Normal, r.mode)
        r.s(5.1)
        assertEquals(UiMode.Glance, r.mode)
        assertEquals(DrivingReason.Moving, r.reason)
    }

    @Test fun oneSampleAboveTenLocksAtOnce() {
        val r = Rig()
        r.gate.onSpeed(10.5)
        assertEquals(UiMode.Glance, r.mode)
    }

    @Test fun exactlyTenOrFiveDoesNotLock() {
        val r = Rig()
        r.s(10.0); r.s(5.0); r.s(5.0)
        assertEquals(UiMode.Normal, r.mode)
    }

    @Test fun aSingleBumpBetweenFiveAndTenFollowedByQuietDoesNotLock() {
        val r = Rig()
        repeat(20) { r.s(0.5); r.s(7.0); r.s(0.5) }
        assertEquals(UiMode.Normal, r.mode)
    }

    @Test fun twoHighSamplesWithAGapBetweenAreNotConsecutive() {
        val r = Rig()
        r.s(7.0, ms = 10_000)
        r.s(7.0)
        assertEquals(UiMode.Normal, r.mode)
    }

    // ---- unlock -------------------------------------------------------------------------------------------------

    @Test fun unlocksOnlyAfterTenSecondsOfQuiet() {
        val r = Rig()
        r.hold(60.0, 3)
        r.gate.onSpeed(0.0) // the median of 60, 60, 0 is still 60: the quiet period starts with the NEXT sample
        repeat(10) { r.clock.advance(1_000); r.gate.onSpeed(0.0) } // quiet since t0 + 1 s: nine seconds so far
        r.clock.advance(900); r.gate.tick()
        assertEquals(UiMode.Glance, r.mode)
        r.clock.advance(100); r.gate.tick() // t0 + 11 s, the last fix is 1 s old
        assertEquals(UiMode.Normal, r.mode)
    }

    @Test fun aRedLightNeverUnlocks() {
        val r = Rig()
        r.hold(50.0, 3)
        r.hold(0.0, 9)
        assertEquals(UiMode.Glance, r.mode)
        r.hold(30.0, 2)
        assertEquals(UiMode.Glance, r.mode)
    }

    @Test fun creepingAboveTwoRestartsTheCountdown() {
        val r = Rig()
        r.hold(40.0, 3)
        r.hold(0.0, 8)
        r.hold(3.0, 3) // the median of the last three is 3: not stopped
        r.hold(0.0, 9)
        assertEquals("the quiet before the crawl does not count", UiMode.Glance, r.mode)
        r.hold(0.0, 2)
        assertEquals(UiMode.Normal, r.mode)
    }

    @Test fun oneOutlierAmongQuietSamplesDoesNotRestartTheCountdown() {
        val r = Rig()
        r.hold(40.0, 3)
        r.hold(0.0, 4)
        r.s(4.0) // the median of 0, 0, 4 is still 0
        r.hold(0.0, 7)
        assertEquals(UiMode.Normal, r.mode)
    }

    @Test fun aSpikeThatLocksDoesNotCountTowardsTheTimer() {
        val r = Rig()
        r.hold(0.0, 12)
        r.s(60.0) // a glitch: locks
        assertEquals(UiMode.Glance, r.mode)
        r.hold(0.0, 9)
        assertEquals("ten seconds of quiet are needed AFTER the spike", UiMode.Glance, r.mode)
        r.hold(0.0, 3)
        assertEquals(UiMode.Normal, r.mode)
    }

    @Test fun exactlyTheUnlockSpeedCountsAsStopped() {
        val r = Rig()
        r.hold(40.0, 3)
        r.hold(2.0, 12)
        assertEquals(UiMode.Normal, r.mode)
    }

    // ---- lost fix -----------------------------------------------------------------------------------------------

    @Test fun aLostFixWhileMovingStaysLockedForever() {
        val r = Rig()
        r.hold(80.0, 3)
        r.gate.onSpeed(null)
        assertEquals(DrivingReason.NoSignalWhileMoving, r.reason)
        repeat(600) { r.clock.advance(1_000); r.gate.tick() }
        assertEquals(UiMode.Glance, r.mode)
        assertNull(r.gate.status.value.speedKmh)
    }

    @Test fun aFixThatSimplyStopsArrivingCountsAsLostAfterFiveSeconds() {
        val r = Rig()
        r.hold(80.0, 3)
        r.clock.advance(3_000); r.gate.tick()
        assertEquals(DrivingReason.Moving, r.reason)
        r.clock.advance(3_000); r.gate.tick()
        assertEquals(DrivingReason.NoSignalWhileMoving, r.reason)
    }

    @Test fun afterTheTunnelAStoppedFixStillNeedsTenSeconds() {
        val r = Rig()
        r.hold(80.0, 3)
        r.gate.onSpeed(null); r.clock.advance(60_000); r.gate.tick()
        r.s(0.0)
        assertEquals(DrivingReason.StoppedRecently, r.reason)
        r.hold(0.0, 9)
        assertEquals(UiMode.Normal, r.mode)
    }

    @Test fun aReportedLossEndsTheQuietPeriod() {
        val r = Rig()
        r.hold(60.0, 3)
        r.hold(0.0, 8)
        r.s(null)
        r.hold(0.0, 4)
        assertEquals("8 s, a loss, 4 s are not ten continuous seconds", UiMode.Glance, r.mode)
    }

    @Test fun unlockedThenLostFixStaysNormalButSaysUnknown() {
        val r = Rig()
        r.hold(0.0, 3)
        r.gate.onSpeed(null)
        assertEquals(UiMode.Normal, r.mode)
        assertEquals(DrivingReason.SignalLost, r.reason)
        assertTrue(r.gate.status.value.unknown)
    }

    // ---- bad and unreliable samples ----------------------------------------------------------------------------------

    @Test fun badNumbersAreIgnored() {
        val r = Rig()
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, -0.001, 300.1, 1e9)) {
            r.gate.onSpeed(bad)
            assertEquals("$bad must not lock", UiMode.Normal, r.mode)
        }
    }

    @Test fun badNumbersDoNotUnlockAndDoNotKeepAFixAlive() {
        val r = Rig()
        r.hold(60.0, 3)
        repeat(12) { r.gate.onSpeed(Double.NaN); r.clock.advance(1_000); r.gate.tick() }
        assertEquals(DrivingReason.NoSignalWhileMoving, r.reason)
    }

    @Test fun anUnreliableFixCanLockButNeverUnlock() {
        val r = Rig()
        r.s(30.0, reliable = false)
        assertEquals("an unreliable but fast fix locks", UiMode.Glance, r.mode)
        repeat(30) { r.s(0.0, reliable = false) }
        assertEquals("however long unreliable fixes say stopped", UiMode.Glance, r.mode)
        r.hold(0.0, 10)
        assertEquals("reliable ones do", UiMode.Normal, r.mode)
    }

    @Test fun anUnreliableFixIsNotAFix() {
        val r = Rig(settled = false)
        repeat(12) { r.s(0.0, reliable = false) }
        assertEquals("only unreliable fixes: after the checking time the app still does not know", DrivingReason.NoFixYet, r.reason)
    }

    // ---- passenger --------------------------------------------------------------------------------------------------

    @Test fun passengerModeForcesNormalWhileMoving() {
        val r = Rig(passenger = true)
        r.hold(90.0, 5)
        assertEquals(UiMode.Normal, r.mode)
        assertEquals(DrivingReason.Passenger, r.reason)
    }

    @Test fun switchingPassengerOffWhileMovingLocksAtOnce() {
        val r = Rig(passenger = true)
        r.hold(90.0, 5)
        r.gate.setPassenger(false)
        assertEquals(UiMode.Glance, r.mode)
    }

    @Test fun passengerModeExpiresAfterTenMinutesParkedAndTellsTheSettings() {
        val r = Rig(passenger = true)
        var expired = 0
        r.gate.onPassengerExpired = { expired++ }
        repeat(9 * 60) { r.s(0.0) }
        assertEquals(DrivingReason.Passenger, r.reason)
        assertEquals(0, expired)
        repeat(61) { r.s(0.0) }
        assertEquals(1, expired)
        assertEquals(DrivingReason.Parked, r.reason)
        r.hold(70.0, 3)
        assertEquals("and the lock works again", UiMode.Glance, r.mode)
    }

    @Test fun passengerModeDoesNotExpireWhileMovingOrWithoutAFix() {
        val r = Rig(passenger = true)
        var expired = 0
        r.gate.onPassengerExpired = { expired++ }
        repeat(30) { r.hold(60.0, 60) ; r.hold(0.0, 5) }
        repeat(700) { r.clock.advance(1_000); r.gate.tick() } // no fixes at all
        assertEquals(0, expired)
        assertEquals(DrivingReason.Passenger, r.reason)
    }

    // ---- parkade -----------------------------------------------------------------------------------------------------

    @Test fun afterAMinuteWithoutAFixWhileLockedTheParkedPromptAppearsAndAHoldUnlocks() {
        val r = Rig()
        r.hold(60.0, 3)
        r.gate.onSpeed(null)
        r.clock.advance(58_000); r.gate.tick() // 59 s since the last fix
        assertFalse(r.gate.status.value.parkedPrompt)
        assertFalse("not offered yet: refused", r.gate.confirmParked())
        r.clock.advance(3_000); r.gate.tick()
        assertTrue(r.gate.status.value.parkedPrompt)
        assertTrue(r.gate.confirmParked())
        assertEquals(UiMode.Normal, r.mode)
        assertEquals(DrivingReason.ParkedConfirmed, r.reason)
        assertFalse(r.gate.status.value.parkedPrompt)
    }

    @Test fun theParkedConfirmationIsClearedByTheNextFixAboveFive() {
        val r = Rig()
        r.hold(60.0, 3)
        r.gate.onSpeed(null); r.clock.advance(61_000); r.gate.tick()
        r.gate.confirmParked()
        r.hold(30.0, 2)
        assertEquals(UiMode.Glance, r.mode)
        assertEquals(DrivingReason.Moving, r.reason)
    }

    @Test fun noPromptWithAValidFixOrWhenNotLocked() {
        val r = Rig()
        r.clock.advance(120_000); r.gate.tick()
        assertFalse(r.gate.status.value.parkedPrompt) // unlocked, no fix: nothing to confirm
        assertFalse(r.gate.confirmParked())
        r.hold(60.0, 70)
        assertFalse("a valid moving fix never offers it", r.gate.status.value.parkedPrompt)
        assertFalse(r.gate.confirmParked())
    }

    @Test fun aRestartInsideAParkadeCountsTheMinuteFromTheRestart() {
        val r = Rig()
        r.hold(60.0, 3)
        r.clock.advance(120_000); r.gate.tick()
        r.gate.restart()
        assertFalse(r.gate.status.value.parkedPrompt)
        r.clock.advance(61_000); r.gate.tick()
        assertTrue(r.gate.status.value.parkedPrompt)
    }

    // ---- clocks -----------------------------------------------------------------------------------------------------

    @Test fun wallClockJumpsChangeNothing() {
        val r = Rig()
        r.hold(60.0, 3)
        r.hold(0.0, 4)
        r.clock.jumpWall(-3_600_000L); r.gate.tick()
        r.clock.jumpWall(+86_400_000L); r.gate.tick()
        assertEquals(UiMode.Glance, r.mode)
        r.hold(0.0, 8)
        assertEquals(UiMode.Normal, r.mode)
    }

    @Test fun aMonotonicClockThatGoesBackwardsNeverUnlocksEarly() {
        val r = Rig()
        r.hold(60.0, 3)
        r.s(0.0)
        r.clock.mono -= 30_000
        r.gate.onSpeed(0.0)
        r.hold(0.0, 5)
        assertEquals(UiMode.Glance, r.mode)
    }

    @Test fun veryLongGapsAreHandled() {
        val r = Rig()
        r.hold(60.0, 3)
        r.clock.advance(Long.MAX_VALUE / 4); r.gate.tick()
        assertEquals(UiMode.Glance, r.mode)
        r.s(0.0)
        assertEquals(UiMode.Glance, r.mode)
    }

    // ---- a brute-force oracle ---------------------------------------------------------------------------------------

    private class Ev(val t: Long, val v: Double?) // v null: reported loss; NaN: a bad sample (ignored)

    /** An evaluation: after the first [n] events of the history, at time [t]; [afterSample] is true right after event n-1 was delivered. */
    private class Eval(val t: Long, val n: Int, val afterSample: Boolean)

    private fun valid(v: Double?) = v != null && v.isFinite() && v >= 0.0 && v <= 300.0

    /**
     * Independent of the gate's incremental state: at every evaluation the verdict is recomputed from the WHOLE sample
     * history. Chain = valid samples since the last loss or gap above five seconds. A lock event is a chain sample above 10,
     * or above 5 with a chain predecessor above 5. A sample is slow when the median of the (up to) three chain samples
     * ending at it, or the maximum when fewer than three exist, is at most 2. Unlocked at an evaluation when, after the last
     * lock event, the trailing run of slow chain samples started at least 10 s before it and the newest sample is at most
     * 5 s old. Once unlocked it stays so until the next lock event (the verdict is a latch over the evaluations).
     */
    private class Oracle { var locked = false }

    private fun Oracle.step(history: List<Ev>, e: Eval) {
        run {
            val known = history.take(e.n)
            val chain = ArrayList<Int>() // indices into known
            var lastLock = -1
            for ((i, ev) in known.withIndex()) {
                if (ev.v == null) { chain.clear(); continue }
                if (!valid(ev.v)) continue
                if (chain.isNotEmpty() && ev.t - known[chain.last()].t > 5_000) chain.clear()
                val prevHigh = chain.isNotEmpty() && known[chain.last()].v!! > 5.0
                if (ev.v > 10.0 || (ev.v > 5.0 && prevHigh)) lastLock = i
                chain.add(i)
            }
            if (e.afterSample && lastLock == e.n - 1) locked = true
            if (!locked || chain.isEmpty() || e.t - known[chain.last()].t > 5_000) return
            fun slow(k: Int): Boolean {
                val w = chain.subList(maxOf(0, k - 2), k + 1).map { known[it].v!! }.sorted()
                return (if (w.size >= 3) w[1] else w.last()) <= 2.0
            }
            var start = chain.size
            while (start > 0 && slow(start - 1) && chain[start - 1] > lastLock) start--
            if (start < chain.size && e.t - known[chain[start]].t >= 10_000) locked = false
        }
    }

    @Test fun randomSequencesAgreeWithTheBruteForceOracle() {
        repeat(300) { seed ->
            val rnd = Random(seed)
            val r = Rig() // settled: Checking is over
            val history = ArrayList<Ev>()
            val oracle = Oracle()
            history.add(Ev(r.clock.monoMs() - 1_000, 0.0))
            repeat(250) {
                val roll = rnd.nextInt(100)
                val sample: Double? = when {
                    roll < 40 -> rnd.nextDouble() * 2.0
                    roll < 52 -> 2.0 + rnd.nextDouble() * 3.0
                    roll < 60 -> 5.0 + rnd.nextDouble() * 5.0
                    roll < 85 -> 10.0 + rnd.nextDouble() * 110.0
                    roll < 95 -> null
                    else -> Double.NaN
                }
                val dt = if (rnd.nextInt(10) == 0) rnd.nextLong(1, 20_000) else 1_000L
                val t = r.clock.monoMs()
                history.add(Ev(t, sample)); oracle.step(history, Eval(t, history.size, afterSample = true))
                r.gate.onSpeed(sample)
                assertEquals("seed $seed after the sample at $t", oracle.locked, r.mode == UiMode.Glance)
                r.clock.advance(dt); oracle.step(history, Eval(r.clock.monoMs(), history.size, afterSample = false)); r.gate.tick()
                assertEquals("seed $seed after the tick at ${r.clock.monoMs()}", oracle.locked, r.mode == UiMode.Glance)
            }
        }
    }

    @Test fun anySpeedAboveTenAlwaysLocksWhateverCameBefore() {
        repeat(100) { seed ->
            val rnd = Random(seed)
            val r = Rig()
            repeat(50) { r.s(rnd.nextDouble() * 4.0, rnd.nextLong(100, 30_000)) }
            r.gate.onSpeed(10.5 + rnd.nextDouble() * 200.0)
            assertEquals(UiMode.Glance, r.mode)
        }
    }

    @Test fun modeAndStatusAlwaysAgree() {
        val r = Rig(settled = false)
        listOf(0.0, 10.0, null, 0.0, 0.0, 3.0).forEach { r.s(it, 4_000); assertEquals(r.gate.status.value.mode, r.mode) }
    }
}
