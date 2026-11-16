package com.marketsboard.briefing

import com.marketsboard.VirtualClock
import com.marketsboard.core.Utterance
import com.marketsboard.core.UtteranceKind
import com.marketsboard.data.news.Headline
import com.marketsboard.data.settings.AppSettings
import com.marketsboard.data.settings.BriefingHistory
import com.marketsboard.speech.Speaker
import com.marketsboard.speech.SpeakerAvailability
import com.marketsboard.speech.SpeakerFault
import com.marketsboard.speech.SpeakerState
import com.marketsboard.ui.UiMode
import com.marketsboard.ui.markets.HeaderUi
import com.marketsboard.ui.markets.LiveKind
import com.marketsboard.ui.markets.QuoteRowUi
import com.marketsboard.ui.markets.RowStatus
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records what it was asked to say and lets the test drive the state like an engine would. */
class TestSpeaker : Speaker {
    override val state = MutableStateFlow<SpeakerState>(SpeakerState.Idle)
    override val availability = MutableStateFlow(SpeakerAvailability.Unknown)
    var spoken: List<Utterance> = emptyList()
    var rate = 0f
    var speakCalls = 0
    var stopCalls = 0
    override fun speak(utterances: List<Utterance>, rate: Float) { spoken = utterances; this.rate = rate; speakCalls++; state.value = SpeakerState.Preparing }
    override fun stop() { stopCalls++; state.value = SpeakerState.Idle }
    fun at(i: Int) { state.value = SpeakerState.Speaking(i, spoken.size) }
}

private class ScriptedSource(var snap: BriefingSnapshot) : BriefingSource {
    var refreshes = 0
    var refreshDelayMs = 0L
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun refresh() { refreshes++; gate?.await(); if (refreshDelayMs > 0) delay(refreshDelayMs) }
    override fun snapshot(): BriefingSnapshot = snap
}

private class MemoryHistory(var ids: Set<String> = emptySet()) : BriefingHistory {
    val writes = mutableListOf<List<String>>()
    override fun spoken(): Set<String> = ids
    override fun remember(ids: Collection<String>) { writes += ids.toList(); this.ids = this.ids + ids }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BriefingControllerTest {
    private val now = 1_700_000_000_000L
    private fun row(sym: String, price: Double?, prev: Double? = price, status: RowStatus = RowStatus.Fresh) =
        QuoteRowUi(sym, if (sym == "MSFT") "Microsoft" else "iShares Core Equity ETF Portfolio", "TSX", if (sym == "MSFT") "USD" else "CAD", price, prev, emptyList(), 2, status)

    private fun head(id: String, title: String, ageMin: Long = 30) = Headline(id, title, "", "https://x.example/$id", "CBC", now - ageMin * 60_000)

    private fun snap(
        rows: List<QuoteRowUi> = listOf(row("XEQT.TO", 41.25, 43.25), row("MSFT", 414.6, 435.94)),
        headlines: List<Headline> = listOf(head("h1", "Bank of Canada holds key rate"), head("h2", "TSX edges higher"), head("h3", "Oil slips")),
        header: HeaderUi = HeaderUi(LiveKind.Live, "Live"), offline: Boolean = false, cached: Boolean = false,
    ) = BriefingSnapshot(rows, header, headlines, now, ZoneId.of("America/Toronto"), offline, cached)

    private inner class Rig(
        scope: TestScope,
        val source: ScriptedSource = ScriptedSource(snap()),
        val settings: MutableStateFlow<AppSettings> = MutableStateFlow(AppSettings()),
        val history: MemoryHistory = MemoryHistory(),
        val speaker: TestSpeaker = TestSpeaker(),
        val active: MutableStateFlow<Boolean> = MutableStateFlow(true),
        val mode: MutableStateFlow<UiMode> = MutableStateFlow(UiMode.Normal),
        config: BriefingConfig = BriefingConfig(),
    ) {
        val clock = VirtualClock(scope.testScheduler)
        val c = BriefingController(scope.backgroundScope, source, speaker, settings, history, active, mode, config, Dispatchers.Unconfined)
        val ui get() = c.state.value
    }

    private fun TestScope.rig(source: ScriptedSource = ScriptedSource(snap()), config: BriefingConfig = BriefingConfig(), settings: AppSettings = AppSettings()) =
        Rig(this, source, MutableStateFlow(settings), config = config)

    // ---- happy path ---------------------------------------------------------------------------------------------

    @Test fun neverStartsByItself() = runTest {
        val r = rig()
        advanceTimeBy(3_600_000); runCurrent()
        assertEquals(BriefingPhase.Idle, r.ui.phase)
        assertEquals(0, r.source.refreshes)
        assertEquals(0, r.speaker.speakCalls)
    }

    @Test fun refreshesThenBuildsThenSpeaksWithTheSettingsRate() = runTest {
        val r = rig(settings = AppSettings(speechRate = 0.9f))
        r.c.onBrief()
        runCurrent()
        assertEquals(BriefingPhase.Preparing, r.ui.phase)
        assertEquals(1, r.source.refreshes)
        assertEquals(1, r.speaker.speakCalls)
        assertEquals(0.9f, r.speaker.rate, 0f)
        assertTrue(r.speaker.spoken.first().text.endsWith("Here is your market briefing."))
        assertTrue(r.speaker.spoken.any { it.kind == UtteranceKind.Headline })
    }

    @Test fun theCaptionFollowsTheSentenceBeingSpoken() = runTest {
        val r = rig()
        r.c.onBrief(); runCurrent()
        r.speaker.at(1); runCurrent()
        assertEquals(BriefingPhase.Speaking, r.ui.phase)
        assertEquals(r.speaker.spoken[1].text, r.ui.caption)
        assertEquals(2, r.ui.index)
        assertEquals(r.speaker.spoken.size, r.ui.total)
        r.speaker.at(2); runCurrent()
        assertEquals(r.speaker.spoken[2].text, r.ui.caption)
    }

    @Test fun finishingReturnsToIdleAndRemembersTheReadHeadlines() = runTest {
        val r = rig()
        r.c.onBrief(); runCurrent()
        val ids = listOf("h1", "h2", "h3")
        r.speaker.state.value = SpeakerState.Finished; runCurrent()
        assertEquals(BriefingPhase.Idle, r.ui.phase)
        assertEquals(ids, r.history.writes.single())
        assertEquals(ids.toSet(), r.history.ids)
    }

    @Test fun theNextBriefingSkipsWhatWasAlreadyRead() = runTest {
        val r = rig()
        r.c.onBrief(); runCurrent()
        r.speaker.state.value = SpeakerState.Finished; runCurrent()
        r.source.snap = snap(headlines = listOf(head("h1", "Bank of Canada holds key rate"), head("h4", "Gold climbs")))
        r.c.onBrief(); runCurrent()
        val heads = r.speaker.spoken.filter { it.kind == UtteranceKind.Headline }
        assertEquals(1, heads.size)
        assertTrue(heads.single().text.startsWith("Gold climbs"))
    }

    @Test fun onlyHeadlinesThatStartedCountAsRead() = runTest {
        val r = rig()
        r.c.onBrief(); runCurrent()
        val firstHeadline = r.speaker.spoken.indexOfFirst { it.kind == UtteranceKind.Headline }
        r.speaker.at(firstHeadline); runCurrent()
        r.c.onStop(); runCurrent()
        assertEquals(listOf(listOf("h1")), r.history.writes)
    }

    @Test fun headlinesOlderThanADayAreNotRead() = runTest {
        val r = rig(ScriptedSource(snap(headlines = listOf(head("old", "Ancient news", ageMin = 25 * 60)))))
        r.c.onBrief(); runCurrent()
        assertTrue(r.speaker.spoken.none { it.kind == UtteranceKind.Headline })
        assertTrue(r.speaker.spoken.any { it.text == "No new headlines." })
    }

    @Test fun newsOffInSettingsLeavesOutTheWholeSection() = runTest {
        val r = rig(settings = AppSettings(includeNews = false))
        r.c.onBrief(); runCurrent()
        assertTrue(r.speaker.spoken.none { it.kind == UtteranceKind.Headline || it.kind == UtteranceKind.HeadlinesIntro || it.text.contains("headlines") })
        assertTrue(r.history.writes.isEmpty())
    }

    // ---- refresh, timeout, caveats -----------------------------------------------------------------------------------

    @Test fun aSlowRefreshIsCutOffAtEightSecondsAndStillSpeaksWithACaveat() = runTest {
        val src = ScriptedSource(snap()).apply { refreshDelayMs = 60_000 }
        val r = rig(src)
        r.c.onBrief(); runCurrent()
        advanceTimeBy(7_900); runCurrent()
        assertEquals("still waiting", 0, r.speaker.speakCalls)
        assertEquals(BriefingPhase.Preparing, r.ui.phase)
        advanceTimeBy(200); runCurrent()
        assertEquals(1, r.speaker.speakCalls)
        assertTrue(r.speaker.spoken.any { it.kind == UtteranceKind.Caveat && it.text.contains("could not refresh in time") })
    }

    @Test fun aTimeoutDoesNotAddASecondCaveatWhenTheTextAlreadyHasOne() = runTest {
        val stale = snap(rows = listOf(row("XEQT.TO", 41.25, 43.25, RowStatus.Stale("30 min ago", 1_800_000))), header = HeaderUi(LiveKind.Delayed, "Delayed"))
        val r = rig(ScriptedSource(stale).apply { refreshDelayMs = 60_000 })
        r.c.onBrief(); runCurrent(); advanceTimeBy(8_100); runCurrent()
        assertEquals(1, r.speaker.spoken.count { it.kind == UtteranceKind.Caveat && it.text.startsWith("Prices may be delayed") })
        assertTrue(r.speaker.spoken.none { it.text.contains("could not refresh in time") })
    }

    @Test fun aFastRefreshHasNoTimeoutCaveat() = runTest {
        val r = rig(ScriptedSource(snap()).apply { refreshDelayMs = 2_000 })
        r.c.onBrief(); runCurrent(); advanceTimeBy(2_100); runCurrent()
        assertEquals(1, r.speaker.speakCalls)
        assertTrue(r.speaker.spoken.none { it.text.contains("could not refresh") })
    }

    @Test fun offlineAddsTheOfflineCaveat() = runTest {
        val r = rig(ScriptedSource(snap(offline = true, header = HeaderUi(LiveKind.Offline, "Offline"), rows = listOf(row("XEQT.TO", 41.25, 43.25, RowStatus.Error("Can't reach the market", "20 min ago", 1_200_000))))))
        r.c.onBrief(); runCurrent()
        assertTrue(r.speaker.spoken[1].text.startsWith("You appear to be offline"))
    }

    @Test fun staleHeadlinesGetTheirOwnCaveat() = runTest {
        val r = rig(ScriptedSource(snap(cached = true)))
        r.c.onBrief(); runCurrent()
        assertTrue(r.speaker.spoken.any { it.text == "Headlines may be out of date." })
    }

    @Test fun aThrowingSourceDoesNotCrashAndStillFinishesPreparing() = runTest {
        val src = object : BriefingSource {
            override suspend fun refresh() { error("boom") }
            override fun snapshot(): BriefingSnapshot = snap()
        }
        val r = Rig(this, ScriptedSource(snap()))
        val c = BriefingController(backgroundScope, src, r.speaker, r.settings, r.history, r.active, r.mode, BriefingConfig(), Dispatchers.Unconfined)
        c.onBrief(); runCurrent()
        assertEquals(BriefingPhase.Text, c.state.value.phase)
        assertEquals("The briefing could not be prepared.", c.state.value.message)
    }

    // ---- double taps, stop, background, focus -----------------------------------------------------------------------

    @Test fun doubleTapsAreIgnoredWhilePreparingAndSpeaking() = runTest {
        val gate = CompletableDeferred<Unit>()
        val r = rig(ScriptedSource(snap()).apply { this.gate = gate })
        r.c.onBrief(); r.c.onBrief(); r.c.onBrief(); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(1, r.source.refreshes)
        assertEquals(1, r.speaker.speakCalls)
        r.speaker.at(0); runCurrent()
        r.c.onBrief(); runCurrent()
        assertEquals(1, r.speaker.speakCalls)
        assertEquals(BriefingPhase.Speaking, r.ui.phase)
    }

    @Test fun stopSilencesAtOnceAndCancelsAPendingRefresh() = runTest {
        val r = rig(ScriptedSource(snap()).apply { refreshDelayMs = 60_000 })
        r.c.onBrief(); runCurrent()
        r.c.onStop(); runCurrent()
        assertEquals(BriefingPhase.Idle, r.ui.phase)
        advanceTimeBy(120_000); runCurrent()
        assertEquals("a cancelled preparation never speaks", 0, r.speaker.speakCalls)
        assertTrue(r.speaker.stopCalls >= 1)
    }

    @Test fun stopWhileSpeakingStopsTheSpeaker() = runTest {
        val r = rig()
        r.c.onBrief(); runCurrent(); r.speaker.at(0); runCurrent()
        val before = r.speaker.stopCalls
        r.c.onStop(); runCurrent()
        assertEquals(BriefingPhase.Idle, r.ui.phase)
        assertEquals(before + 1, r.speaker.stopCalls)
        // late engine callbacks of the stopped run change nothing
        r.speaker.state.value = SpeakerState.Speaking(3, 9); runCurrent()
        assertEquals(BriefingPhase.Idle, r.ui.phase)
    }

    @Test fun backgroundingStopsEverything() = runTest {
        val r = rig()
        r.c.onBrief(); runCurrent(); r.speaker.at(1); runCurrent()
        r.active.value = false; runCurrent()
        assertEquals(BriefingPhase.Idle, r.ui.phase)
        assertTrue(r.speaker.stopCalls >= 1)
    }

    @Test fun backgroundingDuringPreparationStopsItToo() = runTest {
        val r = rig(ScriptedSource(snap()).apply { refreshDelayMs = 5_000 })
        r.c.onBrief(); runCurrent()
        r.active.value = false; runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals(0, r.speaker.speakCalls)
        assertEquals(BriefingPhase.Idle, r.ui.phase)
    }

    @Test fun lostAudioFocusStopsWithAPlainNote() = runTest {
        val r = rig()
        r.c.onBrief(); runCurrent(); r.speaker.at(2); runCurrent()
        r.speaker.state.value = SpeakerState.Error(SpeakerFault.FocusLost); runCurrent()
        assertEquals(BriefingPhase.Text, r.ui.phase)
        assertEquals("Stopped because another app started playing audio.", r.ui.message)
        assertTrue(r.ui.lines.isEmpty())
    }

    @Test fun aNoteWithoutTextClearsItself() = runTest {
        val r = rig()
        r.c.onBrief(); runCurrent()
        r.speaker.state.value = SpeakerState.Error(SpeakerFault.FocusLost); runCurrent()
        advanceTimeBy(15_100); runCurrent()
        assertEquals(BriefingPhase.Idle, r.ui.phase)
    }

    // ---- no speech ------------------------------------------------------------------------------------------------

    @Test fun anUnavailableEngineShowsTheBriefingAsText() = runTest {
        val r = rig()
        r.speaker.availability.value = SpeakerAvailability.Unavailable
        r.c.onBrief(); runCurrent()
        assertEquals(0, r.speaker.speakCalls)
        assertEquals(BriefingPhase.Text, r.ui.phase)
        assertEquals("Speech is not available on this unit. Here is the briefing as text.", r.ui.message)
        assertTrue(r.ui.lines.size > 3)
        assertTrue(r.ui.lines.first().contains("Here is your market briefing"))
    }

    @Test fun anEngineThatFailsToStartAlsoFallsBackToText() = runTest {
        val r = rig()
        r.c.onBrief(); runCurrent()
        r.speaker.state.value = SpeakerState.Error(SpeakerFault.InitFailed); runCurrent()
        assertEquals(BriefingPhase.Text, r.ui.phase)
        assertTrue(r.ui.lines.isNotEmpty())
        assertEquals("a failed start never marks headlines as read", true, r.history.writes.isEmpty())
    }

    @Test fun anErrorMidwayKeepsWhatWasAlreadyReadAndShowsText() = runTest {
        val r = rig()
        r.c.onBrief(); runCurrent()
        val h = r.speaker.spoken.indexOfFirst { it.kind == UtteranceKind.Headline }
        r.speaker.at(h); runCurrent()
        r.speaker.state.value = SpeakerState.Error(SpeakerFault.SpeechFailed); runCurrent()
        assertEquals(BriefingPhase.Text, r.ui.phase)
        assertTrue(r.ui.message.startsWith("Speech stopped unexpectedly"))
        assertEquals(listOf(listOf("h1")), r.history.writes)
    }

    @Test fun voiceOffInSettingsShowsTextWithoutTouchingTheSpeaker() = runTest {
        val r = rig(settings = AppSettings(voiceEnabled = false))
        r.c.onBrief(); runCurrent()
        assertEquals(0, r.speaker.speakCalls)
        assertEquals(BriefingPhase.Text, r.ui.phase)
        assertTrue(r.ui.message.startsWith("Voice is off"))
    }

    @Test fun theTextOfABriefingIsNeverExposedInGlance() = runTest {
        val r = rig(settings = AppSettings(voiceEnabled = false))
        r.c.onBrief(); runCurrent()
        assertTrue(r.ui.lines.isNotEmpty())
        r.mode.value = UiMode.Glance; runCurrent()
        assertTrue("reading while moving is not allowed", r.ui.lines.isEmpty())
        assertTrue(r.ui.message.isNotEmpty())
        advanceTimeBy(8_000); runCurrent()
        assertEquals("a note does not linger while driving", BriefingPhase.Idle, r.ui.phase)
    }

    @Test fun glanceDoesNotStopTheBriefing() = runTest {
        val r = rig()
        r.c.onBrief(); runCurrent(); r.speaker.at(1); runCurrent()
        r.mode.value = UiMode.Glance; runCurrent()
        assertEquals(BriefingPhase.Speaking, r.ui.phase)
        r.c.onStop(); runCurrent()
        assertEquals(BriefingPhase.Idle, r.ui.phase)
    }

    @Test fun aNewBriefingReplacesTheTextOfTheLastOne() = runTest {
        val r = rig(settings = AppSettings(voiceEnabled = false))
        r.c.onBrief(); runCurrent()
        r.c.onBrief(); runCurrent()
        assertEquals(2, r.source.refreshes)
        r.c.onStop(); runCurrent()
        assertEquals(BriefingPhase.Idle, r.ui.phase)
    }

    // ---- triggers ---------------------------------------------------------------------------------------------------

    @Test fun theMediaKeyDoesNothingUnlessTheSettingIsOn() = runTest {
        val r = rig()
        assertFalse(r.c.trigger(TriggerSource.MediaKey))
        runCurrent()
        assertEquals(0, r.source.refreshes)
    }

    @Test fun theMediaKeyStartsAndStopsWhenEnabled() = runTest {
        val r = rig(settings = AppSettings(mediaKeyTrigger = true))
        assertTrue(r.c.trigger(TriggerSource.MediaKey)); runCurrent()
        r.speaker.at(0); runCurrent()
        assertEquals(BriefingPhase.Speaking, r.ui.phase)
        assertTrue(r.c.trigger(TriggerSource.MediaKey)); runCurrent()
        assertEquals(BriefingPhase.Idle, r.ui.phase)
        assertTrue(r.c.trigger(TriggerSource.SteeringWheel)); runCurrent()
        assertEquals(2, r.speaker.speakCalls)
    }

    @Test fun theButtonTriggerStartsAndIgnoresARepeat() = runTest {
        val r = rig()
        assertTrue(r.c.trigger(TriggerSource.Button))
        assertFalse(r.c.trigger(TriggerSource.Button))
    }
}
