package com.marketsboard.speech

import com.marketsboard.core.Utterance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the platform engine's start-up ended. */
enum class EngineInit { Ok, Failed, NoLanguage }

/** What a [SpeechMachine] needs from the platform (Android: AudioManager and TextToSpeech). Every call is made on the main thread. */
interface SpeechPlatform {
    /** Requests transient audio focus; false when refused. */
    fun requestFocus(): Boolean

    fun releaseFocus()

    /** Starts the engine; the result must come back through [SpeechMachine.onEngineInit] with the same [epoch]. */
    fun initEngine(epoch: Int)

    /** Queues every sentence and pause. False when the engine refused (an ERROR from `speak`). */
    fun speakAll(utterances: List<Utterance>, rate: Float, generation: Int): Boolean

    /** Silences the engine but keeps it. */
    fun stopEngine()

    /** Releases the engine; a later [initEngine] starts a new one. */
    fun shutdownEngine()

    /** Calls [SpeechMachine.onWatchdog] with these arguments after [ms]. */
    fun scheduleWatchdog(ms: Long, generation: Int, token: Int)
}

/**
 * The generation, focus and engine-lifecycle logic of the Android speaker as pure code (the adapter only forwards events),
 * so the awkward cases are unit tested: audio focus loss of ANY kind including "can duck" (speech is not auto-ducked, so a
 * navigation prompt would talk over the briefing) stops the speech; an engine start-up that finishes after stop, after a
 * newer speak or after shutdown cannot start the wrong briefing; an engine that refuses to speak is shut down and
 * recreated next time; a watchdog fails a briefing that stops making progress.
 * All calls on the main thread. Late callbacks carry the generation they belong to and are ignored when it is old.
 */
class SpeechMachine(
    private val platform: SpeechPlatform,
    initialAvailability: SpeakerAvailability = SpeakerAvailability.Unknown,
    private val startWatchdogMs: Long = 12_000,
    private val progressWatchdogMs: Long = 30_000,
) {
    private val _state = MutableStateFlow<SpeakerState>(SpeakerState.Idle)
    val state: StateFlow<SpeakerState> = _state.asStateFlow()
    private val _availability = MutableStateFlow(initialAvailability)
    val availability: StateFlow<SpeakerAvailability> = _availability.asStateFlow()

    var generation = 0
        private set
    private var epoch = 0
    private var ready = false
    private var initializing = false
    private var queued: Pair<List<Utterance>, Float>? = null
    private var token = 0

    fun speak(utterances: List<Utterance>, rate: Float) {
        stopInternal()
        val gen = ++generation
        _state.value = SpeakerState.Preparing
        if (utterances.isEmpty()) { _state.value = SpeakerState.Finished; return }
        if (_availability.value == SpeakerAvailability.Unavailable) { fail(gen, SpeakerFault.Unavailable); return }
        if (!platform.requestFocus()) { fail(gen, SpeakerFault.FocusDenied); return }
        queued = utterances to rate
        arm(gen, startWatchdogMs)
        if (ready) startQueued(gen) else if (!initializing) { initializing = true; platform.initEngine(epoch) }
    }

    fun stop() {
        stopInternal()
        _state.value = SpeakerState.Idle
    }

    fun shutdown() {
        stop()
        epoch++ // a start-up still in flight belongs to a dead engine
        ready = false; initializing = false
        platform.shutdownEngine()
    }

    /** The engine's start-up finished. [forEpoch] is the value given to [SpeechPlatform.initEngine]. */
    fun onEngineInit(result: EngineInit, forEpoch: Int) {
        if (forEpoch != epoch) return // shut down meanwhile: the platform already released it
        initializing = false
        val gen = generation
        when (result) {
            EngineInit.Ok -> {
                ready = true
                _availability.value = SpeakerAvailability.Available
                // Only a briefing still waiting starts; after stop() or a newer speak() there is nothing stale to say.
                if (_state.value == SpeakerState.Preparing && queued != null) startQueued(gen)
            }
            EngineInit.Failed -> { // not marked Unavailable: the next briefing tries again
                ready = false; platform.shutdownEngine()
                fail(gen, SpeakerFault.InitFailed)
            }
            EngineInit.NoLanguage -> {
                ready = false; platform.shutdownEngine()
                _availability.value = SpeakerAvailability.Unavailable
                fail(gen, SpeakerFault.Unavailable)
            }
        }
    }

    /** Audio focus was lost, for any reason (permanent, transient, or "can duck"): stop. Gaining it back needs nothing. */
    fun onFocusLost() {
        val s = _state.value
        if (s is SpeakerState.Speaking || s == SpeakerState.Preparing) fail(generation, SpeakerFault.FocusLost)
    }

    fun onUtteranceStart(gen: Int, index: Int) {
        if (gen != generation) return
        val total = queued?.first?.size ?: return
        _state.value = SpeakerState.Speaking(index, total)
        arm(gen, progressWatchdogMs)
    }

    fun onUtteranceDone(gen: Int, index: Int) {
        if (gen != generation) return
        val list = queued?.first ?: return
        if (index == list.lastIndex) {
            platform.releaseFocus()
            queued = null
            token++ // disarm
            _state.value = SpeakerState.Finished
        } else {
            arm(gen, progressWatchdogMs)
        }
    }

    fun onUtteranceError(gen: Int) { if (gen == generation) failAndRecycle(gen, SpeakerFault.SpeechFailed) }

    fun onWatchdog(gen: Int, forToken: Int) {
        if (gen != generation || forToken != token) return
        val s = _state.value
        if (s == SpeakerState.Preparing) failAndRecycle(gen, SpeakerFault.InitFailed)
        else if (s is SpeakerState.Speaking) failAndRecycle(gen, SpeakerFault.SpeechFailed)
    }

    // ---- internals ----------------------------------------------------------------------------------------------

    private fun startQueued(gen: Int) {
        val (list, rate) = queued ?: return
        val ok = try { platform.speakAll(list, rate, gen) } catch (e: Throwable) { false }
        // An engine that answers ERROR is broken: shut it down and forget it so the next briefing builds a new one.
        if (!ok) failAndRecycle(gen, SpeakerFault.SpeechFailed)
    }

    private fun arm(gen: Int, ms: Long) {
        token++
        platform.scheduleWatchdog(ms, gen, token)
    }

    private fun stopInternal() {
        generation++ // late callbacks of the old run are ignored
        token++
        queued = null
        platform.stopEngine()
        platform.releaseFocus()
    }

    private fun failAndRecycle(gen: Int, kind: SpeakerFault) {
        if (gen != generation) return
        platform.shutdownEngine()
        ready = false; initializing = false; epoch++
        fail(gen, kind)
    }

    private fun fail(gen: Int, kind: SpeakerFault) {
        if (gen != generation) return
        platform.stopEngine()
        platform.releaseFocus()
        queued = null
        token++
        _state.value = SpeakerState.Error(kind)
    }
}
