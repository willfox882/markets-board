package com.marketsboard.sandbox

import com.marketsboard.briefing.BriefingPhase
import com.marketsboard.core.DrivingReason
import com.marketsboard.data.Clock
import com.marketsboard.ui.UiMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real gate, presenters, briefing controller and stores in the sandbox environment, with a controllable gate clock and the fake speaker. */
class SandboxDrivingEnvTest {
    private class ManualClock : Clock {
        @Volatile var mono = 1_000_000L
        override fun nowMs() = 1_700_000_000_000L + mono
        override fun monoMs() = mono
    }

    private suspend fun <T> until(timeoutMs: Long = 20_000, f: () -> T?): T = withTimeout(timeoutMs) {
        var v = f()
        while (v == null) { delay(20); v = f() }
        v
    }

    private fun env(block: suspend (SandboxEnv, ManualClock) -> Any?) {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val clock = ManualClock()
            try { block(SandboxEnv(scope, gateClock = clock, autoFeed = false), clock) } finally { scope.cancel() }
        }
    }

    @Test fun speedLocksEveryPresenterAndTenQuietSecondsUnlock() = env { e, clock ->
        e.driveAt(0.0)
        assertEquals(UiMode.Normal, e.driving.mode.value)
        e.presenter.onToggleEdit()
        until { e.presenter.state.value.takeIf { it.editMode } }
        e.driveAt(45.0)
        until { e.presenter.state.value.takeIf { it.mode == UiMode.Glance && !it.editMode } }
        e.presenter.onToggleEdit(); e.presenter.onOpen("MSFT"); e.presenter.onAddOpen()
        delay(200)
        val g = e.presenter.state.value
        assertTrue(!g.editMode && g.detail == null && g.add == null)
        assertEquals(DrivingReason.Moving, e.driving.status.value.reason)
        repeat(10) { clock.mono += 1_000; e.driveAt(0.0) } // the stop began at the first of these: nine seconds so far
        assertEquals("nine quiet seconds are not enough", UiMode.Glance, e.driving.mode.value)
        clock.mono += 1_000; e.driveAt(0.0)
        assertEquals(UiMode.Normal, e.driving.mode.value)
    }

    @Test fun gpsLostKeepsItLockedAndNoPermissionStaysNormalWithAReason() = env { e, clock ->
        e.driveAt(60.0)
        e.gpsLost.value = true; e.feed()
        clock.mono += 120_000; e.feed()
        assertEquals(UiMode.Glance, e.driving.mode.value)
        assertEquals(DrivingReason.NoSignalWhileMoving, e.driving.status.value.reason)
        e.location.flow.value = false
        until { e.driving.status.value.takeIf { it.reason == DrivingReason.PermissionMissing } }
        assertEquals(UiMode.Normal, e.driving.mode.value)
        e.settingsPresenter.onGrantLocation() // the sandbox grants it
        until { e.driving.status.value.takeIf { it.reason != DrivingReason.PermissionMissing } }
    }

    @Test fun passengerModeCannotBeSwitchedOnWhileLockedButWorksWhenParked() = env { e, clock ->
        e.driveAt(70.0)
        until { e.settingsPresenter.state.value.takeIf { it.mode == UiMode.Glance } }
        e.settingsPresenter.onPassengerMode(true)
        delay(200)
        assertEquals(UiMode.Glance, e.driving.mode.value)
        assertTrue(!e.settingsStore.settings.value.passengerMode)
        repeat(11) { clock.mono += 1_000; e.driveAt(0.0) }
        e.settingsPresenter.onPassengerMode(true)
        until { e.driving.status.value.takeIf { it.reason == DrivingReason.Passenger } }
        e.driveAt(90.0)
        assertEquals("passenger mode shows the full UI at speed", UiMode.Normal, e.driving.mode.value)
        e.settingsPresenter.onPassengerMode(false)
        until { e.driving.mode.value.takeIf { it == UiMode.Glance } }
    }

    @Test fun briefMeWorksWhileDrivingAndSpeaksWithTheFakeSpeaker() = env { e, _ ->
        e.time.speed = 60.0
        e.driveAt(80.0)
        e.briefing.onBrief()
        until { e.briefing.state.value.takeIf { it.phase == BriefingPhase.Speaking } }
        assertTrue(e.briefing.state.value.caption.isNotEmpty())
        until { e.briefing.state.value.takeIf { it.phase == BriefingPhase.Idle && e.speaker.log.value.isNotEmpty() } }
        val said = e.speaker.log.value
        assertTrue(said.first().contains("Here is your market briefing"))
        val ns = e.news.state.value
        assertTrue("headlines were read and remembered: $said items=${ns.items.size} overall=${ns.overall} feeds=${ns.feedStates.map { it.status to it.lastError }} now=${e.news.correctedNowMs()} first=${ns.items.firstOrNull()?.publishedMs}", e.history.spoken().isNotEmpty())
    }

    @Test fun anUnavailableEngineGivesTheTextFallbackAndGlanceHidesTheLines() = env { e, _ ->
        e.speaker.mode = FakeSpeakerMode.Unavailable
        e.briefing.onBrief()
        val s = until { e.briefing.state.value.takeIf { it.phase == BriefingPhase.Text } }
        assertTrue(s.lines.isNotEmpty())
        e.driveAt(60.0)
        until { e.briefing.state.value.takeIf { it.lines.isEmpty() } }
    }

    @Test fun anErrorMidwayEndsInText() = env { e, _ ->
        e.time.speed = 60.0
        e.speaker.mode = FakeSpeakerMode.ErrorMidway
        e.briefing.onBrief()
        val s = until { e.briefing.state.value.takeIf { it.phase == BriefingPhase.Text } }
        assertTrue(s.message.startsWith("Speech stopped unexpectedly"))
    }
}
