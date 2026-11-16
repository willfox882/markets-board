package com.marketsboard.core

import com.marketsboard.data.FakeClock
import com.marketsboard.ui.UiMode
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The safety gate: lock, unlock, lost fix, bad samples, passenger, permission, clock behaviour and random sequences. */
class DrivingGateTest {
    private class Rig(passenger: Boolean = false, permission: Boolean = true) {
        val clock = FakeClock()
        val gate = DrivingGate(clock, passenger = passenger, permission = permission)
        val mode get() = gate.mode.value
        val reason get() = gate.status.value.reason

        /** One sample, then [ms] of time passes (a tick at the end, like the 1 s ticker). */
        fun s(kmh: Double?, ms: Long = 1_000) { gate.onSpeed(kmh); clock.advance(ms); gate.tick() }

        /** Samples at 1 Hz for [seconds] seconds. */
        fun hold(kmh: Double, seconds: Int) { repeat(seconds) { s(kmh) } }
    }

    // ---- start and the unknown cases ---------------------------------------------------------------------------

    @Test fun startsNormalWithNoFix() {
        val r = Rig()
        assertEquals(UiMode.Normal, r.mode)
        assertEquals(DrivingReason.NoFixYet, r.reason)
        assertTrue(r.gate.status.value.unknown)
    }

    @Test fun missingPermissionStaysNormalAndSaysSo() {
        val r = Rig(permission = false)
        r.s(60.0)
        assertEquals(UiMode.Normal, r.mode)
        assertEquals(DrivingReason.PermissionMissing, r.reason)
        assertTrue(r.gate.status.value.unknown)
    }

    @Test fun losingThePermissionWhileLockedUnlocksAndForgetsEverything() {
        val r = Rig()
        r.hold(60.0, 3)
        assertEquals(UiMode.Glance, r.mode)
        r.gate.setPermission(false)
        assertEquals(UiMode.Normal, r.mode)
        assertEquals(DrivingReason.PermissionMissing, r.reason)
        r.gate.setPermission(true)
        assertEquals("no fix again until a sample arrives", DrivingReason.NoFixYet, r.reason)
    }

    // ---- lock ---------------------------------------------------------------------------------------------------

    @Test fun locksImmediatelyAboveFiveKmh() {
        val r = Rig()
        r.s(0.0)
        assertEquals(DrivingReason.Parked, r.reason)
        r.gate.onSpeed(5.1)
        assertEquals(UiMode.Glance, r.mode)
        assertEquals(DrivingReason.Moving, r.reason)
    }

    @Test fun exactlyFiveDoesNotLock() {
        val r = Rig()
        r.s(5.0)
        assertEquals(UiMode.Normal, r.mode)
    }

    // ---- unlock -------------------------------------------------------------------------------------------------

    @Test fun unlocksOnlyAfterTenSecondsAtOrBelowTwo() {
        val r = Rig()
        r.hold(60.0, 3)
        assertEquals(UiMode.Glance, r.mode)
        r.s(0.0) // t0: the stop begins
        assertEquals(DrivingReason.StoppedRecently, r.reason)
        repeat(8) { r.s(0.0); assertEquals(UiMode.Glance, r.mode) } // 9 s of stop
        r.s(0.0) // 10 s
        assertEquals(UiMode.Normal, r.mode)
        assertEquals(DrivingReason.Parked, r.reason)
    }

    @Test fun aRedLightNeverUnlocks() {
        val r = Rig()
        r.hold(50.0, 3)
        r.hold(0.0, 9) // a long red light, still under ten seconds
        assertEquals(UiMode.Glance, r.mode)
        r.hold(30.0, 2)
        assertEquals(UiMode.Glance, r.mode)
    }

    @Test fun tenSecondsMeansTenSecondsNotNine() {
        val r = Rig()
        r.hold(40.0, 2)
        r.gate.onSpeed(0.0) // the stop begins at t0
        repeat(9) { r.clock.advance(1_000); r.gate.onSpeed(0.0) } // samples up to t0 + 9 s
        r.clock.advance(900); r.gate.tick() // t0 + 9.9 s
        assertEquals(UiMode.Glance, r.mode)
        r.clock.advance(100); r.gate.tick() // t0 + 10 s, the last fix is 1 s old
        assertEquals(UiMode.Normal, r.mode)
    }

    @Test fun creepingBetweenTwoAndFiveRestartsTheCountdown() {
        val r = Rig()
        r.hold(40.0, 2)
        r.hold(0.0, 8)
        r.s(3.0) // crawling, not stopped
        r.hold(0.0, 9)
        assertEquals("the 8 s before the crawl do not count", UiMode.Glance, r.mode)
        r.s(0.0)
        assertEquals(UiMode.Normal, r.mode)
    }

    @Test fun aSpikeAboveFiveDuringTheCountdownRestartsIt() {
        val r = Rig()
        r.hold(40.0, 2)
        r.hold(0.0, 9)
        r.s(6.0)
        r.hold(0.0, 9)
        assertEquals(UiMode.Glance, r.mode)
        r.s(0.0)
        assertEquals(UiMode.Normal, r.mode)
    }

    @Test fun jitterAroundTheUnlockSpeedDoesNotUnlock() {
        val r = Rig()
        r.hold(40.0, 2)
        val jitter = listOf(0.0, 2.4, 0.5, 2.1, 0.0, 3.0, 1.0, 2.6, 0.0, 2.2, 0.3, 2.9)
        jitter.forEach { r.s(it) }
        assertEquals(UiMode.Glance, r.mode)
    }

    @Test fun noJitterEscapeAtTheExactBoundary() {
        val r = Rig()
        r.hold(40.0, 2)
        r.hold(2.0, 11) // exactly the unlock speed counts as stopped
        assertEquals(UiMode.Normal, r.mode)
    }

    // ---- lost fix -----------------------------------------------------------------------------------------------

    @Test fun aLostFixWhileMovingStaysLockedForever() {
        val r = Rig()
        r.hold(80.0, 3)
        r.gate.onSpeed(null)
        assertEquals(UiMode.Glance, r.mode)
        assertEquals(DrivingReason.NoSignalWhileMoving, r.reason)
        repeat(600) { r.clock.advance(1_000); r.gate.tick() } // ten minutes in a tunnel
        assertEquals(UiMode.Glance, r.mode)
        assertNull(r.gate.status.value.speedKmh)
    }

    @Test fun aFixThatSimplyStopsArrivingCountsAsLostAfterFiveSeconds() {
        val r = Rig()
        r.hold(80.0, 2)
        r.clock.advance(3_000); r.gate.tick()
        assertEquals(DrivingReason.Moving, r.reason)
        r.clock.advance(3_000); r.gate.tick()
        assertEquals(DrivingReason.NoSignalWhileMoving, r.reason)
        assertEquals(UiMode.Glance, r.mode)
    }

    @Test fun afterTheTunnelAValidStoppedFixStillNeedsTenSeconds() {
        val r = Rig()
        r.hold(80.0, 3)
        r.gate.onSpeed(null); r.clock.advance(60_000); r.gate.tick()
        r.s(0.0)
        assertEquals(UiMode.Glance, r.mode)
        assertEquals(DrivingReason.StoppedRecently, r.reason)
        r.hold(0.0, 9)
        assertEquals(UiMode.Normal, r.mode)
    }

    @Test fun afterTheTunnelAMovingFixKeepsItLockedAsMoving() {
        val r = Rig()
        r.hold(80.0, 3)
        r.gate.onSpeed(null); r.clock.advance(60_000)
        r.s(70.0)
        assertEquals(DrivingReason.Moving, r.reason)
    }

    @Test fun stoppedCountdownDoesNotSurviveAGapInTheFixes() {
        val r = Rig()
        r.hold(60.0, 2)
        r.hold(0.0, 6) // 6 s stopped
        r.gate.onSpeed(null); r.clock.advance(1_000); r.gate.tick()
        r.clock.advance(20_000) // the fix is gone for 20 s
        r.hold(0.0, 6)
        assertEquals("6 + 6 seconds with a gap between are not ten continuous seconds", UiMode.Glance, r.mode)
        r.hold(0.0, 5)
        assertEquals(UiMode.Normal, r.mode)
    }

    @Test fun unlockedThenLostFixStaysNormalButSaysUnknown() {
        val r = Rig()
        r.hold(0.0, 3)
        r.gate.onSpeed(null)
        assertEquals(UiMode.Normal, r.mode)
        assertEquals(DrivingReason.SignalLost, r.reason)
        assertTrue(r.gate.status.value.unknown)
    }

    @Test fun aFixFarTooOldForTheCountdownCannotUnlockByTickAlone() {
        val r = Rig()
        r.hold(60.0, 2)
        r.s(0.0)
        r.clock.advance(60_000); r.gate.tick() // no samples for a minute
        assertEquals("no new fix: the stop was never confirmed", UiMode.Glance, r.mode)
    }

    // ---- bad samples ----------------------------------------------------------------------------------------------

    @Test fun badNumbersAreIgnored() {
        val r = Rig()
        r.hold(0.0, 2)
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0, -0.001, 300.1, 1e9)) {
            r.gate.onSpeed(bad)
            assertEquals("$bad must not lock", UiMode.Normal, r.mode)
        }
    }

    @Test fun badNumbersDoNotUnlockAndDoNotKeepAFixAlive() {
        val r = Rig()
        r.hold(60.0, 2)
        repeat(12) { r.gate.onSpeed(Double.NaN); r.clock.advance(1_000); r.gate.tick() }
        assertEquals(UiMode.Glance, r.mode)
        assertEquals("only bad samples: the fix ages out", DrivingReason.NoSignalWhileMoving, r.reason)
    }

    @Test fun threeHundredIsStillAcceptedAsSpeed() {
        val r = Rig()
        r.s(300.0)
        assertEquals(UiMode.Glance, r.mode)
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
        assertEquals(DrivingReason.Moving, r.reason)
    }

    @Test fun passengerWithoutPermissionStillSaysPassenger() {
        val r = Rig(passenger = true, permission = false)
        assertEquals(DrivingReason.Passenger, r.reason)
    }

    // ---- clocks -----------------------------------------------------------------------------------------------------

    @Test fun wallClockJumpsChangeNothing() {
        val r = Rig()
        r.hold(60.0, 2)
        r.hold(0.0, 4)
        r.clock.jumpWall(-3_600_000L)
        r.gate.tick()
        r.clock.jumpWall(+86_400_000L)
        r.gate.tick()
        assertEquals(UiMode.Glance, r.mode)
        r.hold(0.0, 6)
        assertEquals(UiMode.Normal, r.mode)
    }

    @Test fun aMonotonicClockThatGoesBackwardsNeverUnlocksEarly() {
        val r = Rig()
        r.hold(60.0, 2)
        r.s(0.0)
        r.clock.mono -= 30_000
        r.gate.onSpeed(0.0)
        assertEquals(UiMode.Glance, r.mode)
        r.hold(0.0, 5)
        assertEquals(UiMode.Glance, r.mode)
    }

    @Test fun veryLongGapsAreHandled() {
        val r = Rig()
        r.hold(60.0, 2)
        r.clock.advance(Long.MAX_VALUE / 4); r.gate.tick()
        assertEquals(UiMode.Glance, r.mode)
        r.s(0.0)
        assertEquals(UiMode.Glance, r.mode)
    }

    // ---- invariants over random sequences -------------------------------------------------------------------------

    /** Reference model: written independently of the class (replays the same events). */
    private class Model {
        var locked = false
        var slowSince: Long? = null
        var lastFix: Long? = null
        var lastSpeed = 0.0
        var lost = false
        fun reportLost() { lost = true; slowSince = null }
        fun sample(kmh: Double, now: Long) {
            lost = false
            val gap = lastFix?.let { now - it } ?: Long.MAX_VALUE
            if (gap > 5_000) slowSince = null
            lastFix = now; lastSpeed = kmh
            if (kmh > 5.0) { locked = true; slowSince = null }
            else if (kmh <= 2.0) { if (slowSince == null) slowSince = now } else slowSince = null
        }
        fun mode(now: Long): UiMode {
            val valid = !lost && lastFix?.let { now - it <= 5_000 } == true
            if (locked && valid && slowSince != null && now - slowSince!! >= 10_000 && lastSpeed <= 2.0) locked = false
            return if (locked) UiMode.Glance else UiMode.Normal
        }
    }

    @Test fun randomSequencesMatchTheReferenceModelAndNeverUnlockWithoutTenQuietSeconds() {
        repeat(200) { seed ->
            val rnd = Random(seed)
            val r = Rig()
            val m = Model()
            var quietMs = 0L // continuous valid slow time seen by the test itself
            repeat(300) {
                val roll = rnd.nextInt(100)
                val sample: Double? = when {
                    roll < 40 -> 0.0 + rnd.nextDouble() * 2.0
                    roll < 55 -> 2.0 + rnd.nextDouble() * 3.0
                    roll < 85 -> 5.0 + rnd.nextDouble() * 120.0
                    roll < 95 -> null
                    else -> Double.NaN
                }
                val before = r.mode
                val dt = if (rnd.nextInt(10) == 0) rnd.nextLong(1, 20_000) else 1_000L
                val now = r.clock.monoMs()
                if (sample == null) m.reportLost() else if (!sample.isNaN()) m.sample(sample, now)
                r.gate.onSpeed(sample)
                m.mode(now) // the gate also evaluates right after a sample, before time passes
                quietMs = if (sample == null || (!sample.isNaN() && sample > 2.0)) 0L else quietMs + dt
                r.clock.advance(dt); r.gate.tick()
                val expected = m.mode(r.clock.monoMs())
                assertEquals("seed $seed", expected, r.mode)
                if (before == UiMode.Glance && r.mode == UiMode.Normal) {
                    assertTrue("seed $seed unlocked after only $quietMs ms of quiet", quietMs >= 10_000)
                }
            }
        }
    }

    @Test fun anySpeedAboveFiveAlwaysLocksWhateverCameBefore() {
        repeat(100) { seed ->
            val rnd = Random(seed)
            val r = Rig()
            repeat(50) { r.s(rnd.nextDouble() * 4.0, rnd.nextLong(100, 30_000)) }
            r.gate.onSpeed(5.0 + rnd.nextDouble() * 200.0)
            assertEquals(UiMode.Glance, r.mode)
        }
    }

    @Test fun modeAndStatusAlwaysAgree() {
        val r = Rig()
        listOf(0.0, 10.0, null, 0.0, 0.0, 3.0).forEach { r.s(it, 4_000); assertEquals(r.gate.status.value.mode, r.mode) }
        assertFalse(r.gate.status.value.reason == DrivingReason.Passenger)
    }
}
