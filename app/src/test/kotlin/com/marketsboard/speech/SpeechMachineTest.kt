package com.marketsboard.speech

import com.marketsboard.core.Utterance
import com.marketsboard.core.UtteranceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The speaker's focus, generation and engine-lifecycle logic with a scripted platform (no Android). */
class SpeechMachineTest {
    private class FakePlatform : SpeechPlatform {
        val log = mutableListOf<String>()
        var focusGranted = true
        var speakOk = true
        var lastInitEpoch = -1
        var spoken: List<Utterance> = emptyList()
        var watchdogs = mutableListOf<Triple<Long, Int, Int>>()
        override fun requestFocus(): Boolean { log += "focus"; return focusGranted }
        override fun releaseFocus() { log += "release" }
        override fun initEngine(epoch: Int) { log += "init"; lastInitEpoch = epoch }
        override fun speakAll(utterances: List<Utterance>, rate: Float, generation: Int): Boolean { log += "speak"; spoken = utterances; return speakOk }
        override fun stopEngine() { log += "stopEngine" }
        override fun shutdownEngine() { log += "shutdown" }
        override fun scheduleWatchdog(ms: Long, generation: Int, token: Int) { watchdogs += Triple(ms, generation, token) }
        fun count(s: String) = log.count { it == s }
    }

    private val two = listOf(Utterance("One.", UtteranceKind.Greeting, 100), Utterance("Two.", UtteranceKind.Quote, 0))
    private fun rig(avail: SpeakerAvailability = SpeakerAvailability.Unknown): Pair<FakePlatform, SpeechMachine> {
        val p = FakePlatform()
        return p to SpeechMachine(p, avail)
    }

    private fun SpeechMachine.start(p: FakePlatform) { speak(two, 1f); onEngineInit(EngineInit.Ok, p.lastInitEpoch) }

    @Test fun happyPathSpeaksReportsProgressFinishesAndReleasesFocus() {
        val (p, m) = rig()
        m.speak(two, 1f)
        assertEquals(SpeakerState.Preparing, m.state.value)
        m.onEngineInit(EngineInit.Ok, p.lastInitEpoch)
        assertEquals(1, p.count("speak"))
        val g = m.generation
        m.onUtteranceStart(g, 0); assertEquals(SpeakerState.Speaking(0, 2), m.state.value)
        m.onUtteranceDone(g, 0)
        m.onUtteranceStart(g, 1); m.onUtteranceDone(g, 1)
        assertEquals(SpeakerState.Finished, m.state.value)
        assertEquals(SpeakerAvailability.Available, m.availability.value)
        assertEquals("release", p.log.last())
    }

    @Test fun everyKindOfFocusLossStopsTheSpeech() {
        // the adapter maps LOSS, LOSS_TRANSIENT and LOSS_TRANSIENT_CAN_DUCK to onFocusLost: speech is never auto-ducked
        for (phase in 0..1) {
            val (p, m) = rig()
            m.start(p)
            if (phase == 1) m.onUtteranceStart(m.generation, 0)
            m.onFocusLost()
            assertEquals(SpeakerState.Error(SpeakerFault.FocusLost), m.state.value)
            assertTrue(p.count("release") >= 1)
        }
    }

    @Test fun focusLossWhenNothingIsSpeakingDoesNothing() {
        val (p, m) = rig()
        m.onFocusLost()
        assertEquals(SpeakerState.Idle, m.state.value)
        m.start(p); m.stop()
        m.onFocusLost()
        assertEquals(SpeakerState.Idle, m.state.value)
    }

    @Test fun aRefusedFocusIsAnErrorWithoutStartingTheEngine() {
        val (p, m) = rig()
        p.focusGranted = false
        m.speak(two, 1f)
        assertEquals(SpeakerState.Error(SpeakerFault.FocusDenied), m.state.value)
        assertEquals(0, p.count("init"))
    }

    // ---- the init race -------------------------------------------------------------------------------------------

    @Test fun anEngineThatFinishesStartingAfterStopDoesNotSpeak() {
        val (p, m) = rig()
        m.speak(two, 1f)
        val epoch = p.lastInitEpoch
        m.stop()
        m.onEngineInit(EngineInit.Ok, epoch)
        assertEquals(0, p.count("speak"))
        assertEquals(SpeakerState.Idle, m.state.value)
    }

    @Test fun aSecondSpeakWhileTheEngineIsStartingReusesTheStartupAndSpeaksOnlyTheNewest() {
        val (p, m) = rig()
        m.speak(listOf(Utterance("Old.", UtteranceKind.Note, 0)), 1f)
        m.speak(two, 1f)
        assertEquals("only one start-up", 1, p.count("init"))
        m.onEngineInit(EngineInit.Ok, p.lastInitEpoch)
        assertEquals(1, p.count("speak"))
        assertEquals(two, p.spoken)
    }

    @Test fun anEngineStartupFromBeforeShutdownIsIgnored() {
        val (p, m) = rig()
        m.speak(two, 1f)
        val old = p.lastInitEpoch
        m.shutdown()
        m.onEngineInit(EngineInit.Ok, old)
        assertEquals(SpeakerState.Idle, m.state.value)
        assertEquals(0, p.count("speak"))
        // and the next speak starts a fresh engine
        m.speak(two, 1f)
        assertEquals(2, p.count("init"))
    }

    @Test fun lateCallbacksOfAnOldRunAreIgnored() {
        val (p, m) = rig()
        m.start(p)
        val old = m.generation
        m.stop()
        m.onUtteranceStart(old, 1); m.onUtteranceDone(old, 1); m.onUtteranceError(old)
        assertEquals(SpeakerState.Idle, m.state.value)
        m.start(p)
        m.onUtteranceDone(old, 1)
        assertEquals(SpeakerState.Preparing, m.state.value)
    }

    @Test fun initFailureIsAnErrorButNotPermanentUnavailability() {
        val (p, m) = rig()
        m.speak(two, 1f)
        m.onEngineInit(EngineInit.Failed, p.lastInitEpoch)
        assertEquals(SpeakerState.Error(SpeakerFault.InitFailed), m.state.value)
        assertEquals(SpeakerAvailability.Unknown, m.availability.value)
        assertTrue(p.count("shutdown") >= 1)
        m.speak(two, 1f)
        assertEquals("the next briefing builds a new engine", 2, p.count("init"))
    }

    @Test fun aMissingLanguageMarksTheEngineUnavailableForGood() {
        val (p, m) = rig()
        m.speak(two, 1f)
        m.onEngineInit(EngineInit.NoLanguage, p.lastInitEpoch)
        assertEquals(SpeakerState.Error(SpeakerFault.Unavailable), m.state.value)
        assertEquals(SpeakerAvailability.Unavailable, m.availability.value)
        m.speak(two, 1f)
        assertEquals("no second attempt", 1, p.count("init"))
        assertEquals(SpeakerState.Error(SpeakerFault.Unavailable), m.state.value)
    }

    // ---- the ERROR path and the watchdog -------------------------------------------------------------------------------

    @Test fun aSpeakErrorShutsTheEngineDownAndForgetsItSoTheNextSpeakRecreatesIt() {
        val (p, m) = rig()
        m.speak(two, 1f)
        p.speakOk = false
        m.onEngineInit(EngineInit.Ok, p.lastInitEpoch)
        assertEquals(SpeakerState.Error(SpeakerFault.SpeechFailed), m.state.value)
        assertTrue(p.count("shutdown") >= 1)
        p.speakOk = true
        m.speak(two, 1f)
        assertEquals("a new engine, not the broken one", 2, p.count("init"))
        m.onEngineInit(EngineInit.Ok, p.lastInitEpoch)
        assertEquals(1, p.count("speak") - 1)
    }

    @Test fun anUtteranceErrorRecyclesTheEngineToo() {
        val (p, m) = rig()
        m.start(p)
        m.onUtteranceStart(m.generation, 0)
        m.onUtteranceError(m.generation)
        assertEquals(SpeakerState.Error(SpeakerFault.SpeechFailed), m.state.value)
        assertTrue(p.count("shutdown") >= 1)
    }

    @Test fun theWatchdogFailsAnEngineThatStopsMakingProgress() {
        val (p, m) = rig()
        m.start(p)
        val g = m.generation
        m.onUtteranceStart(g, 0)
        val (_, gen, token) = p.watchdogs.last()
        // an older watchdog (progress happened since) does nothing
        val old = p.watchdogs.first()
        m.onWatchdog(old.second, old.third)
        assertTrue(m.state.value is SpeakerState.Speaking)
        m.onWatchdog(gen, token)
        assertEquals(SpeakerState.Error(SpeakerFault.SpeechFailed), m.state.value)
    }

    @Test fun theWatchdogWhilePreparingIsAnInitFailure() {
        val (p, m) = rig()
        m.speak(two, 1f)
        val (_, gen, token) = p.watchdogs.last()
        m.onWatchdog(gen, token)
        assertEquals(SpeakerState.Error(SpeakerFault.InitFailed), m.state.value)
    }

    @Test fun progressRearmsTheWatchdogAndFinishingDisarmsIt() {
        val (p, m) = rig()
        m.start(p)
        val g = m.generation
        val before = p.watchdogs.size
        m.onUtteranceStart(g, 0); m.onUtteranceDone(g, 0)
        assertTrue(p.watchdogs.size >= before + 2)
        m.onUtteranceStart(g, 1); m.onUtteranceDone(g, 1)
        val last = p.watchdogs.last()
        m.onWatchdog(last.second, last.third)
        assertEquals("finished stays finished", SpeakerState.Finished, m.state.value)
    }

    @Test fun anEmptyBriefingFinishesAtOnceAndAnUnavailableEngineFailsAtOnce() {
        val (_, m) = rig()
        m.speak(emptyList(), 1f)
        assertEquals(SpeakerState.Finished, m.state.value)
        val (_, u) = rig(SpeakerAvailability.Unavailable)
        u.speak(two, 1f)
        assertEquals(SpeakerState.Error(SpeakerFault.Unavailable), u.state.value)
        assertFalse(u.state.value is SpeakerState.Preparing)
    }
}
