package com.marketsboard.briefing

import com.marketsboard.core.Briefing
import com.marketsboard.core.BriefingInput
import com.marketsboard.core.Briefings
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
import com.marketsboard.ui.markets.QuoteRowUi
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class BriefingPhase {
    Idle,

    /** Refreshing the data, building the text, starting the engine. */
    Preparing,

    /** Speaking: [BriefingUiState.caption] is the current sentence. */
    Speaking,

    /** Not speaking: [BriefingUiState.message] says why or what, [BriefingUiState.lines] is the briefing as text (never in Glance). */
    Text,
}

/** What the briefing UI draws. */
data class BriefingUiState(
    val phase: BriefingPhase = BriefingPhase.Idle,
    /** The sentence being spoken (blank while preparing). */
    val caption: String = "",
    /** Plain-language note: why the briefing is only text, why it stopped. */
    val message: String = "",
    /** The briefing as text lines when it could not be spoken. Empty in Glance and when there is nothing to read. */
    val lines: List<String> = emptyList(),
    /** 1 based number of the sentence being spoken and how many there are (0 when not speaking). */
    val index: Int = 0,
    val total: Int = 0,
) {
    val active: Boolean get() = phase == BriefingPhase.Preparing || phase == BriefingPhase.Speaking
    val visible: Boolean get() = phase != BriefingPhase.Idle
}

/** What the screens may do with the briefing. Allowed in BOTH UI modes (it is audio). */
interface BriefingActions {
    /** "Brief me". Ignored while a briefing is preparing or speaking. */
    fun onBrief() {}

    /** The Stop button: stops speech, or closes the text. */
    fun onStop() {}

    companion object { val None: BriefingActions = object : BriefingActions {} }
}

/** Everything the briefing needs from the data layer at one moment. */
data class BriefingSnapshot(
    val rows: List<QuoteRowUi>,
    val header: HeaderUi,
    val headlines: List<Headline>,
    val nowMs: Long,
    val zone: ZoneId = ZoneId.systemDefault(),
    /** The device has no connection. */
    val offline: Boolean = false,
    /** The headlines are not freshly fetched (cache, or the last pass failed). */
    val headlinesFromCache: Boolean = false,
)

/** The data side of the briefing (the live one wraps the repositories; tests script it). */
interface BriefingSource {
    /** Refreshes quotes and news and returns when both passes are over. The caller applies the timeout. Never throws. */
    suspend fun refresh()

    fun snapshot(): BriefingSnapshot
}

data class BriefingConfig(
    /** Longest the refresh may take before the briefing is built from what is held (plus a caveat). */
    val refreshTimeoutMs: Long = 8_000,
    /** How long a note without text lines stays on screen. */
    val noticeMs: Long = 15_000,
)

/**
 * Runs the spoken briefing. "Brief me" refreshes quotes and news (timeout about 8 s total, because the pollers run only
 * while the Activity is resumed), builds the text with [Briefings.build] (skipping headlines already read, only those from
 * the last 24 h), and speaks it through the [Speaker]. It never starts by itself: only [onBrief] or [trigger].
 *
 * - Double taps are ignored while preparing or speaking; [onStop] stops everything at once.
 * - It stops when the app goes to the background ([isActive] false) and when the speaker reports the audio focus was lost.
 * - Headlines count as read once their sentence starts; the ids are persisted through [history] (off the main thread) at
 *   the end, however it ends.
 * - If the speaker is unavailable, or voice is off in Settings, the briefing is shown as text with a plain message. In
 *   Glance the text lines are never shown (reading while moving is not allowed), only the message.
 * - A refresh that times out still speaks, with an extra caveat unless the text already carries one.
 * Allowed in Glance: it is audio. Call from the main thread.
 */
class BriefingController(
    private val scope: CoroutineScope,
    private val source: BriefingSource,
    private val speaker: Speaker,
    private val settings: StateFlow<AppSettings>,
    private val history: BriefingHistory,
    isActive: StateFlow<Boolean>,
    mode: StateFlow<UiMode> = MutableStateFlow(UiMode.Normal),
    private val config: BriefingConfig = BriefingConfig(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BriefingActions, BriefingTrigger {
    private val inner = MutableStateFlow(BriefingUiState())

    /** The UI state; in Glance the text lines are removed (the controller, not the screen, enforces that). */
    val state: StateFlow<BriefingUiState> = combine(inner, mode) { s, m -> if (m == UiMode.Glance && s.lines.isNotEmpty()) s.copy(lines = emptyList()) else s }
        .stateIn(scope, SharingStarted.Eagerly, BriefingUiState())

    private var job: Job? = null
    private var clearJob: Job? = null
    private var run = 0

    init {
        // Going to the background ends everything.
        scope.launch { isActive.collect { if (!it) endAll() } }
        // Entering Glance while a text briefing is showing: it will not linger.
        scope.launch { mode.collect { if (it == UiMode.Glance && inner.value.phase == BriefingPhase.Text) scheduleClear(config.noticeMs / 2) } }
    }

    override fun onBrief() { start() }

    override fun onStop() { endAll() }

    override fun trigger(source: TriggerSource): Boolean = when (source) {
        TriggerSource.Button -> start()
        TriggerSource.MediaKey, TriggerSource.SteeringWheel -> when {
            !settings.value.mediaKeyTrigger -> false
            inner.value.active -> { endAll(); true }
            else -> start()
        }
    }

    /** Stops speech and hides the briefing, whatever state it is in. */
    private fun endAll() {
        val wasActive = inner.value.active
        run++
        job?.cancel()
        job = null
        clearJob?.cancel()
        speaker.stop()
        inner.value = BriefingUiState()
        if (wasActive) commitHeard()
    }

    private fun start(): Boolean {
        if (inner.value.active) return false // double tap
        clearJob?.cancel()
        val id = ++run
        inner.value = BriefingUiState(BriefingPhase.Preparing)
        heard = emptyList()
        job = scope.launch {
            try {
                work(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (id == run) finish(id, BriefingUiState(BriefingPhase.Text, message = "The briefing could not be prepared."))
            }
        }
        return true
    }

    private var ids: List<String> = emptyList()
    private var utterances: List<Utterance> = emptyList()
    private var heard: List<String> = emptyList()

    private suspend fun work(id: Int) {
        // 1. Refresh with a time box. A timeout cancels the fetches; whatever is held is used.
        val completed = withTimeoutOrNull(config.refreshTimeoutMs) { source.refresh(); true } != null
        if (id != run) return
        // 2. Build.
        val s = settings.value
        val snap = source.snapshot()
        val briefing: Briefing = Briefings.build(
            BriefingInput(
                rows = snap.rows, headlines = snap.headlines, nowMs = snap.nowMs, zone = snap.zone, header = snap.header, offline = snap.offline,
                alreadySpoken = history.spoken(), headlinesFromCache = snap.headlinesFromCache || !completed, includeHeadlines = s.includeNews,
            ),
        )
        var list = briefing.utterances
        if (!completed && list.none { it.kind == UtteranceKind.Caveat && (it.text.startsWith("Prices may be delayed") || it.text.startsWith("You appear to be offline")) }) {
            list = listOf(list.first(), Utterance("I could not refresh in time, so some numbers may be old.", UtteranceKind.Caveat, 400)) + list.drop(1)
        }
        utterances = list
        ids = briefing.headlineIds
        // 3. Text only, or speak.
        val textLines = list.map { it.text }
        when {
            !s.voiceEnabled -> return finish(id, BriefingUiState(BriefingPhase.Text, message = "Voice is off in Settings. Here is the briefing as text.", lines = textLines))
            speaker.availability.value == SpeakerAvailability.Unavailable -> return finish(id, BriefingUiState(BriefingPhase.Text, message = UNAVAILABLE, lines = textLines))
        }
        speaker.speak(list, s.speechRate)
        // 4. Follow the speaker until it ends. speak() set Preparing before returning, so no stale end state is seen.
        val end = speaker.state.first { st ->
            if (id != run) return@first true
            if (st is SpeakerState.Speaking) {
                val i = st.index.coerceIn(0, list.lastIndex)
                markHeard(i)
                inner.value = BriefingUiState(BriefingPhase.Speaking, caption = list[i].text, index = i + 1, total = list.size)
            }
            st is SpeakerState.Finished || st is SpeakerState.Error
        }
        if (id != run) return
        when (end) {
            SpeakerState.Finished -> {
                markHeard(list.lastIndex)
                finish(id, BriefingUiState())
            }
            is SpeakerState.Error -> {
                speaker.stop()
                finish(id, errorState(end.kind, textLines))
            }
            else -> Unit
        }
    }

    private fun errorState(kind: SpeakerFault, textLines: List<String>): BriefingUiState = when (kind) {
        SpeakerFault.Unavailable, SpeakerFault.InitFailed -> BriefingUiState(BriefingPhase.Text, message = UNAVAILABLE, lines = textLines)
        SpeakerFault.SpeechFailed -> BriefingUiState(BriefingPhase.Text, message = "Speech stopped unexpectedly. Here is the briefing as text.", lines = textLines)
        SpeakerFault.FocusDenied -> BriefingUiState(BriefingPhase.Text, message = "Another app is using the audio right now. Here is the briefing as text.", lines = textLines)
        SpeakerFault.FocusLost -> BriefingUiState(BriefingPhase.Text, message = "Stopped because another app started playing audio.")
    }

    /** Headlines whose sentence has started count as read. [i] is the sentence index reached. */
    private fun markHeard(i: Int) {
        val reached = utterances.take(i + 1).count { it.kind == UtteranceKind.Headline }
        if (reached > heard.size) heard = ids.take(reached)
    }

    private fun finish(id: Int, ui: BriefingUiState) {
        if (id != run) return
        inner.value = ui
        commitHeard()
        if (ui.phase == BriefingPhase.Text) scheduleClear(if (ui.lines.isEmpty()) config.noticeMs else config.noticeMs * 8)
    }

    private fun commitHeard() {
        val h = heard
        heard = emptyList()
        if (h.isEmpty()) return
        // Off the main thread, and not part of the briefing job (which may be cancelled right now).
        scope.launch(io) { try { history.remember(h) } catch (e: Throwable) { /* a lost write at worst repeats a headline */ } }
    }

    private fun scheduleClear(ms: Long) {
        clearJob?.cancel()
        val id = run
        clearJob = scope.launch {
            delay(ms)
            if (id == run && inner.value.phase == BriefingPhase.Text) inner.value = BriefingUiState()
        }
    }

    private companion object {
        const val UNAVAILABLE = "Speech is not available on this unit. Here is the briefing as text."
    }
}
