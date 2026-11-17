package com.marketsboard.core

import com.marketsboard.data.FakeClock
import com.marketsboard.data.settings.AppSettings
import com.marketsboard.data.settings.AppSettingsStore
import com.marketsboard.data.tempDir
import com.marketsboard.ui.UiMode
import com.marketsboard.ui.settings.LocationAccess
import com.marketsboard.ui.settings.SettingsPresenter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's "Driving lock" setting (0.6.1): off by default, off means Normal at any speed and no GPS nag; on is the old gate. */
@OptIn(ExperimentalCoroutinesApi::class)
class DrivingLockSettingTest {
    private fun file() = File(tempDir(), "settings.json")

    // ---- the setting -------------------------------------------------------------------------------------------------

    @Test fun theLockIsOffByDefault() {
        assertFalse(AppSettings().drivingLock)
        assertFalse(AppSettingsStore(file(), FakeClock()).settings.value.drivingLock)
        assertFalse(DrivingGate(FakeClock()).also { it.onSpeed(90.0) }.status.value.mode == UiMode.Glance)
    }

    @Test fun theLockSurvivesARestart() {
        val f = file()
        val s = AppSettingsStore(f, FakeClock())
        s.update { it.copy(drivingLock = true) }
        assertTrue(s.flush())
        assertTrue(f.readText().contains("\"drivingLock\": true"))
        assertTrue(AppSettingsStore(f, FakeClock()).settings.value.drivingLock)
        s.update { it.copy(drivingLock = false) }
        assertTrue(s.flush())
        assertFalse(AppSettingsStore(f, FakeClock()).settings.value.drivingLock)
    }

    @Test fun aV1FileWithoutTheKeyMeansOffAndKeepsTheOtherValues() {
        val f = file()
        f.writeText("""{"version":1,"voiceEnabled":false,"speechRate":0.9,"includeNews":false,"mediaKeyTrigger":true}""")
        val s = AppSettingsStore(f, FakeClock())
        assertFalse(s.settings.value.drivingLock)
        assertEquals(AppSettings(voiceEnabled = false, speechRate = 0.9f, includeNews = false, mediaKeyTrigger = true), s.settings.value)
        assertEquals(1, AppSettingsStore.VERSION)
        // a wrong type is also "off", never a crash
        f.writeText("""{"version":1,"drivingLock":"yes"}""")
        assertFalse(AppSettingsStore(f, FakeClock()).settings.value.drivingLock)
    }

    @Test fun theFileStaysVersionOne() {
        val s = AppSettingsStore(file(), FakeClock())
        assertTrue(AppSettingsStore.encode(s.settings.value).contains("\"version\": 1"))
    }

    // ---- the gate ----------------------------------------------------------------------------------------------------

    private class Rig(lock: Boolean, permission: Boolean = true) {
        val clock = FakeClock()
        val gate = DrivingGate(clock, permission = permission, lockEnabled = lock)
        fun s(kmh: Double?, ms: Long = 1_000) { gate.onSpeed(kmh); clock.advance(ms); gate.tick() }
    }

    @Test fun withTheLockOffTheModeIsNormalAtAnySpeed() {
        val r = Rig(lock = false)
        assertEquals(UiMode.Normal, r.gate.mode.value)
        assertEquals(DrivingReason.LockOff, r.gate.status.value.reason)
        listOf(0.0, 12.0, 80.0, 130.0, 80.0, null, 200.0).forEach { r.s(it); assertEquals(UiMode.Normal, r.gate.mode.value) }
        repeat(3) { r.s(95.0) }
        assertEquals(UiMode.Normal, r.gate.mode.value)
    }

    @Test fun withTheLockOffThereIsNoCheckingNoNagAndNoParkedPrompt() {
        val r = Rig(lock = false)
        r.gate.restart()
        assertEquals("no Checking Glance after a start", UiMode.Normal, r.gate.mode.value)
        r.clock.advance(120_000); r.gate.tick()
        assertFalse("no GPS unknown nag with no fix", r.gate.status.value.unknown)
        assertFalse(r.gate.status.value.parkedPrompt)
        r.gate.setLocationOn(false)
        assertFalse(r.gate.status.value.unknown)
        val noPermission = Rig(lock = false, permission = false)
        assertFalse("missing permission is not a warning while the lock is off", noPermission.gate.status.value.unknown)
        assertEquals(DrivingReason.LockOff, noPermission.gate.status.value.reason)
        r.gate.setPassenger(true)
        assertEquals(DrivingReason.LockOff, r.gate.status.value.reason)
    }

    @Test fun switchingTheLockOnReengagesAndAFastCarLocks() {
        val r = Rig(lock = false)
        r.s(90.0)
        r.gate.setLockEnabled(true)
        assertEquals("the lock starts from Checking: not knowing counts as moving", UiMode.Glance, r.gate.mode.value)
        assertEquals(DrivingReason.Checking, r.gate.status.value.reason)
        r.s(60.0)
        assertEquals(UiMode.Glance, r.gate.mode.value)
        assertEquals(DrivingReason.Moving, r.gate.status.value.reason)
        // and the old rules apply: a stop of ten seconds unlocks
        repeat(13) { r.s(0.0) }
        assertEquals(UiMode.Normal, r.gate.mode.value)
        assertEquals(DrivingReason.Parked, r.gate.status.value.reason)
        r.s(40.0)
        assertEquals(UiMode.Glance, r.gate.mode.value)
    }

    @Test fun switchingTheLockOffWhileLockedOpensEverythingAndForgetsTheLock() {
        val r = Rig(lock = true)
        r.s(70.0)
        assertEquals(UiMode.Glance, r.gate.mode.value)
        r.gate.setLockEnabled(false)
        assertEquals(UiMode.Normal, r.gate.mode.value)
        assertEquals(DrivingReason.LockOff, r.gate.status.value.reason)
        r.gate.setLockEnabled(true) // back on: nothing from before is trusted, Checking again
        assertEquals(DrivingReason.Checking, r.gate.status.value.reason)
    }

    @Test fun withTheLockOnTheNagAndCheckingAreExactlyAsBefore() {
        val r = Rig(lock = true)
        assertEquals(DrivingReason.Checking, r.gate.status.value.reason)
        r.clock.advance(9_000); r.gate.tick()
        assertEquals(DrivingReason.NoFixYet, r.gate.status.value.reason)
        assertTrue(r.gate.status.value.unknown)
    }

    // ---- the wiring: setting -> gate, presenter -> setting -----------------------------------------------------------

    @Test fun bindFollowsTheSettingBothWays() = runTest {
        val clock = FakeClock()
        val store = AppSettingsStore(file(), clock)
        val gate = DrivingGate(clock)
        gate.bind(backgroundScope, store.settings, MutableStateFlow(true))
        runCurrent()
        gate.onSpeed(80.0); gate.onSpeed(80.0)
        assertEquals("off by default: no lock", UiMode.Normal, gate.mode.value)
        store.update { it.copy(drivingLock = true) }
        runCurrent()
        assertEquals(DrivingReason.Checking, gate.status.value.reason)
        gate.onSpeed(80.0)
        assertEquals(UiMode.Glance, gate.mode.value)
        store.update { it.copy(drivingLock = false) }
        runCurrent()
        assertEquals(UiMode.Normal, gate.mode.value)
    }

    private class CountingLocation : LocationAccess {
        override val granted = MutableStateFlow(false)
        var requests = 0
        override fun request() { requests++ }
    }

    @Test fun thePresenterSwitchPersistsAndTurningTheLockOffClearsPassenger() = runTest {
        val clock = FakeClock()
        val f = file()
        val store = AppSettingsStore(f, clock)
        val gate = DrivingGate(clock)
        gate.bind(backgroundScope, store.settings, MutableStateFlow(true))
        val p = SettingsPresenter(backgroundScope, store, gate, CountingLocation(), io = Dispatchers.Unconfined)
        runCurrent()
        assertFalse(p.state.value.settings.drivingLock)
        p.onDrivingLock(true); runCurrent()
        assertTrue(p.state.value.settings.drivingLock)
        assertTrue(AppSettingsStore(f, clock).settings.value.drivingLock)
        gate.onSpeed(0.0) // the first stopped fix ends Checking, so the presenter is not in Glance
        p.onPassengerMode(true); runCurrent()
        assertTrue(store.settings.value.passengerMode)
        p.onDrivingLock(false); runCurrent()
        assertFalse(store.settings.value.drivingLock)
        assertFalse("Passenger mode cannot linger while hidden", store.settings.value.passengerMode)
        assertEquals(UiMode.Normal, p.state.value.mode)
    }

    @Test fun theLockCannotBeSwitchedOffWhileTheTruckMovesWithTheLockOn() = runTest {
        val clock = FakeClock()
        val store = AppSettingsStore(file(), clock)
        store.update { it.copy(drivingLock = true) }
        val gate = DrivingGate(clock)
        gate.bind(backgroundScope, store.settings, MutableStateFlow(true))
        val p = SettingsPresenter(backgroundScope, store, gate, CountingLocation(), io = Dispatchers.Unconfined)
        runCurrent()
        gate.onSpeed(70.0)
        assertEquals(UiMode.Glance, gate.mode.value)
        p.onDrivingLock(false); runCurrent()
        assertTrue(store.settings.value.drivingLock)
        assertEquals(UiMode.Glance, gate.mode.value)
    }
}
